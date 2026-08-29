/**
 * 结构化模型流到稳定 AgentMessage 的唯一聚合器。
 *
 * <p>本文件隔离“流式临时态”和“可持久化稳定态”：Tool 参数增量在这里以字符串
 * 暂存，只有收到 block-stop、完成 JSON 对象校验后才生成 ToolCallBlock。
 * 聚合器还在稳定消息形成前统一执行输出字符预算、Tool Call ID 唯一性以及
 * stopReason/Tool Call 数量决策，Runtime 因此不会调度半截或协议自相矛盾的模型输出。
 */
import type {
  ModelBlockDelta,
  ModelBlockStart,
  ModelStopReason,
  ModelStreamEvent,
  ModelUsage,
} from './clients/modelClient';
import type {
  AgentError,
  AgentMessage,
  ContentBlock,
  JsonObject,
  ModelState,
} from './types';
import { copyAndFreezeJsonObject, copyAndFreezeJsonValue } from './jsonValues';

/** 聚合器完成后交给 Agent Loop 的一次模型结果。 */
export interface AssembledModelMessage {
  /** 已完成且可持久化的 Assistant 消息。 */
  readonly message: AgentMessage;
  /** Provider 返回的下一份模型续接状态。 */
  readonly modelState: ModelState | null;
  /** Provider 标准化的停止原因。 */
  readonly stopReason: ModelStopReason;
  /** Provider 用量；null 只保留到 ContextManager 执行必需契约校验。 */
  readonly usage: ModelUsage | null;
}

/** 聚合中的文本或展示思考块。 */
interface TextLikeAggregation {
  /** 聚合块类型。 */
  readonly type: 'text' | 'reasoning';
  /** 到达顺序拼接后的文本。 */
  text: string;
  /** block-stop 到达后禁止继续追加。 */
  stopped: boolean;
}

/** 聚合中的 Tool Call；arguments 只在此处允许为字符串。 */
interface ToolCallAggregation {
  /** Tool Call 聚合类型。 */
  readonly type: 'tool-call';
  /** 模型调用标识。 */
  readonly callId: string;
  /** 本轮 Tool 快照中的目标名称。 */
  readonly name: string;
  /** 尚未完成 JSON 校验的参数文本。 */
  argumentsText: string;
  /** block-stop 到达后禁止继续追加。 */
  stopped: boolean;
}

/** Runtime 内部允许存在的聚合块联合类型。 */
type BlockAggregation = TextLikeAggregation | ToolCallAggregation;

/** message-stop 携带的稳定尾部元数据。 */
interface MessageStopAggregation {
  /** 模型停止原因。 */
  readonly stopReason: ModelStopReason;
  /** Provider token 用量；缺失值会在稳定上下文提交前失败。 */
  readonly usage: ModelUsage | null;
  /** 下一份 Provider ModelState。 */
  readonly modelState: ModelState | null;
}

/** 严格结构化流聚合器；未知顺序和不完整 Block 都按协议错误失败。 */
export class ModelMessageAssembler {
  /** 单次模型调用允许聚合的最大字符数，防止流式临时态无界占用浏览器内存。 */
  private readonly maxModelOutputCharacters: number;
  /** 已接受的正文、展示思考和 Tool 参数字符总数；按 JavaScript string.length 计数。 */
  private modelOutputCharacters = 0;
  /** 按 Provider index 保存聚合状态，最终按 index 排序恢复内容顺序。 */
  private readonly blocks = new Map<number, BlockAggregation>();
  /** 同一模型响应中已经声明的 Tool Call ID，重复 ID 会破坏结果关联。 */
  private readonly toolCallIds = new Set<string>();
  /** message-stop 后不允许再消费任何模型事件。 */
  private messageStop: MessageStopAggregation | null = null;

  /** 创建具有明确单次输出预算的聚合器；非法限额属于 Runtime 装配错误。 */
  constructor(maxModelOutputCharacters: number) {
    if (!Number.isFinite(maxModelOutputCharacters)
      || !Number.isInteger(maxModelOutputCharacters)
      || maxModelOutputCharacters <= 0) {
      throw new Error('maxModelOutputCharacters 必须是有限正整数');
    }
    this.maxModelOutputCharacters = maxModelOutputCharacters;
  }

  /** 消费一条 Provider 标准事件，并对事件顺序做即时校验。 */
  consume(event: ModelStreamEvent): void {
    if (this.messageStop != null) {
      throw modelProtocolError('message-stop 之后仍收到模型事件');
    }
    switch (event.type) {
      case 'block-start':
        this.startBlock(event.index, event.block);
        return;
      case 'block-delta':
        this.appendDelta(event.index, event.delta);
        return;
      case 'block-stop':
        this.stopBlock(event.index);
        return;
      case 'message-stop':
        this.completeMessage(event.stopReason, event.usage, event.modelState);
        return;
    }
  }

  /**
   * 生成稳定 Assistant 消息。
   *
   * <p>调用方预先生成 messageId，并已将同一 ID 交给 Provider 关联 ModelState。
   */
  assemble(messageId: string): AssembledModelMessage {
    if (this.messageStop == null) {
      throw modelProtocolError('模型流结束但缺少 message-stop');
    }
    this.assertStopReasonMatchesToolCalls(this.messageStop.stopReason);
    const content: ContentBlock[] = [];
    for (const [, block] of [...this.blocks.entries()].sort(byBlockIndex)) {
      if (!block.stopped) {
        throw modelProtocolError(`模型内容块未结束: ${block.type}`);
      }
      if (block.type === 'tool-call') {
        const input = parseToolInput(block.name, block.argumentsText);
        const toolCall = {
          type: 'tool-call',
          callId: block.callId,
          name: block.name,
          input,
        } as const;
        content.push(toolCall);
        continue;
      }
      if (block.text.length > 0) {
        content.push({ type: block.type, text: block.text });
      }
    }
    if (content.length === 0) {
      throw modelProtocolError('模型完成但没有产生任何内容块');
    }
    return {
      message: { id: messageId, role: 'assistant', blocks: content },
      modelState: this.messageStop.modelState,
      stopReason: this.messageStop.stopReason,
      usage: this.messageStop.usage,
    };
  }

  /** 注册一个新内容块；重复或负数 index 表示 Provider 协议错误。 */
  private startBlock(index: number, start: ModelBlockStart): void {
    assertBlockIndex(index);
    if (this.blocks.has(index)) {
      throw modelProtocolError(`模型内容块重复开始: ${index}`);
    }
    if (start.type === 'tool-call') {
      if (start.callId.length === 0 || start.name.length === 0) {
        throw modelProtocolError('Tool Call start 缺少 callId 或 name');
      }
      if (this.toolCallIds.has(start.callId)) {
        throw modelProtocolError(`同一模型响应重复使用 Tool Call ID: ${start.callId}`);
      }
      this.toolCallIds.add(start.callId);
      this.blocks.set(index, {
        type: 'tool-call',
        callId: start.callId,
        name: start.name,
        argumentsText: '',
        stopped: false,
      });
      return;
    }
    this.blocks.set(index, { type: start.type, text: '', stopped: false });
  }

  /** 把增量追加到已开始且类型一致的内容块。 */
  private appendDelta(index: number, delta: ModelBlockDelta): void {
    assertBlockIndex(index);
    const block = this.blocks.get(index);
    if (block == null) {
      throw modelProtocolError(`模型内容块尚未开始: ${index}`);
    }
    if (block.stopped) {
      throw modelProtocolError(`模型内容块结束后仍收到增量: ${index}`);
    }
    if (block.type !== delta.type) {
      throw modelProtocolError(
        `模型内容块增量类型不一致: ${block.type} != ${delta.type}`,
      );
    }
    if (block.type === 'tool-call' && delta.type === 'tool-call') {
      this.reserveOutputCharacters(delta.argumentsDelta.length);
      block.argumentsText += delta.argumentsDelta;
      return;
    }
    if (block.type !== 'tool-call' && delta.type !== 'tool-call') {
      this.reserveOutputCharacters(delta.text.length);
      block.text += delta.text;
      return;
    }
    throw modelProtocolError(`无法聚合模型内容块: ${index}`);
  }

  /** 标记内容块完成；未知或重复 stop 均明确失败。 */
  private stopBlock(index: number): void {
    assertBlockIndex(index);
    const block = this.blocks.get(index);
    if (block == null) {
      throw modelProtocolError(`结束了尚未开始的模型内容块: ${index}`);
    }
    if (block.stopped) {
      throw modelProtocolError(`模型内容块重复结束: ${index}`);
    }
    block.stopped = true;
  }

  /** 收敛 message-stop，并确保所有内容块已经完整结束。 */
  private completeMessage(
    stopReason: ModelStopReason,
    usage: ModelUsage | null,
    modelState: ModelState | null,
  ): void {
    for (const [index, block] of this.blocks) {
      if (!block.stopped) {
        throw modelProtocolError(`message-stop 前内容块尚未结束: ${index}`);
      }
    }
    this.messageStop = {
      stopReason,
      usage: usage == null ? null : Object.freeze({ ...usage }),
      modelState: modelState == null
        ? null
        : Object.freeze({
            format: modelState.format,
            data: copyAndFreezeJsonValue(modelState.data),
          }),
    };
  }

  /**
   * 在修改聚合字符串前预占字符预算，越界时不接受当前增量。
   *
   * <p>计数采用 JavaScript {@code string.length}，与 Runtime 后续保存和展示所持有的
   * 字符串大小口径一致；超限明确失败，不截断、不摘要也不重试。
   */
  private reserveOutputCharacters(deltaCharacters: number): void {
    if (deltaCharacters > this.maxModelOutputCharacters - this.modelOutputCharacters) {
      throw modelOutputLimitExceededError(this.maxModelOutputCharacters);
    }
    this.modelOutputCharacters += deltaCharacters;
  }

  /**
   * 执行完整 stopReason 决策表，禁止不一致的 Tool 输出进入稳定 Assistant 消息。
   *
   * <p>Tool 参数对象校验随后仍会逐个执行；这里先按聚合块数量拒绝截断 Tool 参数
   * 或自然停止却携带 Tool Call 的响应，保证任何一项失败时整批都不会交给 Runtime。
   */
  private assertStopReasonMatchesToolCalls(stopReason: ModelStopReason): void {
    const toolCallCount = [...this.blocks.values()].filter(
      block => block.type === 'tool-call',
    ).length;
    if (stopReason === 'tool-use') {
      if (toolCallCount === 0) {
        throw modelProtocolError('stopReason=tool-use 但模型响应没有 Tool Call');
      }
      return;
    }
    if (toolCallCount > 0) {
      throw modelProtocolError(
        `stopReason=${stopReason} 时模型响应不得包含 Tool Call`,
      );
    }
  }
}

/** 按模型提供的非负整数 index 恢复稳定内容顺序。 */
function byBlockIndex(
  left: readonly [number, BlockAggregation],
  right: readonly [number, BlockAggregation],
): number {
  return left[0] - right[0];
}

/** index 是流聚合主键，必须是非负整数。 */
function assertBlockIndex(index: number): void {
  if (!Number.isInteger(index) || index < 0) {
    throw modelProtocolError(`模型内容块 index 非法: ${index}`);
  }
}

/** Tool 参数进入稳定消息前必须是非数组 JSON 对象。 */
function parseToolInput(toolName: string, raw: string): JsonObject {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw) as unknown;
  } catch (cause) {
    throw modelProtocolError(`Tool ${toolName} 的参数不是合法 JSON`, cause);
  }
  if (parsed == null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw modelProtocolError(`Tool ${toolName} 的参数必须是 JSON 对象`);
  }
  return copyAndFreezeJsonObject(parsed as JsonObject);
}

/** 构造不可重试的模型协议错误。 */
function modelProtocolError(
  message: string,
  cause?: unknown,
): AgentError & { cause?: unknown } {
  return {
    code: 'MODEL_PROTOCOL_ERROR',
    message,
    retryable: false,
    ...(cause === undefined ? {} : { cause }),
  };
}

/** 构造单次模型聚合字符超限错误；资源边界错误不允许 Runtime 自动重试。 */
function modelOutputLimitExceededError(maxCharacters: number): AgentError {
  return {
    code: 'MODEL_OUTPUT_LIMIT_EXCEEDED',
    message: `单次模型输出字符数超过上限 ${maxCharacters}`,
    retryable: false,
  };
}
