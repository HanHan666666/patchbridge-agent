/**
 * 上下文压缩 HTTP 端口：只传递厂商中立消息、压缩意图与 Provider 私有状态。
 *
 * <p>浏览器负责选择安全消息边界，服务端负责使用当前模型生成摘要，并由当前
 * Provider 投影私有 ModelState。两端职责分开后，Browser 不需要理解厂商状态格式。
 */
import { invalidStateError } from '../errors';
import type {
  AgentMessage,
  ContextCompactionConfiguration,
  ContextCompactionTrigger,
  ModelState,
} from '../types';
import type { ModelCallContext, ModelUsage } from './modelClient';
import {
  defaultHttpTransport,
  jsonInit,
  normalizeEndpoint,
  requestJson,
  type HttpTransport,
} from './http';

/** 生成上下文检查点所需的完整服务端请求。 */
export interface ContextCompactionRequest {
  /** 自动阈值或用户主动操作。 */
  readonly trigger: ContextCompactionTrigger;
  /** 固定保留的 system 消息与本次被淘汰的真实历史前缀。 */
  readonly messagesToSummarize: readonly AgentMessage[];
  /** 固定保留的 system 消息与本次保留的近期真实消息。 */
  readonly retainedMessages: readonly AgentMessage[];
  /** 重复压缩时合并的上一份摘要；首次压缩为 null。 */
  readonly previousSummary: string | null;
  /** 当前模型工作上下文对应的 Provider 私有状态。 */
  readonly modelState: ModelState | null;
  /** 摘要 Assistant 消息的预分配 ID。 */
  readonly responseMessageId: string;
  /** 保留边界位于 assistant 开始处时为 true，提醒摘要覆盖该回合前半段。 */
  readonly splitTurn: boolean;
}

/** 服务端成功生成摘要并投影 Provider 状态后的原子结果。 */
export interface ContextCompactionResult {
  /** 当前模型生成的模型专用摘要。 */
  readonly summary: string;
  /** 摘要调用的 Provider 用量；缺失属于协议错误。 */
  readonly usage: ModelUsage;
  /** 只对应 retainedMessages 的 Provider 私有状态。 */
  readonly modelState: ModelState | null;
}

/** ContextManager 依赖的服务端压缩端口。 */
export interface ContextCompactionGateway {
  /** 读取服务端根据模型配置派生的唯一窗口参数。 */
  configuration(signal?: AbortSignal): Promise<ContextCompactionConfiguration>;
  /** 使用当前模型生成摘要；取消和失败不得返回部分结果。 */
  compact(
    request: ContextCompactionRequest,
    context: ModelCallContext,
    signal: AbortSignal,
  ): Promise<ContextCompactionResult>;
}

/** 同源部署的上下文压缩 HTTP Adapter。 */
export class HttpContextCompactionGateway implements ContextCompactionGateway {
  /** 规范化后的 Agent API 根路径。 */
  private readonly endpoint: string;
  /** 与模型、会话 API 共享的宿主传输。 */
  private readonly transport: HttpTransport;

  /** 创建上下文压缩 Adapter。 */
  constructor(endpoint: string, transport: HttpTransport = defaultHttpTransport) {
    this.endpoint = normalizeEndpoint(endpoint);
    this.transport = transport;
  }

  /** 读取并严格校验服务端模型窗口配置。 */
  async configuration(signal?: AbortSignal): Promise<ContextCompactionConfiguration> {
    const value = await requestJson<unknown>(
      `${this.endpoint}/model/config`,
      { method: 'GET', signal },
      this.transport,
    );
    return requireConfiguration(value);
  }

  /** 发起一次不可流式的原子压缩调用。 */
  async compact(
    request: ContextCompactionRequest,
    context: ModelCallContext,
    signal: AbortSignal,
  ): Promise<ContextCompactionResult> {
    const value = await requestJson<unknown>(
      `${this.endpoint}/model/compact`,
      jsonInit('POST', { ...context, request }, signal),
      this.transport,
    );
    return requireCompactionResult(value);
  }
}

/** 校验模型窗口配置的精确字段与跨字段约束。 */
function requireConfiguration(value: unknown): ContextCompactionConfiguration {
  const record = requireRecord(value, '模型配置');
  requireExactKeys(record, [
    'contextWindowTokens',
    'automaticThresholdTokens',
    'keepRecentTokens',
  ], '模型配置');
  const contextWindowTokens = requirePositiveInteger(
    record.contextWindowTokens,
    'contextWindowTokens',
  );
  const automaticThresholdTokens = requirePositiveInteger(
    record.automaticThresholdTokens,
    'automaticThresholdTokens',
  );
  const keepRecentTokens = requirePositiveInteger(
    record.keepRecentTokens,
    'keepRecentTokens',
  );
  if (automaticThresholdTokens >= contextWindowTokens) {
    throw invalidStateError('automaticThresholdTokens 必须小于 contextWindowTokens');
  }
  if (keepRecentTokens >= automaticThresholdTokens) {
    throw invalidStateError('keepRecentTokens 必须小于 automaticThresholdTokens');
  }
  return Object.freeze({
    contextWindowTokens,
    automaticThresholdTokens,
    keepRecentTokens,
  });
}

/** 校验压缩结果，防止部分或猜测性状态进入 ConversationContext。 */
function requireCompactionResult(value: unknown): ContextCompactionResult {
  const record = requireRecord(value, '上下文压缩结果');
  requireExactKeys(record, ['summary', 'usage', 'modelState'], '上下文压缩结果');
  if (typeof record.summary !== 'string' || record.summary.trim().length === 0) {
    throw invalidStateError('上下文压缩结果 summary 必须是非空字符串');
  }
  const usageRecord = requireRecord(record.usage, '上下文压缩结果 usage');
  requireExactKeys(
    usageRecord,
    ['inputTokens', 'outputTokens', 'totalTokens'],
    '上下文压缩结果 usage',
  );
  const usage: ModelUsage = Object.freeze({
    inputTokens: requireNonNegativeInteger(usageRecord.inputTokens, 'usage.inputTokens'),
    outputTokens: requireNonNegativeInteger(usageRecord.outputTokens, 'usage.outputTokens'),
    totalTokens: requireNonNegativeInteger(usageRecord.totalTokens, 'usage.totalTokens'),
  });
  if (record.modelState !== null) {
    const state = requireRecord(record.modelState, '上下文压缩结果 modelState');
    requireExactKeys(state, ['format', 'data'], '上下文压缩结果 modelState');
    if (typeof state.format !== 'string' || state.format.trim().length === 0) {
      throw invalidStateError('上下文压缩结果 modelState.format 必须是非空字符串');
    }
  }
  return Object.freeze({
    summary: record.summary,
    usage,
    modelState: record.modelState as ModelState | null,
  });
}

/** 要求不可信 HTTP 值为普通对象。 */
function requireRecord(value: unknown, field: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw invalidStateError(`${field} 必须是对象`);
  }
  return value as Record<string, unknown>;
}

/** 要求对象只包含当前协议声明的字段。 */
function requireExactKeys(
  value: Record<string, unknown>,
  expected: readonly string[],
  field: string,
): void {
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  if (actual.length !== wanted.length || actual.some((key, index) => key !== wanted[index])) {
    throw invalidStateError(`${field} 字段必须精确为 ${wanted.join('、')}`);
  }
}

/** 要求配置数字为安全正整数。 */
function requirePositiveInteger(value: unknown, field: string): number {
  const parsed = requireNonNegativeInteger(value, field);
  if (parsed === 0) {
    throw invalidStateError(`${field} 必须大于 0`);
  }
  return parsed;
}

/** 要求 token 数为安全非负整数。 */
function requireNonNegativeInteger(value: unknown, field: string): number {
  if (typeof value !== 'number'
    || !Number.isSafeInteger(value)
    || value < 0) {
    throw invalidStateError(`${field} 必须是安全非负整数`);
  }
  return value;
}
