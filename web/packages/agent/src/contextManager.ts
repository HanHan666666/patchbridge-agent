/**
 * 模型工作上下文的唯一业务入口。
 *
 * <p>该模块集中负责窗口计量、安全切分、摘要检查点投影与 Provider 用量校验。
 * Runtime、Controller 和 View 不各自复制阈值或消息选择逻辑，从而保证自动压缩、
 * 手动压缩和恢复后的下一次模型调用使用完全相同的上下文语义。
 */
import type {
  AgentError,
  AgentMessage,
  ContentBlock,
  ContextCompactionConfiguration,
  ContextCompactionTrigger,
  ContextWindowState,
  ConversationContext,
  ModelContext,
  ModelState,
} from './types';
import type {
  ContextCompactionGateway,
  ContextCompactionRequest,
} from './clients/contextCompactionClient';
import type {
  ModelCallContext,
  ModelToolDefinition,
  ModelUsage,
} from './clients/modelClient';
import { invalidStateError } from './errors';
import {
  snapshotAgentMessage,
  snapshotModelState,
} from './messageValues';

/** 尚未生成任何模型响应的新会话使用的明确空工作上下文。 */
export const EMPTY_MODEL_CONTEXT: ModelContext = Object.freeze({
  checkpoint: null,
  firstRetainedMessageId: null,
  modelState: null,
  usage: null,
});

/** ContextManager 创建稳定 ID 和时间戳所需的可测试依赖。 */
export interface ContextManagerOptions {
  /** 检查点与摘要响应 ID 生成器。 */
  readonly createId?: (kind: 'checkpoint' | 'message') => string;
  /** 返回当前时间；默认使用 Date.now。 */
  readonly now?: () => number;
}

/** 自动压缩前准备模型输入所需的参数。 */
export interface PrepareModelContextInput {
  /** 始终完整保留的当前会话上下文。 */
  readonly conversation: ConversationContext;
  /** 本轮真正发送给 Provider 的 Tool 定义；输入预算必须覆盖它们的规模。 */
  readonly tools: readonly ModelToolDefinition[];
  /** 模型调用的链路归属。 */
  readonly callContext: ModelCallContext;
  /** 与本次 Agent Execution 绑定的取消信号。 */
  readonly signal: AbortSignal;
}

/** 自动准备结果同时返回完整上下文与真正发送给模型的工作消息。 */
export interface PreparedModelContext {
  /** 只有成功压缩时才变化的完整会话上下文。 */
  readonly conversation: ConversationContext;
  /** system、摘要检查点与近期真实消息组成的模型输入。 */
  readonly modelMessages: readonly AgentMessage[];
}

/** 浏览器模型上下文管理端口。 */
export interface ContextManager {
  /** 读取并缓存当前服务端模型窗口配置。 */
  loadConfiguration(signal?: AbortSignal): Promise<ContextCompactionConfiguration>;
  /** 返回已经成功加载的配置；初始化前调用必须失败。 */
  getConfiguration(): ContextCompactionConfiguration;
  /** 在每次模型调用前执行阈值检查，并在需要时完成自动压缩。 */
  prepareForModelCall(input: PrepareModelContextInput): Promise<PreparedModelContext>;
  /** 返回当前模型调用是否必须先自动压缩，供 Runtime 发布准确阶段。 */
  requiresAutomaticCompaction(conversation: ConversationContext): boolean;
  /** 用户主动生成新的上下文检查点。 */
  compact(
    conversation: ConversationContext,
    trigger: ContextCompactionTrigger,
    callContext: ModelCallContext,
    signal: AbortSignal,
  ): Promise<ConversationContext>;
  /** 把模型工作上下文转换成真正发送给 Provider 的消息。 */
  buildModelMessages(conversation: ConversationContext): readonly AgentMessage[];
  /** 用 Provider 的必需用量和下一份私有状态推进模型工作上下文。 */
  recordModelResponse(
    conversation: ConversationContext,
    responseMessage: AgentMessage,
    usage: ModelUsage | null,
    modelState: ModelState | null,
  ): ConversationContext;
}

/** 默认 ContextManager：不持有会话，只缓存不可变服务端配置。 */
export class DefaultContextManager implements ContextManager {
  /** 当前页面已经验证成功的窗口配置。 */
  private configuration: ContextCompactionConfiguration | null = null;
  /** 稳定业务 ID 生成器。 */
  private readonly createId: (kind: 'checkpoint' | 'message') => string;
  /** 可测试时钟。 */
  private readonly now: () => number;

  /** 创建默认上下文管理器。 */
  constructor(
    private readonly gateway: ContextCompactionGateway,
    options: ContextManagerOptions = {},
  ) {
    let sequence = 0;
    this.createId = options.createId ?? (kind => {
      sequence += 1;
      return `context-${kind}-${Date.now().toString(36)}-${sequence.toString(36)}`;
    });
    this.now = options.now ?? Date.now;
  }

  /** 首次读取服务端配置，后续调用共享同一模型配置快照。 */
  async loadConfiguration(signal?: AbortSignal): Promise<ContextCompactionConfiguration> {
    if (this.configuration == null) {
      this.configuration = await this.gateway.configuration(signal);
    }
    return this.configuration;
  }

  /** 返回初始化阶段取得的配置，禁止使用 Browser 默认值掩盖服务端遗漏。 */
  getConfiguration(): ContextCompactionConfiguration {
    if (this.configuration == null) {
      throw invalidStateError('上下文压缩配置尚未加载');
    }
    return this.configuration;
  }

  /**
   * 每次模型调用的统一输入准备边界：必要时先压缩，再执行最终窗口预算检查。
   *
   * <p>预算覆盖工作消息（含 system 指令与摘要检查点）、本轮 Tool 定义与输出预留。
   * 一次压缩完成不能证明下一次请求装得下，因此检查在压缩完成后对最终出站输入
   * 执行；超限明确失败并保留完整历史，由用户调整输入后重新发起，
   * 绝不自动重复压缩、静默删除历史或更换模型。
   */
  async prepareForModelCall(input: PrepareModelContextInput): Promise<PreparedModelContext> {
    const conversation = this.requiresAutomaticCompaction(input.conversation)
      ? await this.compact(
        input.conversation,
        'automatic',
        input.callContext,
        input.signal,
      )
      : input.conversation;
    const modelMessages = this.buildModelMessages(conversation);
    this.assertWithinInputBudget(conversation, modelMessages, input.tools);
    return Object.freeze({
      conversation,
      modelMessages,
    });
  }

  /** 首次调用尚无 usage 时直接调用模型；随后只依据必需 Provider 基线判断阈值。 */
  requiresAutomaticCompaction(conversation: ConversationContext): boolean {
    if (conversation.modelContext.usage == null) {
      return false;
    }
    return measureCurrentTokens(conversation)
      >= this.getConfiguration().automaticThresholdTokens;
  }

  /**
   * 最终输入预算检查：工作消息 + 本轮 Tool 定义 + 输出预留必须装进模型窗口。
   *
   * <p>计量优先使用 Provider 真实 usage 基线（加基线后新消息的保守估算），
   * 避免用 UTF-8 字节上界重复计量已经精确计量过的历史；首次调用尚无 usage 时
   * 才对完整出站输入做估算。超限抛出 CONTEXT_WINDOW_EXCEEDED，由 Runtime 终止
   * 本轮，完整历史保留。
   */
  private assertWithinInputBudget(
    conversation: ConversationContext,
    modelMessages: readonly AgentMessage[],
    tools: readonly ModelToolDefinition[],
  ): void {
    const configuration = this.getConfiguration();
    const budgetTokens = this.inputBudgetTokens();
    const estimatedTokens = conversation.modelContext.usage == null
      ? estimateModelMessages(modelMessages) + estimateToolDefinitions(tools)
      : measureCurrentTokens(conversation) + estimateToolDefinitions(tools);
    if (estimatedTokens <= budgetTokens) {
      return;
    }
    throw contextWindowExceededError({
      estimatedTokens,
      budgetTokens,
      contextWindowTokens: configuration.contextWindowTokens,
      reservedOutputTokens: configuration.reservedOutputTokens,
      detail: '模型输入（工作消息、system 指令与本轮 Tool 定义）超过窗口预算',
    });
  }

  /** 输入预算 = 窗口 − 输出预留；派生只来自服务端配置，不在此二次推导。 */
  private inputBudgetTokens(): number {
    const configuration = this.getConfiguration();
    return configuration.contextWindowTokens - configuration.reservedOutputTokens;
  }

  /**
   * 先在本地生成安全计划，再原子接受服务端结果。
   *
   * <p>方法在 Gateway 完整成功前不会修改传入对象；任何 HTTP、Provider、用量或
   * 投影错误都会直接抛出，调用方因此可以继续持有原模型上下文。
   */
  async compact(
    conversation: ConversationContext,
    trigger: ContextCompactionTrigger,
    callContext: ModelCallContext,
    signal: AbortSignal,
  ): Promise<ConversationContext> {
    const tokensBefore = measureCurrentTokens(conversation);
    const plan = createCompactionPlan(
      conversation,
      this.getConfiguration().keepRecentTokens,
    );
    const checkpointId = this.createId('checkpoint');
    const request: ContextCompactionRequest = Object.freeze({
      trigger,
      messagesToSummarize: plan.messagesToSummarize,
      retainedMessages: plan.retainedMessages,
      previousSummary: conversation.modelContext.checkpoint?.summary ?? null,
      modelState: conversation.modelContext.modelState,
      responseMessageId: this.createId('message'),
      splitTurn: plan.splitTurn,
    });
    const result = await this.gateway.compact(request, callContext, signal);
    const compactionCount = (conversation.modelContext.checkpoint?.compactionCount ?? 0) + 1;
    const estimatedTokensAfter = estimateModelMessages([
      ...plan.retainedSystemMessages,
      createCheckpointMessage(checkpointId, result.summary),
      ...plan.retainedTailMessages,
    ]);
    const lastMessage = plan.retainedTailMessages[plan.retainedTailMessages.length - 1]
      ?? plan.retainedSystemMessages[plan.retainedSystemMessages.length - 1]
      ?? null;
    // 压缩成功不等于结果可用：保留段超过近期预算（如过大的最新安全段）时，
    // 压缩后的工作上下文仍可能装不下窗口。此时必须拒绝该检查点，
    // 调用方继续持有原模型上下文，完整历史不受影响。
    if (estimatedTokensAfter > this.inputBudgetTokens()) {
      throw contextWindowExceededError({
        estimatedTokens: estimatedTokensAfter,
        budgetTokens: this.inputBudgetTokens(),
        contextWindowTokens: this.getConfiguration().contextWindowTokens,
        reservedOutputTokens: this.getConfiguration().reservedOutputTokens,
        detail: '压缩后的工作上下文仍超过模型窗口预算（近期消息过大或摘要过长）',
      });
    }
    const modelContext: ModelContext = Object.freeze({
      checkpoint: Object.freeze({
        id: checkpointId,
        summary: result.summary,
        trigger,
        compactedAt: new Date(this.now()).toISOString(),
        tokensBefore,
        estimatedTokensAfter,
        compactionCount,
      }),
      firstRetainedMessageId: plan.retainedTailMessages[0]?.id ?? null,
      modelState: snapshotModelState(result.modelState),
      usage: Object.freeze({
        totalTokens: estimatedTokensAfter,
        source: 'estimated' as const,
        measuredThroughMessageId: lastMessage?.id ?? null,
      }),
    });
    return Object.freeze({
      messages: conversation.messages,
      modelContext,
    });
  }

  /** 把检查点放在固定 system 消息之后，并只追加边界后的真实近期消息。 */
  buildModelMessages(conversation: ConversationContext): readonly AgentMessage[] {
    const messages = conversation.messages;
    const checkpoint = conversation.modelContext.checkpoint;
    if (checkpoint == null) {
      return Object.freeze(messages.map(snapshotMessage));
    }
    const retainedIndex = requireRetainedIndex(
      messages,
      conversation.modelContext.firstRetainedMessageId,
    );
    const systemMessages = messages.filter(message => message.role === 'system');
    const tail = messages
      .slice(retainedIndex)
      .filter(message => message.role !== 'system');
    return Object.freeze([
      ...systemMessages.map(snapshotMessage),
      createCheckpointMessage(checkpoint.id, checkpoint.summary),
      ...tail.map(snapshotMessage),
    ]);
  }

  /** Provider 用量缺失时明确失败；估算绝不替代一次正常模型响应的计量。 */
  recordModelResponse(
    conversation: ConversationContext,
    responseMessage: AgentMessage,
    usage: ModelUsage | null,
    modelState: ModelState | null,
  ): ConversationContext {
    if (usage == null) {
      throw invalidStateError('模型 Provider 未返回必需的 token usage，无法管理上下文窗口');
    }
    if (!conversation.messages.some(message => message.id === responseMessage.id)) {
      throw invalidStateError('模型用量只能关联已经提交到完整聊天历史的响应消息');
    }
    return Object.freeze({
      messages: conversation.messages,
      modelContext: Object.freeze({
        ...conversation.modelContext,
        modelState: snapshotModelState(modelState),
        usage: Object.freeze({
          totalTokens: usage.totalTokens,
          source: 'provider' as const,
          measuredThroughMessageId: responseMessage.id,
        }),
      }),
    });
  }
}

/** 由 reducer 与 View 共享的纯状态投影，不发起压缩副作用。 */
export function inspectContextWindow(
  conversation: ConversationContext,
  configuration: ContextCompactionConfiguration | null,
): ContextWindowState {
  const usage = conversation.modelContext.usage;
  const currentTokens = usage == null ? null : measureCurrentTokens(conversation);
  return Object.freeze({
    currentTokens,
    source: usage?.source ?? null,
    percentage: currentTokens == null || configuration == null
      ? null
      : Math.min(1, currentTokens / configuration.contextWindowTokens),
    compactable: configuration != null && usage != null
      && canCreateCompactionPlan(conversation, configuration.keepRecentTokens),
  });
}

/** 当前 Provider 基线加上基线之后新消息的保守估算。 */
function measureCurrentTokens(conversation: ConversationContext): number {
  const usage = conversation.modelContext.usage;
  if (usage == null) {
    throw invalidStateError('尚未取得模型 Provider token usage，不能执行上下文压缩');
  }
  if (usage.measuredThroughMessageId == null) {
    return usage.totalTokens;
  }
  const measuredIndex = conversation.messages.findIndex(
    message => message.id === usage.measuredThroughMessageId,
  );
  if (measuredIndex < 0) {
    throw invalidStateError('模型上下文用量引用了不存在的消息');
  }
  return usage.totalTokens + estimateModelMessages(
    conversation.messages.slice(measuredIndex + 1),
  );
}

/** 本地压缩计划；system 消息永远逐字保留，不属于可淘汰预算。 */
interface CompactionPlan {
  readonly messagesToSummarize: readonly AgentMessage[];
  readonly retainedMessages: readonly AgentMessage[];
  readonly retainedSystemMessages: readonly AgentMessage[];
  readonly retainedTailMessages: readonly AgentMessage[];
  readonly splitTurn: boolean;
}

/** 模型输入超限的明确上下文，供错误消息与测试断言使用。 */
interface WindowBudgetExceededInput {
  readonly estimatedTokens: number;
  readonly budgetTokens: number;
  readonly contextWindowTokens: number;
  readonly reservedOutputTokens: number;
  readonly detail: string;
}

/**
 * 构造最终窗口预算检查失败的稳定错误。
 *
 * <p>该失败按已确认的契约终止本次模型调用：不自动重复压缩、不静默删除历史、
 * 不更换模型；完整历史保留，用户调整输入后重新发起。
 */
function contextWindowExceededError(input: WindowBudgetExceededInput): AgentError {
  return {
    code: 'CONTEXT_WINDOW_EXCEEDED',
    message: `${input.detail}：估算 ${input.estimatedTokens} tokens，`
      + `可用预算 ${input.budgetTokens}`
      + `（窗口 ${input.contextWindowTokens} − 输出预留 ${input.reservedOutputTokens}）。`
      + '完整历史已保留，请缩短输入或开始新会话后重试',
    retryable: false,
  };
}

/** 估算本轮 Tool 目录占用：名称、描述与 Schema 按真实序列化字节计入上界。 */
function estimateToolDefinitions(tools: readonly ModelToolDefinition[]): number {
  let bytes = 0;
  for (const tool of tools) {
    bytes += 16
      + utf8Length(tool.name)
      + utf8Length(tool.description)
      + utf8Length(JSON.stringify(tool.inputSchema));
  }
  return bytes;
}

/** 按“非 tool 起始消息 + 连续 tool 结果”分段，确保 Tool Call/Result 不被切开。 */
function createCompactionPlan(
  conversation: ConversationContext,
  keepRecentTokens: number,
): CompactionPlan {
  const active = activeRealMessages(conversation);
  const systemMessages = active.filter(message => message.role === 'system');
  const nonSystem = active.filter(message => message.role !== 'system');
  const segments: AgentMessage[][] = [];
  for (const message of nonSystem) {
    if (message.role === 'tool') {
      const owner = segments[segments.length - 1];
      if (owner == null || owner[0]?.role !== 'assistant') {
        throw invalidStateError('Tool Result 缺少同一上下文中的 Assistant Tool Call');
      }
      owner.push(message);
      continue;
    }
    segments.push([message]);
  }
  let retainedSegmentIndex = segments.length;
  let retainedTokens = 0;
  for (let index = segments.length - 1; index >= 0; index -= 1) {
    const segmentTokens = estimateModelMessages(segments[index] ?? []);
    if (retainedSegmentIndex < segments.length
      && retainedTokens + segmentTokens > keepRecentTokens) {
      break;
    }
    retainedTokens += segmentTokens;
    retainedSegmentIndex = index;
  }
  if (retainedSegmentIndex <= 0 || retainedSegmentIndex >= segments.length) {
    throw invalidStateError('当前模型上下文没有可安全压缩的历史前缀');
  }
  const summarized = segments.slice(0, retainedSegmentIndex).flat();
  const retainedTail = segments.slice(retainedSegmentIndex).flat();
  const firstRetained = retainedTail[0];
  if (firstRetained == null) {
    throw invalidStateError('上下文压缩必须保留至少一个近期消息段');
  }
  return Object.freeze({
    messagesToSummarize: Object.freeze([
      ...systemMessages.map(snapshotMessage),
      ...summarized.map(snapshotMessage),
    ]),
    retainedMessages: Object.freeze([
      ...systemMessages.map(snapshotMessage),
      ...retainedTail.map(snapshotMessage),
    ]),
    retainedSystemMessages: Object.freeze(systemMessages.map(snapshotMessage)),
    retainedTailMessages: Object.freeze(retainedTail.map(snapshotMessage)),
    splitTurn: firstRetained.role === 'assistant',
  });
}

/** 检查是否存在计划；只吞掉“无安全前缀”这一纯展示判断，不参与实际压缩。 */
function canCreateCompactionPlan(
  conversation: ConversationContext,
  keepRecentTokens: number,
): boolean {
  try {
    createCompactionPlan(conversation, keepRecentTokens);
    return true;
  } catch {
    return false;
  }
}

/** 返回当前检查点之后的真实工作消息，排除完整历史中已被摘要覆盖的前缀。 */
function activeRealMessages(conversation: ConversationContext): readonly AgentMessage[] {
  if (conversation.modelContext.checkpoint == null) {
    return conversation.messages;
  }
  const retainedIndex = requireRetainedIndex(
    conversation.messages,
    conversation.modelContext.firstRetainedMessageId,
  );
  return Object.freeze([
    ...conversation.messages.filter(message => message.role === 'system'),
    ...conversation.messages
      .slice(retainedIndex)
      .filter(message => message.role !== 'system'),
  ]);
}

/** 压缩边界必须引用完整历史中的非 system 消息。 */
function requireRetainedIndex(
  messages: readonly AgentMessage[],
  retainedId: string | null,
): number {
  if (retainedId == null) {
    throw invalidStateError('已压缩模型上下文缺少 firstRetainedMessageId');
  }
  const index = messages.findIndex(message => message.id === retainedId);
  if (index < 0 || messages[index]?.role === 'system') {
    throw invalidStateError('firstRetainedMessageId 必须引用完整历史中的非 system 消息');
  }
  return index;
}

/** 生成只存在于模型输入中的摘要消息，避免污染用户可见聊天历史。 */
function createCheckpointMessage(id: string, summary: string): AgentMessage {
  return Object.freeze({
    id: `context-summary-${id}`,
    role: 'user' as const,
    blocks: Object.freeze([Object.freeze({
      type: 'text' as const,
      text: `<context-checkpoint>\n${summary}\n</context-checkpoint>`,
    })]),
  });
}

/**
 * 使用 UTF-8 字节上界做消息选择估算。
 *
 * <p>缺少与当前模型严格匹配的 Browser tokenizer 时，一个 UTF-8 字节按一个 token
 * 计入上界。该策略会有意高估普通英文和图片数据，但不会像 chars/4 或 bytes/3
 * 那样低估随机 ASCII、代码和其他低压缩率内容。估算只用于压缩后过渡快照和安全
 * 边界选择，绝不替代 Provider 正常调用 usage。
 */
function estimateModelMessages(messages: readonly AgentMessage[]): number {
  let bytes = 0;
  for (const message of messages) {
    bytes += 32 + utf8Length(message.role) + utf8Length(message.id);
    for (const block of message.blocks) {
      bytes += 16 + estimateBlockBytes(block);
    }
  }
  return bytes;
}

/** 按内容块真实序列化字段估算字节，图片内联数据不会被低估为短占位符。 */
function estimateBlockBytes(block: ContentBlock): number {
  switch (block.type) {
    case 'text':
    case 'reasoning':
      return utf8Length(block.text);
    case 'image':
      return block.source.type === 'url'
        ? utf8Length(block.source.url)
        : utf8Length(block.source.mediaType) + utf8Length(block.source.data);
    case 'tool-call':
      return utf8Length(block.callId)
        + utf8Length(block.name)
        + utf8Length(JSON.stringify(block.input));
    case 'tool-result':
      return utf8Length(block.callId)
        + utf8Length(block.name)
        + block.content.reduce((sum, content) => sum + utf8Length(content.text), 0);
    default:
      return assertNever(block);
  }
}

/** TextEncoder 是 Browser 标准能力，可精确计算 UTF-8 字节数。 */
function utf8Length(value: string): number {
  return new TextEncoder().encode(value).byteLength;
}

/** 隔离消息数组与 Blocks，防止异步 Gateway 观察到后续修改。 */
const snapshotMessage = snapshotAgentMessage;

/** 联合类型穷尽校验。 */
function assertNever(value: never): never {
  throw new Error(`未支持的上下文内容块: ${String(value)}`);
}
