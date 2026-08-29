/**
 * 调用轨迹（Call Trace）：浏览器端执行过程的可观测数据采集与本地保留。
 *
 * <p>为什么需要独立于会话存储：会话持久化只保存“模型上下文所需的稳定内容”
 * （完整消息与 ModelContext），耗时、token 用量、确认交互等执行元数据不属于模型
 * 上下文，按架构不写入服务端会话存储。CallTraceStore 以只读生命周期 Hook 的
 * 身份观察每次 Execution，把过程事实整理成按 traceId 组织的轨迹，并按会话
 * 保留在 localStorage——不新增后端表与端点，代价是轨迹只属于当前浏览器。
 *
 * <p>采集通道（两条，缺一不可）：
 * <ul>
 *   <li>过程事实来自 {@link AgentHook}：开始/完成时间戳、停止原因、usage、
 *       首 token/输出阶段计时、确认交互与失败边界，均由 Runtime 同步发布；</li>
 *   <li>稳定内容来自 Controller 的补充通知：用户输入与已提交消息里的正文、
 *       思考和 Tool 结果。Hook 契约刻意不携带逐 token 增量，也不在完成事件里
 *       复制大块文本，因此内容在消息稳定后经 noteUserInput /
 *       noteCommittedMessages 补全，并以 responseMessageId / callId 关联。</li>
 * </ul>
 *
 * <p>严格性边界：onEvent 收到未知 traceId 属于装配错误，必须抛错暴露（Hook
 * 异常终止 Execution 是 Runtime 的既定契约）；而 note* 补充通知在轨迹不存在时
 * 静默忽略——自定义 Engine 未接入 Hook 时没有轨迹是既定结果，不是错误。
 *
 * <p>持久化策略：只有归属真实会话（conversationId 非 null）且已经终态的轨迹
 * 才写入 localStorage，每个会话独立键、只保留最近 {@link CALL_TRACE_MAX_TRACES_PER_CONVERSATION}
 * 条；尚未保存的新会话轨迹只存在于内存。写入失败（隐私模式、配额溢出、数据
 * 损坏）不抛出、不重试，而是通过快照的 persistenceError 显式暴露给视图。
 */
import type { AgentHook, AgentHookContext, AgentLifecycleEvent } from './extensions';
import type { ModelStopReason, ModelUsage } from './clients/modelClient';
import type {
  AgentMessage,
  AgentRunOutcome,
  ToolResultBlock,
} from './types';

/** 每个会话在本地保留的最大轨迹条数；超出时从最旧开始丢弃。 */
export const CALL_TRACE_MAX_TRACES_PER_CONVERSATION = 30;

/** 单个文本字段（正文、思考、参数、结果）的截断上限，防止大结果撑爆 localStorage。 */
const DETAIL_TEXT_LIMIT = 4000;

/** localStorage 持久化结构版本；发布前字段演进直接递增并拒绝旧格式。 */
const CALL_TRACE_STORAGE_VERSION = 3;

/**
 * 可注入的最小本地存储端口。
 *
 * <p>生产环境使用 window.localStorage；注入桩是为了在测试里精确验证配额
 * 溢出与数据损坏路径——真实浏览器 storage 无法在 Node 测试中复现这些故障。
 */
export interface CallTraceStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

/** 用户输入记录：一轮执行的起点，由 Controller 在启动执行后补入。 */
export interface UserInputTraceRecord {
  /** 记录种类判别字段。 */
  readonly type: 'user-input';
  /** 用户提交时刻（epoch 毫秒）。 */
  readonly at: number;
  /** 用户输入文本；超长按上限截断。 */
  readonly text: string;
  /** 同批提交的图片附件数量；内容本身不入轨迹。 */
  readonly imageCount: number;
}

/** 单次模型调用记录；开始事件创建，完成事件与消息提交逐步补全。 */
export interface ModelCallTraceRecord {
  /** 记录种类判别字段。 */
  readonly type: 'model-call';
  /** 当前 Execution 内的调用序号（从 1 开始）。 */
  readonly callIndex: number;
  /** Runtime 预分配的 Assistant 消息 ID；用于关联稳定消息内容。 */
  readonly responseMessageId: string;
  /** 调用开始时刻；记录只会在模型调用已经启动后创建。 */
  readonly startedAt: number;
  /** 调用完成时刻；null 表示仍在进行或被中断。 */
  readonly endedAt: number | null;
  /** Provider 标准化停止原因；null 表示尚未完成。 */
  readonly stopReason: ModelStopReason | null;
  /** 本次调用的 token 用量；厂商未提供或未完成时为 null。 */
  readonly usage: ModelUsage | null;
  /** 调用开始到首个非空内容增量的耗时；未完成时为 null。 */
  readonly firstTokenLatencyMs: number | null;
  /** 首个非空内容增量到模型流完成的耗时；未完成时为 null。 */
  readonly outputDurationMs: number | null;
  /** Assistant 正文（截断内）；由稳定消息补全。 */
  readonly text: string;
  /** 展示思考内容（截断内）；由稳定消息补全。 */
  readonly reasoning: string;
  /** 本次 Assistant 消息产生的 Tool Call 标识，保持消息内顺序。 */
  readonly toolCallIds: readonly string[];
}

/** 单次 Tool 调用记录；参数来自开始事件，结果文本由稳定消息补全。 */
export interface ToolCallTraceRecord {
  /** 记录种类判别字段。 */
  readonly type: 'tool-call';
  /** 模型生成的稳定 Tool Call ID。 */
  readonly callId: string;
  /** 本轮快照中的完整 Tool 名。 */
  readonly toolName: string;
  /** 调用开始时刻。 */
  readonly startedAt: number;
  /** 调用完成时刻；null 表示仍在进行或被中断。 */
  readonly endedAt: number | null;
  /** 格式化后的参数 JSON（截断内）。 */
  readonly argumentsText: string;
  /** 回填模型的结果文本（截断内）；空串表示尚无稳定结果。 */
  readonly resultText: string;
  /** 业务是否失败；null 表示尚未完成。 */
  readonly isError: boolean | null;
}

/** 一次 Human-in-the-loop 确认记录；对应一次 interrupt 请求与响应。 */
export interface ConfirmationTraceRecord {
  /** 记录种类判别字段。 */
  readonly type: 'confirmation';
  /** 被确认的 Tool Call ID；来自当时唯一未完成的 Tool 记录。 */
  readonly callId: string;
  /** 待确认的 Tool 名。 */
  readonly toolName: string;
  /** 确认请求发出时刻。 */
  readonly requestedAt: number;
  /** 用户响应时刻；null 表示执行被取消前未响应。 */
  readonly resolvedAt: number | null;
  /** 用户是否批准；null 表示未响应。 */
  readonly approved: boolean | null;
}

/** 轨迹记录联合类型；新增种类时扩展此联合并同步视图。 */
export type CallTraceRecord =
  | UserInputTraceRecord
  | ModelCallTraceRecord
  | ToolCallTraceRecord
  | ConfirmationTraceRecord;

/** 一次 Execution 的完整轨迹；创建于 execution-started，由完成或失败事件封闭。 */
export interface ExecutionTrace {
  /** 与服务端审计串联的链路标识。 */
  readonly traceId: string;
  /** 归属会话；首轮执行尚未建会话时为 null（仅内存，不持久化）。 */
  readonly conversationId: string | null;
  /** 执行开始时刻（epoch 毫秒）。 */
  readonly startedAt: number;
  /** 执行终态时刻；null 表示仍在进行。 */
  readonly endedAt: number | null;
  /** 非异常终态；进行中或异常失败时为 null。 */
  readonly outcome: AgentRunOutcome | null;
  /** 以异常终止时的标准化错误；非异常终态或进行中为 null。 */
  readonly failed: { readonly code: string; readonly message: string } | null;
  /** 按发生顺序排列的过程记录。 */
  readonly records: readonly CallTraceRecord[];
}

/** 面向视图的只读轨迹快照；只在数据变化时重新发布。 */
export interface CallTraceSnapshot {
  /** 当前展示的会话；空白新会话为 null。 */
  readonly conversationId: string | null;
  /** 当前会话的轨迹列表，按时间正序。 */
  readonly traces: readonly ExecutionTrace[];
  /** 本地持久化的最新错误；null 表示无错误或不适用。 */
  readonly persistenceError: string | null;
}

/** 可插拔轨迹视图依赖的最小只读端口（形态对齐 ToolInspectionSource）。 */
export interface CallTraceSource {
  /** 读取当前快照。 */
  snapshot(): CallTraceSnapshot;
  /** 订阅快照；注册时立即收到当前值。 */
  subscribe(listener: (snapshot: CallTraceSnapshot) => void): () => void;
  /** 清空当前会话的轨迹（内存与 localStorage）。 */
  clear(): void;
}

/** 采集未启用时恒定发布的空快照；冻结保证视图拿到的引用不可变。 */
const DISABLED_CALL_TRACE_SNAPSHOT: CallTraceSnapshot = Object.freeze({
  conversationId: null,
  traces: Object.freeze([]),
  persistenceError: null,
});

/**
 * 采集未启用时 Controller 暴露的显式空数据源。
 *
 * <p>设计原因：ready 事件契约要求始终携带 callTraceSource，View 依赖统一端口；
 * 用显式常量表达"未开启采集"，而不是创建一个仍会访问存储的 Store 实例。
 * clear 是幂等空操作——清空一个恒空的轨迹集合在语义上本就是空操作。
 */
export const disabledCallTraceSource: CallTraceSource = {
  snapshot: () => DISABLED_CALL_TRACE_SNAPSHOT,
  subscribe(listener) {
    listener(DISABLED_CALL_TRACE_SNAPSHOT);
    return () => undefined;
  },
  clear: () => undefined,
};

/** CallTraceStore 构造选项；全部提供默认值，测试按需注入。 */
export interface CallTraceStoreOptions {
  /** localStorage 键前缀；仅持久化模式使用，实际键为 `${前缀}:${conversationId}`。 */
  readonly storageKey?: string;
  /**
   * 是否把终态轨迹持久化到 localStorage；缺省 false 只保留当前页内存。
   *
   * <p>采集（创建本 Store 并挂为 Runtime Hook）与持久化是两个独立决策：
   * 前者由装配方显式决定（工厂 callTrace 选项 / Widget call-trace 属性），
   * 后者由本开关显式决定，不存在隐式开启持久化的默认值。
   */
  readonly persist?: boolean;
  /** 时间源；测试注入可控时钟。 */
  readonly now?: () => number;
  /** 本地存储端口；缺省惰性访问全局 localStorage，仅持久化模式会真正访问。 */
  readonly storage?: CallTraceStorage;
}

/** 默认键前缀；与 Widget 的 UX 缓存键命名风格一致。 */
const DEFAULT_STORAGE_KEY = 'patchbridge-agent:call-trace';

/**
 * 轨迹采集与保留的统一入口。
 *
 * <p>同时扮演三个角色：Runtime 的生命周期 Hook、Controller 的内容补充对象、
 * 视图的 CallTraceSource。内部全部使用不可变更新（复制后冻结），发布的快照
 * 可以直接交给任何订阅者而不需要再隔离。
 */
export class CallTraceStore implements AgentHook, CallTraceSource {
  /** localStorage 键前缀；仅持久化模式使用。 */
  private readonly storageKey: string;
  /** 是否持久化终态轨迹；false 时任何路径都不触碰存储端口。 */
  private readonly persistenceEnabled: boolean;
  /** 时间源。 */
  private readonly now: () => number;
  /** 测试可注入的存储端口；缺省包装全局 localStorage。 */
  private readonly storage: CallTraceStorage;
  /** traceId → 轨迹对象；与桶内的对象是同一引用，保证 O(1) 配对。 */
  private readonly tracesById = new Map<string, ExecutionTrace>();
  /** 会话（null 表示未保存的新会话草稿桶）→ 时间正序轨迹数组。 */
  private readonly buckets = new Map<string | null, readonly ExecutionTrace[]>();
  /** 当前展示的会话。 */
  private activeConversationId: string | null = null;
  /** 最近发布的快照。 */
  private currentSnapshot: CallTraceSnapshot;
  /** 视图观察者集合。 */
  private readonly listeners = new Set<(snapshot: CallTraceSnapshot) => void>();
  /** 最近一次持久化错误；发布时随快照暴露，视图负责可见化。 */
  private pendingPersistenceError: string | null = null;
  /** dispose 后不再接受任何输入或订阅。 */
  private disposed = false;

  /**
   * 创建轨迹存储；构造期不访问 storage。
   *
   * <p>仅持久化模式（persist: true）会在会话切换或终态时访问存储端口；
   * 缺省仅内存，全生命周期不触碰 localStorage。
   */
  constructor(options: CallTraceStoreOptions = {}) {
    this.storageKey = options.storageKey ?? DEFAULT_STORAGE_KEY;
    this.persistenceEnabled = options.persist ?? false;
    this.now = options.now ?? (() => Date.now());
    this.storage = options.storage ?? globalLocalStorage();
    this.currentSnapshot = Object.freeze({
      conversationId: null,
      traces: Object.freeze([]),
      persistenceError: null,
    });
  }

  // ---------- AgentHook：过程事实 ----------

  /** 消费 Runtime 生命周期事件；未知 traceId 是装配错误，必须抛错暴露。 */
  onEvent(event: AgentLifecycleEvent, context: AgentHookContext): void {
    if (this.disposed) {
      return;
    }
    switch (event.type) {
      case 'execution-started':
        this.beginExecution(context);
        break;
      case 'model-call-started':
        this.updateTrace(context.traceId, trace => this.beginModelCall(trace, event.callIndex, event.responseMessageId));
        break;
      case 'model-call-completed':
        this.updateTrace(context.traceId, trace => this.completeModelCall(
          trace,
          event.callIndex,
          event.responseMessageId,
          event.stopReason,
          event.usage,
          event.firstTokenLatencyMs,
          event.outputDurationMs,
        ));
        break;
      case 'tool-call-started':
        this.updateTrace(context.traceId, trace => this.beginToolCall(trace, event.callId, event.toolName, event.arguments));
        break;
      case 'tool-call-completed':
        this.updateTrace(context.traceId, trace => this.completeToolCall(trace, event.callId, event.isError));
        break;
      case 'interrupt-requested':
        if (event.interrupt.type === 'tool-confirmation') {
          this.updateTrace(context.traceId, trace => this.requestConfirmation(trace, event.interrupt.tool.name));
        }
        break;
      case 'interrupt-resolved':
        this.updateTrace(context.traceId, trace => this.resolveConfirmation(trace, event.value));
        break;
      case 'execution-completed':
        this.endExecution(context.traceId, {
          outcome: event.outcome,
          failed: null,
        });
        break;
      case 'execution-failed':
        this.endExecution(context.traceId, {
          outcome: null,
          failed: { code: event.errorCode, message: event.errorMessage },
        });
        break;
    }
  }

  // ---------- Controller 内容补充 ----------

  /**
   * 补入本轮用户输入；在 engine.start 返回后调用（execution-started 已同步发布）。
   * 轨迹不存在时忽略：自定义 Engine 未接入 Hook 时没有轨迹是既定结果。
   */
  noteUserInput(traceId: string, text: string, imageCount: number): void {
    this.updateTrace(traceId, trace => ({
      ...trace,
      // 用户输入逻辑上先于一切过程记录，固定使用 Execution 起点并插入首位。
      // 宿主 execution-started Hook 可能同步失败并先封闭轨迹，不能用补录时刻
      // 生成晚于 endedAt 的记录，否则持久化恢复会正确拒绝整条轨迹。
      records: Object.freeze([
        Object.freeze({
          type: 'user-input',
          at: trace.startedAt,
          text: truncateText(text),
          imageCount,
        }) satisfies UserInputTraceRecord,
        ...trace.records,
      ]),
    }), { silentWhenMissing: true });
    const completed = this.tracesById.get(traceId);
    if (completed?.endedAt != null && completed.conversationId != null) {
      // 终态通常已经持久化；同步启动失败是唯一会在终态后补输入的合法时序，
      // 必须立刻重写同一会话键，避免内存轨迹与刷新后的轨迹不一致。
      this.persist(completed.conversationId);
      this.publish();
    }
  }

  /**
   * 消费本轮新增的稳定消息，补全 Assistant 正文与 Tool 结果。
   *
   * <p>Runtime 发布的是本轮累计稳定消息；连续两次 tool-use 时，后一条模型完成事件
   * 会先于它的 Assistant 内容补录。这里必须先在候选轨迹中应用整批消息，再统一校验
   * 和提交，不能让重复补录的旧消息观察到这段合法中间态。任何消息非法时，实时轨迹
   * 保持调用前状态；重复通知同一批消息仍是幂等的。
   */
  noteCommittedMessages(added: readonly AgentMessage[]): void {
    if (this.disposed) {
      return;
    }
    const drafts = new Map<string, ExecutionTrace>();
    for (const message of added) {
      if (message.role === 'assistant') {
        this.fillAssistantContent(message, drafts);
      } else if (message.role === 'tool') {
        this.fillToolResults(message, drafts);
      }
    }
    for (const trace of drafts.values()) {
      if (!hasValidExecutionRecordRelations(trace.records, false)) {
        throw new Error(`轨迹 ${trace.traceId} 的稳定消息批次回填破坏了 Tool 引用关系`);
      }
    }
    this.commitTraceDrafts(drafts);
  }

  // ---------- 会话生命周期 ----------

  /**
   * 切换当前展示的会话；记忆中没有该会话的桶时从 localStorage 恢复。
   * 幂等：同一会话重复调用不发布。
   */
  setActiveConversation(conversationId: string | null): void {
    if (this.disposed || conversationId === this.activeConversationId) {
      return;
    }
    this.activeConversationId = conversationId;
    this.ensureBucketLoaded(conversationId);
    this.publish();
  }

  /**
   * 把草稿桶（conversationId 为 null）中的轨迹迁移到真实会话并持久化。
   * 首轮执行完成后由 Controller 在会话保存成功时调用；轨迹已归属同一会话时为幂等空操作。
   */
  attachConversation(traceId: string, conversationId: string): void {
    if (this.disposed) {
      return;
    }
    const trace = this.tracesById.get(traceId);
    if (trace == null || trace.conversationId === conversationId) {
      return;
    }
    if (trace.conversationId != null) {
      throw new Error(`轨迹 ${traceId} 已归属会话 ${trace.conversationId}，不能改挂到 ${conversationId}`);
    }
    this.moveTrace(trace, conversationId);
    this.persist(conversationId);
    this.publish();
  }

  /** 删除会话时同步丢弃其本地轨迹；会话数据与轨迹数据必须同生共死。 */
  removeConversation(conversationId: string): void {
    if (this.disposed) {
      return;
    }
    for (const trace of this.buckets.get(conversationId) ?? []) {
      this.tracesById.delete(trace.traceId);
    }
    this.buckets.delete(conversationId);
    this.removePersisted(conversationId);
    if (conversationId === this.activeConversationId) {
      this.activeConversationId = null;
    }
    this.publish();
  }

  // ---------- CallTraceSource ----------

  /** 返回最近发布的不可变快照。 */
  snapshot(): CallTraceSnapshot {
    return this.currentSnapshot;
  }

  /** 订阅快照；注册时立即推送当前值，返回幂等取消函数。 */
  subscribe(listener: (snapshot: CallTraceSnapshot) => void): () => void {
    if (this.disposed) {
      throw new Error('CallTraceStore 已释放');
    }
    this.listeners.add(listener);
    listener(this.currentSnapshot);
    return () => this.listeners.delete(listener);
  }

  /** 清空当前会话的轨迹（内存与 localStorage）；草稿会话只清内存。 */
  clear(): void {
    if (this.disposed) {
      return;
    }
    for (const trace of this.buckets.get(this.activeConversationId) ?? []) {
      this.tracesById.delete(trace.traceId);
    }
    this.buckets.delete(this.activeConversationId);
    if (this.activeConversationId != null) {
      this.removePersisted(this.activeConversationId);
    }
    this.publish();
  }

  /** 幂等释放；保留已写入的 localStorage 数据（会话删除时才清理）。 */
  dispose(): void {
    if (this.disposed) {
      return;
    }
    this.disposed = true;
    this.listeners.clear();
  }

  // ---------- 内部：执行与记录配对 ----------

  /** execution-started：先恢复会话桶，再创建并追加本轮轨迹。 */
  private beginExecution(context: AgentHookContext): void {
    this.ensureBucketLoaded(context.conversationId);
    if (this.tracesById.has(context.traceId)) {
      throw new Error(`轨迹 ${context.traceId} 已存在，execution-started 不能重复发布`);
    }
    const trace: ExecutionTrace = Object.freeze({
      traceId: context.traceId,
      conversationId: context.conversationId,
      startedAt: this.now(),
      endedAt: null,
      outcome: null,
      failed: null,
      records: Object.freeze([]),
    });
    this.tracesById.set(context.traceId, trace);
    this.appendToBucket(trace.conversationId, trace);
    this.publish();
  }

  /** 执行终态：写入结束事实，然后尝试持久化（草稿桶不持久化）。 */
  private endExecution(
    traceId: string,
    terminal: {
      readonly outcome: AgentRunOutcome | null;
      readonly failed: { readonly code: string; readonly message: string } | null;
    },
  ): void {
    if ((terminal.outcome == null) === (terminal.failed == null)) {
      throw new Error(`轨迹 ${traceId} 的非异常终态与失败终态必须且只能存在一个`);
    }
    const requiresAllToolCalls = terminal.failed == null
      && terminal.outcome?.type !== 'cancelled';
    this.updateTrace(traceId, trace => {
      if (trace.endedAt != null) {
        throw new Error(`轨迹 ${traceId} 已经进入执行终态，不能重复结束`);
      }
      if (!hasValidExecutionRecordRelations(trace.records, requiresAllToolCalls)) {
        throw new Error(`轨迹 ${traceId} 的记录身份或 Tool 引用不完整，不能进入执行终态`);
      }
      if (!hasConsistentOutcomeWithModelRecords(trace.records, terminal.outcome)) {
        throw new Error(`轨迹 ${traceId} 的模型停止原因与执行终态不一致`);
      }
      return {
        ...trace,
        endedAt: this.now(),
        outcome: terminal.outcome == null
          ? null
          : snapshotAgentRunOutcome(terminal.outcome),
        failed: terminal.failed == null
          ? null
          : Object.freeze({ ...terminal.failed }),
      };
    });
    const trace = this.tracesById.get(traceId);
    if (trace?.conversationId != null) {
      this.persist(trace.conversationId);
    }
    this.publish();
  }

  /** model-call-started：追加一条进行中的模型调用记录。 */
  private beginModelCall(trace: ExecutionTrace, callIndex: number, responseMessageId: string): ExecutionTrace {
    if (trace.records.some(record =>
      record.type === 'model-call' && record.callIndex === callIndex)) {
      throw new Error(`轨迹 ${trace.traceId} 的模型调用序号 ${callIndex} 已存在`);
    }
    if (trace.records.some(record =>
      record.type === 'model-call' && record.responseMessageId === responseMessageId)) {
      throw new Error(`轨迹 ${trace.traceId} 的响应消息 ${responseMessageId} 已存在`);
    }
    return this.appendRecord(trace, Object.freeze({
      type: 'model-call',
      callIndex,
      responseMessageId,
      startedAt: this.now(),
      endedAt: null,
      stopReason: null,
      usage: null,
      firstTokenLatencyMs: null,
      outputDurationMs: null,
      text: '',
      reasoning: '',
      toolCallIds: Object.freeze([]),
    }) satisfies ModelCallTraceRecord);
  }

  /** model-call-completed：按 callIndex 定位记录并补全终态字段。 */
  private completeModelCall(
    trace: ExecutionTrace,
    callIndex: number,
    responseMessageId: string,
    stopReason: ModelStopReason,
    usage: ModelUsage | null,
    firstTokenLatencyMs: number,
    outputDurationMs: number,
  ): ExecutionTrace {
    // responseMessageId 是配对防线：序号匹配但 ID 不同说明事件序列被破坏。
    return this.replaceRecord(
      trace,
      record => record.type === 'model-call' && record.callIndex === callIndex,
      record => ({
        ...(record as ModelCallTraceRecord),
        endedAt: this.now(),
        stopReason,
        usage,
        firstTokenLatencyMs,
        outputDurationMs,
      }) satisfies ModelCallTraceRecord,
      responseMessageId,
    );
  }

  /** tool-call-started：追加一条进行中的 Tool 调用记录。 */
  private beginToolCall(
    trace: ExecutionTrace,
    callId: string,
    toolName: string,
    arguments_: unknown,
  ): ExecutionTrace {
    if (trace.records.some(record =>
      record.type === 'tool-call' && record.callId === callId)) {
      throw new Error(`轨迹 ${trace.traceId} 的 Tool 调用 ${callId} 已存在`);
    }
    if (!trace.records.some(record =>
      record.type === 'model-call' && record.toolCallIds.includes(callId))) {
      throw new Error(`Tool 调用 ${callId} 没有对应的模型声明`);
    }
    return this.appendRecord(trace, Object.freeze({
      type: 'tool-call',
      callId,
      toolName,
      startedAt: this.now(),
      endedAt: null,
      argumentsText: truncateText(safeStringify(arguments_)),
      resultText: '',
      isError: null,
    }) satisfies ToolCallTraceRecord);
  }

  /** tool-call-completed：按 callId 定位记录并补全完成事实。 */
  private completeToolCall(trace: ExecutionTrace, callId: string, isError: boolean): ExecutionTrace {
    return this.replaceRecord(trace, record =>
      record.type === 'tool-call' && record.callId === callId,
      record => ({
        ...(record as ToolCallTraceRecord),
        endedAt: this.now(),
        isError,
      }) satisfies ToolCallTraceRecord);
  }

  /** interrupt-requested：Tool 确认挂起时关联当前未完成的 Tool 调用。 */
  private requestConfirmation(trace: ExecutionTrace, toolName: string): ExecutionTrace {
    // Runtime 同一时刻最多等待一个确认，且必然发生在某次 Tool 调用进行中；
    // 取最后一条未完成的 Tool 记录即是被确认的调用。
    const openTool = [...trace.records].reverse().find((record): record is ToolCallTraceRecord =>
      record.type === 'tool-call' && record.endedAt == null);
    if (openTool == null) {
      throw new Error(`确认请求没有对应的进行中 Tool 调用: ${toolName}`);
    }
    if (openTool.toolName !== toolName) {
      throw new Error(`确认请求 ${toolName} 与进行中 Tool ${openTool.toolName} 不匹配`);
    }
    if (trace.records.some(record =>
      record.type === 'confirmation' && record.callId === openTool.callId)) {
      throw new Error(`Tool 调用 ${openTool.callId} 已存在确认记录`);
    }
    return this.appendRecord(trace, Object.freeze({
      type: 'confirmation',
      callId: openTool.callId,
      toolName,
      requestedAt: this.now(),
      resolvedAt: null,
      approved: null,
    }) satisfies ConfirmationTraceRecord);
  }

  /** interrupt-resolved：补全唯一一条未响应的确认记录。 */
  private resolveConfirmation(trace: ExecutionTrace, approved: boolean): ExecutionTrace {
    let resolved = false;
    const records = trace.records.map(record => {
      if (record.type !== 'confirmation' || record.resolvedAt != null) {
        return record;
      }
      resolved = true;
      return Object.freeze({
        ...record,
        resolvedAt: this.now(),
        approved,
      }) satisfies ConfirmationTraceRecord;
    });
    if (!resolved) {
      throw new Error('确认响应没有对应的挂起确认记录');
    }
    return Object.freeze({ ...trace, records: Object.freeze(records) });
  }

  /** 按消息 ID 把 Assistant 内容写入当前批次候选轨迹。 */
  private fillAssistantContent(
    message: AgentMessage,
    drafts: Map<string, ExecutionTrace>,
  ): void {
    let text = '';
    let reasoning = '';
    const toolCallIds: string[] = [];
    for (const block of message.blocks) {
      if (block.type === 'text') {
        text += block.text;
      } else if (block.type === 'reasoning') {
        reasoning += block.text;
      } else if (block.type === 'tool-call') {
        toolCallIds.push(block.callId);
      }
    }
    const uniqueToolCallIds = new Set<string>();
    for (const callId of toolCallIds) {
      if (callId.length === 0 || uniqueToolCallIds.has(callId)) {
        throw new Error(`Assistant 消息 ${message.id} 包含空或重复的 Tool Call ID`);
      }
      uniqueToolCallIds.add(callId);
    }
    this.updateTraceDraftByRecord(
      drafts,
      record => record.type === 'model-call' && record.responseMessageId === message.id,
      (record, trace) => {
        const duplicatedAcrossModels = trace.records.some(candidate =>
          candidate !== record
          && candidate.type === 'model-call'
          && candidate.toolCallIds.some(callId => uniqueToolCallIds.has(callId)));
        if (duplicatedAcrossModels) {
          throw new Error(`轨迹 ${trace.traceId} 的模型记录重复声明 Tool Call ID`);
        }
        const nextRecord = Object.freeze({
          ...(record as ModelCallTraceRecord),
          text: truncateText(text),
          reasoning: truncateText(reasoning),
          toolCallIds: Object.freeze(toolCallIds),
        } satisfies ModelCallTraceRecord);
        return nextRecord;
      },
    );
  }

  /** 按 callId 把稳定 Tool 结果写入当前批次候选轨迹。 */
  private fillToolResults(
    message: AgentMessage,
    drafts: Map<string, ExecutionTrace>,
  ): void {
    for (const block of message.blocks) {
      if (block.type !== 'tool-result') {
        continue;
      }
      const content = block.content
        .map(item => item.type === 'text' ? item.text : '')
        .join('');
      this.updateTraceDraftByRecord(
        drafts,
        record => record.type === 'tool-call' && record.callId === block.callId,
        record => ({
          ...(record as ToolCallTraceRecord),
          resultText: truncateText(content),
          isError: block.status === 'error',
        }) satisfies ToolCallTraceRecord,
      );
    }
  }

  // ---------- 内部：不可变更新 ----------

  /**
   * 对指定轨迹应用一次不可变更新并发布。
   *
   * <p>silentWhenMissing 仅用于 Controller 的内容补充通知（自定义 Engine 没有
   * 轨迹是既定结果）；Hook 事件路径保持严格抛错。
   */
  private updateTrace(
    traceId: string,
    update: (trace: ExecutionTrace) => ExecutionTrace,
    options: { silentWhenMissing?: boolean } = {},
  ): void {
    if (this.disposed) {
      return;
    }
    const trace = this.tracesById.get(traceId);
    if (trace == null) {
      if (options.silentWhenMissing) {
        return;
      }
      throw new Error(`收到未知 traceId ${traceId} 的生命周期事件，Hook 装配可能不完整`);
    }
    this.replaceTrace(trace, update(trace));
  }

  /**
   * 跨任意实时轨迹定位一条记录，并只更新当前稳定消息批次的候选版本。
   *
   * <p>候选轨迹按 traceId 复用，使同一批累计消息能看到此前消息的候选结果；在
   * noteCommittedMessages 完成整批校验前，不得调用 replaceTrace 污染实时索引。
   */
  private updateTraceDraftByRecord(
    drafts: Map<string, ExecutionTrace>,
    matches: (record: CallTraceRecord) => boolean,
    update: (record: CallTraceRecord, trace: ExecutionTrace) => CallTraceRecord,
  ): void {
    for (const liveTrace of this.tracesById.values()) {
      const trace = drafts.get(liveTrace.traceId) ?? liveTrace;
      if (!trace.records.some(matches)) {
        continue;
      }
      const records = trace.records.map(record =>
        matches(record) ? Object.freeze(update(record, trace)) : record);
      drafts.set(
        trace.traceId,
        Object.freeze({ ...trace, records: Object.freeze(records) }),
      );
      return;
    }
    // 与 note* 通道的约定一致：轨迹不存在（自定义 Engine）时静默忽略。
  }

  /**
   * 一次提交已经全部校验通过的候选轨迹，并只向当前会话观察者发布一次。
   *
   * <p>提交前先验证每条候选轨迹仍位于某个会话桶；若内部索引已经分裂则直接失败，
   * 保证 tracesById 与 buckets 不会只更新一侧。
   */
  private commitTraceDrafts(drafts: ReadonlyMap<string, ExecutionTrace>): void {
    if (drafts.size === 0) {
      return;
    }
    const nextBuckets = new Map<string | null, readonly ExecutionTrace[]>();
    const locatedTraceIds = new Set<string>();
    for (const [conversationId, bucket] of this.buckets) {
      let changed = false;
      const nextBucket = bucket.map(trace => {
        const draft = drafts.get(trace.traceId);
        if (draft == null) {
          return trace;
        }
        changed = true;
        locatedTraceIds.add(trace.traceId);
        return draft;
      });
      if (changed) {
        nextBuckets.set(conversationId, Object.freeze(nextBucket));
      }
    }
    if (locatedTraceIds.size !== drafts.size) {
      throw new Error('稳定消息批次候选轨迹与会话桶索引不一致');
    }
    for (const [traceId, draft] of drafts) {
      this.tracesById.set(traceId, draft);
    }
    for (const [conversationId, bucket] of nextBuckets) {
      this.buckets.set(conversationId, bucket);
    }
    if (nextBuckets.has(this.activeConversationId)) {
      this.publish();
    }
  }

  /** 在轨迹末尾追加一条记录。 */
  private appendRecord(trace: ExecutionTrace, record: CallTraceRecord): ExecutionTrace {
    return Object.freeze({ ...trace, records: Object.freeze([...trace.records, record]) });
  }

  /** 按谓词替换一条记录；expectResponseMessageId 非空时同时校验配对防线。 */
  private replaceRecord(
    trace: ExecutionTrace,
    matches: (record: CallTraceRecord) => boolean,
    update: (record: CallTraceRecord) => CallTraceRecord,
    expectResponseMessageId?: string,
  ): ExecutionTrace {
    let matched: CallTraceRecord | null = null;
    const records = trace.records.map(record => {
      if (!matches(record)) {
        return record;
      }
      matched = record;
      return Object.freeze(update(record));
    });
    if (matched == null) {
      throw new Error(`生命周期完成事件找不到配对的开始记录（traceId=${trace.traceId}）`);
    }
    if (expectResponseMessageId != null) {
      const original = matched as ModelCallTraceRecord;
      if (original.responseMessageId !== expectResponseMessageId) {
        throw new Error(
          `模型调用 ${expectResponseMessageId} 与开始记录 ${original.responseMessageId} 不匹配`,
        );
      }
    }
    return Object.freeze({ ...trace, records: Object.freeze(records) });
  }

  /** 把更新后的轨迹对象写回索引与所属桶。 */
  private replaceTrace(previous: ExecutionTrace, next: ExecutionTrace): void {
    this.tracesById.set(next.traceId, next);
    const bucket = this.buckets.get(previous.conversationId) ?? [];
    this.buckets.set(
      previous.conversationId,
      Object.freeze(bucket.map(trace => trace === previous ? next : trace)),
    );
    if (this.publishesBucket(previous.conversationId)) {
      this.publish();
    }
  }

  /** 草稿桶（null）迁移到真实会话桶；目标桶先恢复并保持时间正序追加。 */
  private moveTrace(trace: ExecutionTrace, conversationId: string): void {
    const targetBucket = this.ensureBucketLoaded(conversationId);
    if (targetBucket.some(item => item.traceId === trace.traceId)) {
      throw new Error(`会话 ${conversationId} 已存在轨迹 ${trace.traceId}，不能重复迁移`);
    }
    const draftBucket = this.buckets.get(null) ?? [];
    this.buckets.set(
      null,
      Object.freeze(draftBucket.filter(item => item !== trace)),
    );
    const moved: ExecutionTrace = Object.freeze({ ...trace, conversationId });
    this.tracesById.set(moved.traceId, moved);
    this.buckets.set(conversationId, Object.freeze([...targetBucket, moved]));
  }

  /** 追加到目标桶末尾；目标会话尚未进内存时先恢复已有本地轨迹。 */
  private appendToBucket(conversationId: string | null, trace: ExecutionTrace): void {
    const bucket = this.ensureBucketLoaded(conversationId);
    this.buckets.set(conversationId, Object.freeze([...bucket, trace]));
  }

  /**
   * 返回已加载桶；首次访问非空会话时从 localStorage 恢复。
   *
   * <p>所有写入路径必须经过这里，避免“先写新轨迹、后读旧历史”导致覆盖。
   */
  private ensureBucketLoaded(
    conversationId: string | null,
  ): readonly ExecutionTrace[] {
    const loaded = this.buckets.get(conversationId);
    if (loaded != null) {
      return loaded;
    }
    const restored = this.readPersisted(conversationId);
    this.buckets.set(conversationId, restored);
    return restored;
  }

  /** 当前会话的桶发生变化才需要发布快照。 */
  private publishesBucket(conversationId: string | null): boolean {
    return conversationId === this.activeConversationId;
  }

  /** 重建并向全部观察者发布不可变快照。 */
  private publish(): void {
    const traces = this.buckets.get(this.activeConversationId) ?? [];
    this.currentSnapshot = Object.freeze({
      conversationId: this.activeConversationId,
      traces,
      persistenceError: this.pendingPersistenceError,
    });
    for (const listener of [...this.listeners]) {
      listener(this.currentSnapshot);
    }
  }

  // ---------- 内部：localStorage ----------

  /** 执行有界内存保留；持久化模式额外写入会话键，且只写终态轨迹。 */
  private persist(conversationId: string): void {
    const bucket = this.buckets.get(conversationId) ?? [];
    const completed = bucket
      .filter(trace => trace.endedAt != null)
      .slice(-CALL_TRACE_MAX_TRACES_PER_CONVERSATION);
    const retainedIds = new Set(completed.map(trace => trace.traceId));
    const retained = bucket.filter(trace =>
      trace.endedAt == null || retainedIds.has(trace.traceId));
    const retainedTraceIds = new Set(retained.map(trace => trace.traceId));
    for (const trace of bucket) {
      if (!retainedTraceIds.has(trace.traceId)) {
        this.tracesById.delete(trace.traceId);
      }
    }
    this.buckets.set(conversationId, Object.freeze(retained));
    // 内存保留策略对两种模式一致（有界内存）；仅持久化模式继续写存储。
    if (!this.persistenceEnabled) {
      return;
    }
    try {
      const payload = JSON.stringify({
        version: CALL_TRACE_STORAGE_VERSION,
        traces: completed,
      });
      this.storage.setItem(this.persistKey(conversationId), payload);
      this.pendingPersistenceError = null;
    } catch (cause) {
      // 隐私模式、配额溢出等：轨迹功能必须服从“不打断 Agent 主流程”，
      // 失败显式暴露在快照里，不重试、不降级写其他键。
      this.pendingPersistenceError = cause instanceof Error
        ? cause.message
        : String(cause);
    }
  }

  /** 读取会话键并反序列化；数据损坏时按“丢弃本地缓存”处理并显式暴露。 */
  private readPersisted(conversationId: string | null): readonly ExecutionTrace[] {
    // 未开启持久化时本地永远没有历史：直接返回空集合且不触碰存储端口。
    if (!this.persistenceEnabled || conversationId == null) {
      this.pendingPersistenceError = null;
      return Object.freeze([]);
    }
    try {
      const raw = this.storage.getItem(this.persistKey(conversationId));
      if (raw == null) {
        this.pendingPersistenceError = null;
        return Object.freeze([]);
      }
      const parsed: unknown = JSON.parse(raw);
      const traces = toExecutionTraces(parsed, conversationId);
      if (traces == null) {
        throw new Error('轨迹数据结构不符合当前版本');
      }
      for (const trace of traces) {
        const existing = this.tracesById.get(trace.traceId);
        if (existing != null && existing.conversationId !== conversationId) {
          throw new Error(`轨迹 ${trace.traceId} 同时归属多个会话`);
        }
      }
      for (const trace of traces) {
        this.tracesById.set(trace.traceId, trace);
      }
      this.pendingPersistenceError = null;
      return traces;
    } catch (cause) {
      // 本地缓存不是真相源：损坏即整体丢弃，不做局部修复，错误显式可见。
      this.pendingPersistenceError = cause instanceof Error
        ? cause.message
        : String(cause);
      return Object.freeze([]);
    }
  }

  /** 删除会话键；未开启持久化时无键可删直接返回。失败同样只暴露不抛出。 */
  private removePersisted(conversationId: string): void {
    if (!this.persistenceEnabled) {
      return;
    }
    try {
      this.storage.removeItem(this.persistKey(conversationId));
      this.pendingPersistenceError = null;
    } catch (cause) {
      this.pendingPersistenceError = cause instanceof Error
        ? cause.message
        : String(cause);
    }
  }

  /** 会话对应的 localStorage 键。 */
  private persistKey(conversationId: string): string {
    return `${this.storageKey}:${conversationId}`;
  }
}

/**
 * 校验并收窄反序列化结果为 ExecutionTrace 数组。
 *
 * <p>不做局部修复：版本、会话归属、终态、记录联合类型或嵌套字段任一不符，
 * 整份会话缓存即判为损坏，由调用方显式暴露错误并丢弃。
 */
function toExecutionTraces(
  value: unknown,
  conversationId: string,
): readonly ExecutionTrace[] | null {
  if (value == null || typeof value !== 'object') {
    return null;
  }
  if (Array.isArray(value)) {
    return null;
  }
  const container = value as Record<string, unknown>;
  if (
    !hasExactKeys(container, ['version', 'traces'])
    || container['version'] !== CALL_TRACE_STORAGE_VERSION
    || !Array.isArray(container['traces'])
    || container['traces'].length > CALL_TRACE_MAX_TRACES_PER_CONVERSATION
  ) {
    return null;
  }
  const traces: ExecutionTrace[] = [];
  const traceIds = new Set<string>();
  for (const item of container['traces']) {
    const trace = toExecutionTrace(item, conversationId);
    if (trace == null || traceIds.has(trace.traceId)) {
      return null;
    }
    traceIds.add(trace.traceId);
    traces.push(trace);
  }
  return Object.freeze(traces);
}

/** 单条持久化轨迹必须属于目标会话且已经到达 Execution 终态。 */
function toExecutionTrace(
  value: unknown,
  conversationId: string,
): ExecutionTrace | null {
  if (value == null || typeof value !== 'object') {
    return null;
  }
  if (Array.isArray(value)) {
    return null;
  }
  const trace = value as Partial<ExecutionTrace>;
  if (!hasExactKeys(value as Record<string, unknown>, [
    'traceId',
    'conversationId',
    'startedAt',
    'endedAt',
    'outcome',
    'failed',
    'records',
  ])) {
    return null;
  }
  const outcome = toAgentRunOutcome(trace.outcome);
  if (
    typeof trace.traceId !== 'string'
    || trace.traceId.length === 0
    || trace.conversationId !== conversationId
    || !isFiniteNumber(trace.startedAt)
    || !isFiniteNumber(trace.endedAt)
    || trace.endedAt < trace.startedAt
    || outcome === undefined
    || !isFailure(trace.failed)
    || (outcome == null) === (trace.failed == null)
    || !Array.isArray(trace.records)
  ) {
    return null;
  }
  const records: CallTraceRecord[] = [];
  for (const item of trace.records) {
    const record = toTraceRecord(item);
    if (
      record == null
      || !isRecordWithinExecution(record, trace.startedAt, trace.endedAt)
    ) {
      return null;
    }
    records.push(record);
  }
  if (!hasValidExecutionRecordRelations(
    records,
    trace.failed == null && outcome?.type !== 'cancelled',
  )) {
    return null;
  }
  if (!hasConsistentOutcomeWithModelRecords(records, outcome)) {
    return null;
  }
  return Object.freeze({
    traceId: trace.traceId,
    conversationId,
    startedAt: trace.startedAt,
    endedAt: trace.endedAt,
    outcome,
    failed: trace.failed == null
      ? null
      : Object.freeze({ code: trace.failed.code, message: trace.failed.message }),
    records: Object.freeze(records),
  });
}

/**
 * 校验同一 Execution 内的记录身份与引用关系。
 *
 * <p>自然完成或 max-tokens 时，模型声明的每个 Tool Call 都必须形成 Tool 记录；
 * 取消或失败可能发生在 Tool 真正启动前，因此只允许这两类终态保留尚未开始的声明。
 * 每条已完成模型记录的 stopReason 也必须与 Tool 声明数量一致。已经形成的 Tool 与
 * 确认记录始终必须反向关联到唯一模型声明，避免恢复后产生重复 UI key 或悬空详情。
 */
function hasValidExecutionRecordRelations(
  records: readonly CallTraceRecord[],
  requiresAllToolCalls: boolean,
): boolean {
  const modelCallIndexes = new Set<number>();
  const responseMessageIds = new Set<string>();
  const declaredToolCallIds = new Set<string>();
  const toolCalls = new Map<string, string>();
  const confirmations = new Map<string, string>();

  for (const record of records) {
    if (record.type === 'model-call') {
      if (
        modelCallIndexes.has(record.callIndex)
        || responseMessageIds.has(record.responseMessageId)
        || (record.stopReason === 'tool-use') !== (record.toolCallIds.length > 0)
      ) {
        return false;
      }
      modelCallIndexes.add(record.callIndex);
      responseMessageIds.add(record.responseMessageId);
      for (const callId of record.toolCallIds) {
        if (callId.length === 0 || declaredToolCallIds.has(callId)) {
          return false;
        }
        declaredToolCallIds.add(callId);
      }
      continue;
    }
    if (record.type === 'tool-call') {
      if (toolCalls.has(record.callId)) {
        return false;
      }
      toolCalls.set(record.callId, record.toolName);
      continue;
    }
    if (record.type === 'confirmation') {
      if (confirmations.has(record.callId)) {
        return false;
      }
      confirmations.set(record.callId, record.toolName);
    }
  }

  for (const callId of toolCalls.keys()) {
    if (!declaredToolCallIds.has(callId)) {
      return false;
    }
  }
  for (const [callId, toolName] of confirmations) {
    if (toolCalls.get(callId) !== toolName) {
      return false;
    }
  }
  if (requiresAllToolCalls) {
    for (const callId of declaredToolCallIds) {
      if (!toolCalls.has(callId)) {
        return false;
      }
    }
  }
  return true;
}

/**
 * 自然完成和输出截断必须由最后一条模型记录直接证明。
 *
 * <p>自然完成或 max-tokens 必须至少有一条模型记录，最后一次调用必须已经封闭，
 * 且停止原因必须与唯一 Outcome 完全一致。cancelled 和失败允许停在模型或 Tool 的
 * 任意中间位置，不应用这条完成态约束。
 */
function hasConsistentOutcomeWithModelRecords(
  records: readonly CallTraceRecord[],
  outcome: AgentRunOutcome | null,
): boolean {
  if (outcome == null || outcome.type === 'cancelled') {
    return true;
  }
  const modelRecords = records.filter(
    (record): record is ModelCallTraceRecord => record.type === 'model-call',
  );
  const lastModelRecord = modelRecords[modelRecords.length - 1];
  if (lastModelRecord == null) {
    return false;
  }
  if (lastModelRecord.endedAt == null) {
    return false;
  }
  return outcome.type === 'max-tokens'
    ? lastModelRecord.stopReason === 'max-tokens'
    : lastModelRecord.stopReason === outcome.stopReason;
}

/** localStorage 中的一条记录必须完整匹配当前联合类型。 */
function toTraceRecord(value: unknown): CallTraceRecord | null {
  if (value == null || typeof value !== 'object') {
    return null;
  }
  const record = value as Record<string, unknown>;
  switch (record['type']) {
    case 'user-input':
      return toUserInputRecord(record);
    case 'model-call':
      return toModelCallRecord(record);
    case 'tool-call':
      return toToolCallRecord(record);
    case 'confirmation':
      return toConfirmationRecord(record);
    default:
      return null;
  }
}

/** 用户输入记录结构校验。 */
function toUserInputRecord(record: Record<string, unknown>): UserInputTraceRecord | null {
  if (
    !hasExactKeys(record, ['type', 'at', 'text', 'imageCount'])
    || !isFiniteNumber(record['at'])
    || !isBoundedText(record['text'])
    || !isNonNegativeInteger(record['imageCount'])
  ) {
    return null;
  }
  return Object.freeze({
    type: 'user-input',
    at: record['at'],
    text: record['text'],
    imageCount: record['imageCount'],
  });
}

/** 模型调用记录结构校验；usage 与 Tool Call ID 数组均重新冻结。 */
function toModelCallRecord(record: Record<string, unknown>): ModelCallTraceRecord | null {
  const startedAt = record['startedAt'];
  const endedAt = record['endedAt'];
  const stopReason = record['stopReason'];
  const usage = toModelUsage(record['usage']);
  const firstTokenLatencyMs = record['firstTokenLatencyMs'];
  const outputDurationMs = record['outputDurationMs'];
  const toolCallIds = record['toolCallIds'];
  const lifecycleValid = (endedAt === null
      && stopReason === null
      && usage === null
      && firstTokenLatencyMs === null
      && outputDurationMs === null)
    || (isFiniteNumber(endedAt)
      && isFiniteNumber(startedAt)
      && endedAt >= startedAt
      && isCompletedStopReason(stopReason)
      && isNonNegativeFiniteNumber(firstTokenLatencyMs)
      && isNonNegativeFiniteNumber(outputDurationMs));
  if (
    !hasExactKeys(record, [
      'type',
      'callIndex',
      'responseMessageId',
      'startedAt',
      'endedAt',
      'stopReason',
      'usage',
      'firstTokenLatencyMs',
      'outputDurationMs',
      'text',
      'reasoning',
      'toolCallIds',
    ])
    || !isPositiveInteger(record['callIndex'])
    || typeof record['responseMessageId'] !== 'string'
    || record['responseMessageId'].length === 0
    || !isFiniteNumber(startedAt)
    || !lifecycleValid
    || usage === undefined
    || !isBoundedText(record['text'])
    || !isBoundedText(record['reasoning'])
    || !Array.isArray(toolCallIds)
    || !toolCallIds.every(item => typeof item === 'string')
  ) {
    return null;
  }
  return Object.freeze({
    type: 'model-call',
    callIndex: record['callIndex'],
    responseMessageId: record['responseMessageId'],
    startedAt,
    endedAt: endedAt as number | null,
    stopReason: stopReason as ModelStopReason | null,
    usage,
    firstTokenLatencyMs: firstTokenLatencyMs as number | null,
    outputDurationMs: outputDurationMs as number | null,
    text: record['text'],
    reasoning: record['reasoning'],
    toolCallIds: Object.freeze([...toolCallIds] as string[]),
  });
}

/** Tool 调用记录结构校验；完成时间与业务状态必须成对出现。 */
function toToolCallRecord(record: Record<string, unknown>): ToolCallTraceRecord | null {
  const startedAt = record['startedAt'];
  const endedAt = record['endedAt'];
  const isError = record['isError'];
  const lifecycleValid = (endedAt === null
      && isError === null
      && record['resultText'] === '')
    || (isFiniteNumber(endedAt)
      && isFiniteNumber(startedAt)
      && endedAt >= startedAt
      && typeof isError === 'boolean');
  if (
    !hasExactKeys(record, [
      'type',
      'callId',
      'toolName',
      'startedAt',
      'endedAt',
      'argumentsText',
      'resultText',
      'isError',
    ])
    || typeof record['callId'] !== 'string'
    || record['callId'].length === 0
    || typeof record['toolName'] !== 'string'
    || !isFiniteNumber(startedAt)
    || !lifecycleValid
    || !isBoundedText(record['argumentsText'])
    || !isBoundedText(record['resultText'])
  ) {
    return null;
  }
  return Object.freeze({
    type: 'tool-call',
    callId: record['callId'],
    toolName: record['toolName'],
    startedAt,
    endedAt: endedAt as number | null,
    argumentsText: record['argumentsText'],
    resultText: record['resultText'],
    isError: isError as boolean | null,
  });
}

/** 确认记录结构校验；响应时间与 approved 必须成对出现。 */
function toConfirmationRecord(
  record: Record<string, unknown>,
): ConfirmationTraceRecord | null {
  const requestedAt = record['requestedAt'];
  const resolvedAt = record['resolvedAt'];
  const approved = record['approved'];
  const lifecycleValid = (resolvedAt === null && approved === null)
    || (isFiniteNumber(resolvedAt)
      && isFiniteNumber(requestedAt)
      && resolvedAt >= requestedAt
      && typeof approved === 'boolean');
  if (
    !hasExactKeys(record, [
      'type',
      'callId',
      'toolName',
      'requestedAt',
      'resolvedAt',
      'approved',
    ])
    || typeof record['callId'] !== 'string'
    || record['callId'].length === 0
    || typeof record['toolName'] !== 'string'
    || !isFiniteNumber(requestedAt)
    || !lifecycleValid
  ) {
    return null;
  }
  return Object.freeze({
    type: 'confirmation',
    callId: record['callId'],
    toolName: record['toolName'],
    requestedAt,
    resolvedAt: resolvedAt as number | null,
    approved: approved as boolean | null,
  });
}

/** 记录起止时间必须完整落在所属 Execution 区间内。 */
function isRecordWithinExecution(
  record: CallTraceRecord,
  executionStartedAt: number,
  executionEndedAt: number,
): boolean {
  let startedAt: number;
  let endedAt: number | null;
  switch (record.type) {
    case 'user-input':
      startedAt = record.at;
      endedAt = record.at;
      break;
    case 'model-call':
    case 'tool-call':
      startedAt = record.startedAt;
      endedAt = record.endedAt;
      break;
    case 'confirmation':
      startedAt = record.requestedAt;
      endedAt = record.resolvedAt;
      break;
  }
  return startedAt >= executionStartedAt
    && startedAt <= executionEndedAt
    && (endedAt == null || (endedAt >= startedAt && endedAt <= executionEndedAt));
}

/** localStorage 恢复文本必须遵守采集端相同的单字段容量上限。 */
function isBoundedText(value: unknown): value is string {
  return typeof value === 'string' && value.length <= DETAIL_TEXT_LIMIT;
}

/** 复制并冻结非异常终态，切断 Hook 事件与轨迹快照之间的对象别名。 */
function snapshotAgentRunOutcome(outcome: AgentRunOutcome): AgentRunOutcome {
  return Object.freeze({ ...outcome });
}

/**
 * 严格恢复 v3 非异常终态；undefined 表示结构非法，null 表示异常或进行中。
 * completed 只接受自然结束原因，tool-use 与 max-tokens 不能伪装成 completed。
 */
function toAgentRunOutcome(
  value: unknown,
): AgentRunOutcome | null | undefined {
  if (value === null) {
    return null;
  }
  if (typeof value !== 'object' || Array.isArray(value)) {
    return undefined;
  }
  const outcome = value as Record<string, unknown>;
  if (outcome['type'] === 'completed') {
    if (
      !hasExactKeys(outcome, ['type', 'stopReason'])
      || !isNaturalCompletionStopReason(outcome['stopReason'])
    ) {
      return undefined;
    }
    return Object.freeze({
      type: 'completed',
      stopReason: outcome['stopReason'],
    });
  }
  if (outcome['type'] === 'max-tokens' || outcome['type'] === 'cancelled') {
    if (!hasExactKeys(outcome, ['type'])) {
      return undefined;
    }
    return Object.freeze({ type: outcome['type'] });
  }
  return undefined;
}

/** AgentRunOutcome.completed 允许的自然停止原因。 */
function isNaturalCompletionStopReason(
  value: unknown,
): value is Extract<AgentRunOutcome, { readonly type: 'completed' }>['stopReason'] {
  return value === 'end-turn'
    || value === 'stop-sequence'
    || value === 'other';
}

/** localStorage 对象必须精确匹配当前版本字段，禁止旧字段或未知扩展静默进入。 */
function hasExactKeys(
  value: Record<string, unknown>,
  expected: readonly string[],
): boolean {
  const keys = Object.keys(value);
  return keys.length === expected.length
    && expected.every(key => Object.prototype.hasOwnProperty.call(value, key));
}

/** 可持久化失败对象结构校验。 */
function isFailure(value: unknown): value is ExecutionTrace['failed'] {
  if (value === null) {
    return true;
  }
  if (typeof value !== 'object' || value == null || Array.isArray(value)) {
    return false;
  }
  const failure = value as Record<string, unknown>;
  return hasExactKeys(failure, ['code', 'message'])
    && typeof failure['code'] === 'string'
    && typeof failure['message'] === 'string';
}

/** ModelUsage 校验；undefined 表示非法，null 表示厂商未提供。 */
function toModelUsage(value: unknown): ModelUsage | null | undefined {
  if (value === null) {
    return null;
  }
  if (typeof value !== 'object' || value == null) {
    return undefined;
  }
  if (Array.isArray(value)) {
    return undefined;
  }
  const usage = value as Record<string, unknown>;
  if (
    !hasExactKeys(usage, ['inputTokens', 'outputTokens', 'totalTokens'])
    || !isNonNegativeInteger(usage['inputTokens'])
    || !isNonNegativeInteger(usage['outputTokens'])
    || !isNonNegativeInteger(usage['totalTokens'])
  ) {
    return undefined;
  }
  return Object.freeze({
    inputTokens: usage['inputTokens'],
    outputTokens: usage['outputTokens'],
    totalTokens: usage['totalTokens'],
  });
}

/** 完成态模型停止原因白名单。 */
function isCompletedStopReason(value: unknown): value is ModelStopReason {
  return [
    'end-turn',
    'tool-use',
    'max-tokens',
    'stop-sequence',
    'other',
  ].includes(value as ModelStopReason);
}

/** 有限数值判别，拒绝 NaN/Infinity。 */
function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

/** 非负有限数值判别；性能耗时允许亚毫秒小数。 */
function isNonNegativeFiniteNumber(value: unknown): value is number {
  return isFiniteNumber(value) && value >= 0;
}

/** 非负整数判别。 */
function isNonNegativeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0;
}

/** 正整数判别。 */
function isPositiveInteger(value: unknown): value is number {
  return isNonNegativeInteger(value) && value > 0;
}

/** 按上限截断文本并标注原始长度；持久化容量的唯一防线。 */
function truncateText(text: string): string {
  if (text.length <= DETAIL_TEXT_LIMIT) {
    return text;
  }
  const suffix = `…（已截断，共 ${text.length} 字符）`;
  return `${text.slice(0, DETAIL_TEXT_LIMIT - suffix.length)}${suffix}`;
}

/** 参数序列化；循环引用等序列化失败按明确错误文本保留现场。 */
function safeStringify(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2) ?? String(value);
  } catch {
    return `[无法序列化的参数: ${String(value)}]`;
  }
}

/** 惰性访问全局 localStorage；非浏览器环境返回访问即抛错的桩。 */
function globalLocalStorage(): CallTraceStorage {
  return {
    getItem(key) {
      return localStorage.getItem(key);
    },
    setItem(key, value) {
      localStorage.setItem(key, value);
    },
    removeItem(key) {
      localStorage.removeItem(key);
    },
  };
}
