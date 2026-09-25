/**
 * 厂商中立 Model 端口与 HTTP Adapter。
 *
 * <p>Model 只暴露 Agent 语义：有序 ContentBlock 流、停止原因和下一份 ModelState。
 * HttpModel 负责框架信封、SSE 切帧与错误标准化，不解析任何模型厂商字段；
 * OpenAI、Anthropic、Responses API 等 wire protocol 必须由服务端 Provider 转换。
 */
import { isNetworkError, toAgentErrorFromNetwork, toAgentErrorFromResponse } from '../errors';
import type {
  AgentError,
  AgentMessage,
  JsonObject,
  ModelState,
  ModelTargetRef,
} from '../types';
import {
  defaultHttpTransport,
  isAbortError,
  normalizeEndpoint,
  type HttpTransport,
} from './http';
import { SseParser } from './sse';

/** 提供给模型的最小 Tool 描述；执行器、权限与来源不得进入模型请求。 */
export interface ModelToolDefinition {
  /** 与 ToolRegistrySnapshot 中定义一致的完整名称。 */
  name: string;
  /** 帮助模型判断调用时机的能力描述。 */
  description: string;
  /** 模型生成参数必须遵守的 JSON Schema。 */
  inputSchema: JsonObject;
}

/** 一次厂商中立模型请求。 */
export interface ModelRequest {
  /** 本轮冻结的目标身份，服务端精确检查修订和权限。 */
  modelTarget: ModelTargetRef;
  /** 当前执行可见的完整稳定消息历史。 */
  messages: readonly AgentMessage[];
  /** 当前历史对应的 Provider 续接状态。 */
  modelState: ModelState | null;
  /** 本次 Assistant 消息预先分配的 ID，Provider 用它关联返回状态。 */
  responseMessageId: string;
  /** 当前 Tool 快照映射出的最小模型定义。 */
  tools: readonly ModelToolDefinition[];
  /** 可选采样温度；具体合法范围由 Provider 校验。 */
  temperature?: number | null;
  /** 可选最大输出 token 数。 */
  maxTokens?: number | null;
}

/** 模型调用的框架链路上下文，不会进入厂商请求体。 */
export interface ModelCallContext {
  /** 串联浏览器、网关、模型与 Tool 审计的链路标识。 */
  traceId: string;
  /** 当前会话标识；新会话第一次调用时为 null。 */
  conversationId: string | null;
}

/** 模型开始产生的内容块；Tool Call 的稳定身份在 start 时确定。 */
export type ModelBlockStart =
  | { readonly type: 'text' }
  | { readonly type: 'reasoning' }
  | {
      readonly type: 'tool-call';
      readonly callId: string;
      readonly name: string;
    };

/** 模型内容增量；Tool 参数保持字符串片段，完成后由 Runtime 统一解析。 */
export type ModelBlockDelta =
  | { readonly type: 'text'; readonly text: string }
  | { readonly type: 'reasoning'; readonly text: string }
  | { readonly type: 'tool-call'; readonly argumentsDelta: string };

/** 模型停止原因；不能识别的厂商值必须明确映射为 other。 */
export type ModelStopReason =
  | 'end-turn'
  | 'tool-use'
  | 'max-tokens'
  | 'stop-sequence'
  | 'other';

/** Provider 报告的 token 使用量；协议仍保留 null 以便 Runtime 显式拒绝缺失值。 */
export interface ModelUsage {
  /** 输入上下文 token 数。 */
  inputTokens: number;
  /** 本次输出 token 数。 */
  outputTokens: number;
  /** 厂商报告的总 token 数。 */
  totalTokens: number;
}

/** Provider 标准化后的模型流；Runtime 不得再解析任何厂商 JSON。 */
export type ModelStreamEvent =
  | {
      readonly type: 'block-start';
      readonly index: number;
      readonly block: ModelBlockStart;
    }
  | {
      readonly type: 'block-delta';
      readonly index: number;
      readonly delta: ModelBlockDelta;
    }
  | {
      readonly type: 'block-stop';
      readonly index: number;
    }
  | {
      readonly type: 'message-stop';
      readonly stopReason: ModelStopReason;
      readonly usage: ModelUsage | null;
      readonly modelState: ModelState | null;
    };

/** 模型调用端口；实现通过 AsyncIterable 保留背压与标准 AbortSignal 语义。 */
export interface Model {
  /** 发起一次流式调用，HTTP、协议或 Provider 失败通过迭代器异常传播。 */
  stream(
    request: ModelRequest,
    context: ModelCallContext,
    signal: AbortSignal,
  ): AsyncIterable<ModelStreamEvent>;
}

/** 网关流内错误帧；SSE 已开始后无法再改变 HTTP 状态码。 */
interface ModelErrorFrame {
  /** 错误事件判别字段。 */
  readonly type: 'error';
  /** 已由服务端标准化的框架错误。 */
  readonly error: AgentError;
}

/** HTTP Model Adapter：共享宿主传输层，并只接受框架结构化 SSE 事件。 */
export class HttpModel implements Model {
  /** 规范化后的框架 API 根路径。 */
  private readonly endpoint: string;
  /** 模型请求与其他 Agent API 共用的宿主安全传输。 */
  private readonly transport: HttpTransport;

  /** 创建模型 Adapter；未注入传输时使用同源 FetchHttpTransport。 */
  constructor(endpoint: string, transport: HttpTransport = defaultHttpTransport) {
    this.endpoint = normalizeEndpoint(endpoint);
    this.transport = transport;
  }

  /**
   * 零事件网络失败允许单次重连；一旦收到框架事件便禁止重试，避免重复模型输出。
   * message-stop 是框架协议终态，交付后发起非阻塞 reader 取消；迟到传输错误只属于清理结果。
   * Abort 和 HTTP/协议错误始终原样结束，不进入网络重试。
   */
  async *stream(
    request: ModelRequest,
    context: ModelCallContext,
    signal: AbortSignal,
  ): AsyncIterable<ModelStreamEvent> {
    let delivered = 0;
    try {
      for await (const event of this.streamOnce(request, context, signal)) {
        delivered += 1;
        yield event;
      }
      return;
    } catch (cause) {
      if (delivered > 0 || signal.aborted || !isNetworkError(cause)) {
        throw cause;
      }
    }
    for await (const event of this.streamOnce(request, context, signal)) {
      yield event;
    }
  }

  /** 发起一次 HTTP 请求并把 SSE data 严格转换成框架事件。 */
  private async *streamOnce(
    request: ModelRequest,
    context: ModelCallContext,
    signal: AbortSignal,
  ): AsyncIterable<ModelStreamEvent> {
    let response: Response;
    try {
      response = await this.transport.request(`${this.endpoint}/model/stream`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
        body: JSON.stringify({ ...context, request }),
        signal,
      });
    } catch (cause) {
      if (isAbortError(cause)) {
        throw cause;
      }
      throw toAgentErrorFromNetwork(cause);
    }
    if (!response.ok || response.body == null) {
      throw await toAgentErrorFromResponse(response);
    }

    const reader = response.body.getReader();
    const parser = new SseParser();
    const decoder = new TextDecoder();
    let protocolCompleted = false;
    try {
      for (;;) {
        const { done, value } = await readSseChunk(reader, signal);
        const chunk = decoder.decode(value, { stream: !done });
        for (const data of done ? parser.end(chunk) : parser.feed(chunk)) {
          const event = parseFrameworkEvent(data);
          if (event.type === 'error') {
            throw event.error;
          }
          if (event.type === 'message-stop') {
            protocolCompleted = true;
          }
          yield event;
          if (protocolCompleted) {
            return;
          }
        }
        if (done) {
          return;
        }
      }
    } finally {
      if (protocolCompleted) {
        try {
          void reader.cancel().catch(() => {
            // message-stop 已封闭协议结果；传输清理失败不得推翻稳定模型响应。
          });
        } catch {
          // reader.cancel 的同步清理失败同样不属于模型协议结果。
        }
      }
      reader.releaseLock();
    }
  }
}

/**
 * 读取一段 SSE 字节并统一网络错误。
 *
 * <p>fetch 成功只代表响应头已到达；代理仍可能在首个完整事件前重置正文流。
 * 该边界必须把 read() 的原生异常标准化，外层才能安全执行“零事件仅重连一次”；
 * 用户取消保持原异常，由 Runtime 按中止语义收口。
 */
async function readSseChunk(
  reader: ReadableStreamDefaultReader<Uint8Array>,
  signal: AbortSignal,
): Promise<ReadableStreamReadResult<Uint8Array>> {
  try {
    return await reader.read();
  } catch (cause) {
    if (signal.aborted || isAbortError(cause)) {
      throw cause;
    }
    throw toAgentErrorFromNetwork(cause);
  }
}

/**
 * 严格解析网关事件。
 *
 * <p>网关已经是受控框架边界，非法 JSON 或未知事件代表前后端版本不一致，
 * 必须显式失败；禁止像厂商心跳一样静默忽略。所有层级都执行精确 key 校验，
 * 拒绝已知字段之外的任何 vendor 或旧版字段，防止协议悄悄漂移。
 */
function parseFrameworkEvent(data: string): ModelStreamEvent | ModelErrorFrame {
  let parsed: unknown;
  try {
    parsed = JSON.parse(data) as unknown;
  } catch (cause) {
    throw protocolError('模型网关返回了非法 JSON 事件', cause);
  }
  if (parsed == null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw protocolError('模型网关事件必须是 JSON 对象');
  }
  const event = parsed as Record<string, unknown>;
  switch (event.type) {
    case 'block-start':
      assertBlockStartEvent(event);
      return parsed as ModelStreamEvent;
    case 'block-delta':
      assertBlockDeltaEvent(event);
      return parsed as ModelStreamEvent;
    case 'block-stop':
      assertExactKeys(event, ['type', 'index'], 'block-stop');
      assertBlockIndex(event.index);
      return parsed as ModelStreamEvent;
    case 'message-stop':
      assertMessageStopEvent(event);
      return parsed as ModelStreamEvent;
    case 'error':
      assertErrorFrame(event);
      return parsed as ModelErrorFrame;
    default:
      throw protocolError(`模型网关返回未知事件: ${String(event.type)}`);
  }
}

/** 校验 block-start 的索引、顶层 key 集合和分块身份字段。 */
function assertBlockStartEvent(event: Record<string, unknown>): void {
  assertExactKeys(event, ['type', 'index', 'block'], 'block-start');
  assertBlockIndex(event.index);
  if (!isObject(event.block) || typeof event.block.type !== 'string') {
    throw protocolError('block-start 缺少合法 block');
  }
  if (event.block.type === 'text' || event.block.type === 'reasoning') {
    assertExactKeys(event.block, ['type'], 'block-start.block');
    return;
  }
  if (event.block.type === 'tool-call') {
    assertExactKeys(event.block, ['type', 'callId', 'name'], 'block-start.block');
    if (typeof event.block.callId === 'string' && typeof event.block.name === 'string') {
      return;
    }
  }
  throw protocolError(`block-start 类型或字段非法: ${String(event.block.type)}`);
}

/** 校验 block-delta 的索引、顶层 key 集合和增量载荷。 */
function assertBlockDeltaEvent(event: Record<string, unknown>): void {
  assertExactKeys(event, ['type', 'index', 'delta'], 'block-delta');
  assertBlockIndex(event.index);
  if (!isObject(event.delta) || typeof event.delta.type !== 'string') {
    throw protocolError('block-delta 缺少合法 delta');
  }
  if (event.delta.type === 'text' || event.delta.type === 'reasoning') {
    assertExactKeys(event.delta, ['type', 'text'], 'block-delta.delta');
    if (typeof event.delta.text === 'string') {
      return;
    }
  }
  if (event.delta.type === 'tool-call') {
    assertExactKeys(event.delta, ['type', 'argumentsDelta'], 'block-delta.delta');
    if (typeof event.delta.argumentsDelta === 'string') {
      return;
    }
  }
  throw protocolError(`block-delta 类型或字段非法: ${String(event.delta.type)}`);
}

/** 校验 message-stop 的顶层 key 集合、停止原因、用量与 Provider 状态信封。 */
function assertMessageStopEvent(event: Record<string, unknown>): void {
  assertExactKeys(event, ['type', 'stopReason', 'usage', 'modelState'], 'message-stop');
  if (!isStopReason(event.stopReason)) {
    throw protocolError(`message-stop 停止原因非法: ${String(event.stopReason)}`);
  }
  if (event.usage !== null && !isUsage(event.usage)) {
    throw protocolError('message-stop usage 必须是 null 或合法 token 统计');
  }
  if (event.modelState !== null && !isModelState(event.modelState)) {
    throw protocolError('message-stop modelState 格式非法');
  }
}

/** 校验流内错误帧的精确 key 集合，确保 Controller 总能依赖稳定错误字段。 */
function assertErrorFrame(event: Record<string, unknown>): void {
  assertExactKeys(event, ['type', 'error'], 'error');
  if (!isObject(event.error)) {
    throw protocolError('模型网关 error 事件字段非法');
  }
  assertExactKeys(event.error, ['code', 'message', 'retryable'], 'error.error');
  if (typeof event.error.code !== 'string'
    || typeof event.error.message !== 'string'
    || typeof event.error.retryable !== 'boolean') {
    throw protocolError('模型网关 error 事件字段非法');
  }
}

/** 内容块 index 必须是非负整数。 */
function assertBlockIndex(index: unknown): void {
  if (typeof index !== 'number' || !Number.isInteger(index) || index < 0) {
    throw protocolError(`模型内容块 index 非法: ${String(index)}`);
  }
}

/** 判断框架允许的稳定停止原因。 */
function isStopReason(value: unknown): value is ModelStopReason {
  return value === 'end-turn'
    || value === 'tool-use'
    || value === 'max-tokens'
    || value === 'stop-sequence'
    || value === 'other';
}

/** 判断 token 使用量是否为安全非负整数，且不含任何额外统计字段。 */
function isUsage(value: unknown): value is ModelUsage {
  return isObject(value)
    && hasExactKeys(value, ['inputTokens', 'outputTokens', 'totalTokens'])
    && isNonNegativeInteger(value.inputTokens)
    && isNonNegativeInteger(value.outputTokens)
    && isNonNegativeInteger(value.totalTokens);
}

/** 判断 Provider State 是否含明确 format 和 data 字段，且无其他 vendor 字段。 */
function isModelState(value: unknown): value is ModelState {
  return isObject(value)
    && hasExactKeys(value, ['format', 'data'])
    && typeof value.format === 'string'
    && value.format.length > 0
    && Object.prototype.hasOwnProperty.call(value, 'data');
}

/**
 * 校验事件对象的 key 集合与协议定义完全一致。
 *
 * <p>网关协议是受控契约，多余字段几乎总是前后端版本不一致或厂商字段泄漏，
 * 必须以 MODEL_PROTOCOL_ERROR 显式失败，禁止静默忽略。
 */
function assertExactKeys(
  value: Record<string, unknown>,
  expected: readonly string[],
  label: string,
): void {
  if (!hasExactKeys(value, expected)) {
    throw protocolError(`${label} 含未知字段，期望精确字段集: ${expected.join(', ')}`);
  }
}

/** 判断对象 key 集合与期望集合是否完全一致（不多不少）。 */
function hasExactKeys(
  value: Record<string, unknown>,
  expected: readonly string[],
): boolean {
  const keys = Object.keys(value);
  if (keys.length !== expected.length) {
    return false;
  }
  return expected.every(key => Object.prototype.hasOwnProperty.call(value, key));
}

/** JSON 事件对象判定。 */
function isObject(value: unknown): value is Record<string, unknown> {
  return value != null && typeof value === 'object' && !Array.isArray(value);
}

/** JavaScript 边界只接受可无损持久化和计算的非负整数 token 数。 */
function isNonNegativeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}

/** 构造稳定协议错误；该错误不可通过重试修复。 */
function protocolError(message: string, cause?: unknown): AgentError & { cause?: unknown } {
  return {
    code: 'MODEL_PROTOCOL_ERROR',
    message,
    retryable: false,
    ...(cause === undefined ? {} : { cause }),
  };
}
