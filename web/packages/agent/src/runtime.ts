/**
 * 默认 Browser Agent Runtime：执行厂商中立、有界且可取消的 Agent Loop。
 *
 * <p>Runtime 只处理 Agent 语义：结构化模型事件、稳定 ContentBlock、本轮 Tool
 * 快照和 Human-in-the-loop。所有厂商请求/响应字段都属于服务端 Model Provider，
 * 本文件不得出现 OpenAI、Anthropic 或 Responses API 的 wire protocol 分支。
 */
import type {
  AgentEngine,
  AgentExecution,
  AgentExecutionEvent,
  AgentInterruptResponse,
  AgentRunInput,
  AgentRunResult,
  ToolConfirmationInterrupt,
} from './engine';
import type {
  Model,
  ModelRequest,
  ModelStreamEvent,
  ModelToolDefinition,
} from './clients/modelClient';
import type { ContextManager } from './contextManager';
import {
  ModelMessageAssembler,
  type AssembledModelMessage,
} from './modelMessageAssembler';
import {
  composeModelInterceptors,
  composeToolInterceptors,
  snapshotHooks,
  type AgentHook,
  type AgentHookContext,
  type AgentHookFailure,
  type AgentLifecycleEvent,
  type AgentTerminalLifecycleEvent,
  type ModelCallNext,
  type ModelInterceptor,
  type ToolCallNext,
  type ToolInterceptor,
} from './extensions';
import type {
  AgentError,
  AgentMessage,
  AgentRunOutcome,
  JsonObject,
  ModelContext,
  ModelState,
  ToolCallBlock,
  ToolCallResult,
  ToolDefinition,
} from './types';
import {
  copyAndFreezeJsonObject,
} from './jsonValues';
import {
  snapshotAgentMessage,
  snapshotModelContext,
} from './messageValues';

/** Runtime 生成稳定标识时使用的业务命名空间。 */
export type RuntimeIdKind = 'message' | 'interrupt';

/** 一次 Browser Execution 必须同时具备的完整资源上限。 */
export interface AgentExecutionLimits {
  /** 单次 Execution 最多允许调用模型的次数。 */
  readonly maxModelCalls: number;
  /** 单次 Execution 最多允许模型请求的 Tool Call 总数。 */
  readonly maxToolCalls: number;
  /** 从 Execution 启动到终态的最大毫秒数，包含人工确认等待。 */
  readonly maxDurationMs: number;
  /** 单次模型调用允许聚合的正文、思考与 Tool 参数字符总数。 */
  readonly maxModelOutputCharacters: number;
  /** 单个 Tool 结果允许回填模型的最大字符数。 */
  readonly maxToolResultCharacters: number;
}

/**
 * 便捷工厂使用的有界默认预算。
 *
 * <p>这些值只为默认 Widget 和 Demo 提供明确安全边界，不代表宿主业务 SLA。
 * 直接构造 Runtime 仍必须完整提供 limits，避免关键资源约束被调用方无意省略。
 */
export const DEFAULT_AGENT_EXECUTION_LIMITS: AgentExecutionLimits = Object.freeze({
  maxModelCalls: 16,
  maxToolCalls: 32,
  maxDurationMs: 300_000,
  maxModelOutputCharacters: 100_000,
  maxToolResultCharacters: 100_000,
});

/** DefaultAgentRuntime 的显式安全配置。 */
export interface DefaultAgentRuntimeOptions {
  /** 直接构造 Runtime 时必须完整提供的执行资源预算。 */
  readonly limits: AgentExecutionLimits;
  /** 自动压缩、工作消息构造与 Provider 用量推进的唯一上下文管理器。 */
  readonly contextManager: ContextManager;
  /** 可选标识生成器；测试和需要自定义追踪格式的宿主可注入。 */
  readonly createId?: (kind: RuntimeIdKind) => string;
  /** 可选单调毫秒时钟；共同用于绝对 Deadline、首 token 延迟与输出速度采样。 */
  readonly now?: () => number;
  /** 只读生命周期观察者，按数组顺序同步调用。 */
  readonly hooks?: readonly AgentHook[];
  /** Browser Model 调用拦截器，按数组顺序进入、反序退出。 */
  readonly modelInterceptors?: readonly ModelInterceptor[];
  /** Browser Tool 调用拦截器，按数组顺序进入、反序退出。 */
  readonly toolInterceptors?: readonly ToolInterceptor[];
  /**
   * 终态 Hook 失败的独立诊断出口。
   *
   * <p>省略时 Runtime 只在该异常路径使用 console.error 明确报告；诊断异常不能
   * 改写已经选定的 Execution 终态。普通 Hook 异常仍通过 result rejection 传播。
   */
  readonly onHookError?: (failure: AgentHookFailure) => void;
}

/** 单次 Tool 调度的稳定结果；null 由调用方专门表示 Execution 已取消。 */
interface ToolExecutionOutcome {
  /** 回填模型的文本内容。 */
  readonly content: string;
  /** Tool 业务是否失败。 */
  readonly isError: boolean;
}

/** 整批预检后固定的 Tool 调度条目，执行阶段不再重复查找或判断存在性。 */
interface PreparedToolCall {
  /** 模型生成且已通过唯一性检查的调用块。 */
  readonly call: ToolCallBlock;
  /** 与本轮 ToolRegistrySnapshot 同 revision 的准确执行定义。 */
  readonly definition: ToolDefinition;
}

/** 单次模型流的稳定结果与最终性能指标；逐增量计时不离开 Runtime。 */
interface ModelStreamOutcome {
  /** 已完成且通过协议校验的模型消息。 */
  readonly assembled: AssembledModelMessage;
  /** 调用开始到首个非空内容增量的耗时。 */
  readonly firstTokenLatencyMs: number;
  /** 首个非空内容增量到模型流完成的耗时。 */
  readonly outputDurationMs: number;
}

/** 当前挂起的中断响应槽；Execution 同一时刻最多等待一个 Tool。 */
interface PendingInterrupt {
  /** 等待响应的明确中断。 */
  readonly interrupt: ToolConfirmationInterrupt;
  /** 取消以 null 收敛，普通响应必须是 boolean。 */
  readonly resolve: (approved: boolean | null) => void;
}

/** 等待中的 Model、Tool 或确认被逻辑终态门抢先关闭时使用的内部哨兵。 */
const EXECUTION_STOPPED = Symbol('execution-stopped');

/** 终态门与普通异步操作竞速后的内部结果。 */
type ActiveOperationResult<T> = T | typeof EXECUTION_STOPPED;

/** 默认 AgentEngine：只拥有配置和活动 Execution 集合，不保存隐式当前 run。 */
export class DefaultAgentRuntime implements AgentEngine {
  /** 厂商中立模型端口。 */
  private readonly model: Model;
  /** 构造期校验并冻结的完整执行资源预算。 */
  private readonly limits: AgentExecutionLimits;
  /** 与 Controller 手动压缩共享同一配置和边界规则的上下文管理器。 */
  private readonly contextManager: ContextManager;
  /** 稳定标识生成器。 */
  private readonly createId: (kind: RuntimeIdKind) => string;
  /** 模型性能指标使用的单调毫秒时钟。 */
  private readonly now: () => number;
  /** 已组合的 Model 调用端口。 */
  private readonly invokeModel: ModelCallNext;
  /** 每次 Execution 固定使用的 Hook 快照。 */
  private readonly hooks: readonly AgentHook[];
  /** 终态 Hook 失败的独立诊断出口；不参与 Agent 生命周期广播。 */
  private readonly onHookError: ((failure: AgentHookFailure) => void) | undefined;
  /** 每次 Execution 固定使用的 Tool Interceptor 快照。 */
  private readonly toolInterceptors: readonly ToolInterceptor[];
  /** dispose 时需要统一取消的活动执行。 */
  private readonly activeExecutions = new Set<DefaultAgentExecution>();
  /** 已释放 Engine 禁止创建新执行。 */
  private disposed = false;

  /** 创建 Runtime，并在启动前校验有界循环配置。 */
  constructor(model: Model, options: DefaultAgentRuntimeOptions) {
    if (model == null) {
      throw new Error('model 不可为空');
    }
    if (options == null) {
      throw new Error('Runtime options 不可为空');
    }
    this.model = model;
    if (options.contextManager == null) {
      throw new Error('contextManager 不可为空');
    }
    this.contextManager = options.contextManager;
    this.limits = snapshotExecutionLimits(options.limits);
    this.createId = options.createId ?? defaultRuntimeId;
    this.now = options.now ?? defaultRuntimeNow;
    this.hooks = snapshotHooks(options.hooks ?? []);
    this.onHookError = options.onHookError;
    this.toolInterceptors = Object.freeze([...(options.toolInterceptors ?? [])]);
    this.invokeModel = composeModelInterceptors(
      options.modelInterceptors ?? [],
      (request, context, signal) => this.model.stream(request, context, signal),
    );
  }

  /** 创建绑定监听器的独立 Execution。 */
  start(
    input: AgentRunInput,
    listener: (event: AgentExecutionEvent) => void,
  ): AgentExecution {
    if (this.disposed) {
      throw new Error('AgentEngine 已释放，不能再启动新的执行');
    }
    let execution: DefaultAgentExecution;
    execution = new DefaultAgentExecution(
      this.invokeModel,
      input,
      listener,
      this.limits,
      this.contextManager,
      this.createId,
      this.now,
      this.hooks,
      this.onHookError,
      this.toolInterceptors,
      () => this.activeExecutions.delete(execution),
    );
    this.activeExecutions.add(execution);
    execution.begin();
    return execution;
  }

  /** 幂等释放 Runtime，并取消所有仍在等待模型、Tool 或用户响应的执行。 */
  dispose(): void {
    if (this.disposed) {
      return;
    }
    this.disposed = true;
    for (const execution of [...this.activeExecutions]) {
      execution.cancel();
    }
    this.activeExecutions.clear();
  }
}

/** 默认单次 Execution：独占 AbortController、中断槽和本轮稳定结果。 */
class DefaultAgentExecution implements AgentExecution {
  /** 标准取消源，同时传递到 Model 与 Tool。 */
  private readonly abortController = new AbortController();
  /** 当前 Human-in-the-loop 中断；未等待时为 null。 */
  private pendingInterrupt: PendingInterrupt | null = null;
  /** 最终结果在构造时启动，监听器已提前绑定。 */
  public readonly result: Promise<AgentRunResult>;
  /** 已绑定本轮 Tool 快照的拦截器调用链。 */
  private readonly invokeTool: ToolCallNext;
  /** 本轮已经通过完整预检并稳定提交的消息。 */
  private readonly stableMessages: AgentMessage[] = [];
  /** 与完整 working 消息严格对应的当前模型工作上下文。 */
  private stableModelContext: ModelContext;
  /** 输入上下文与本次 Execution 已接受的全部 Tool Call ID。 */
  private readonly acceptedToolCallIds = new Set<string>();
  /** 本次 Execution 已通过整批预检的 Tool Call 数量。 */
  private acceptedToolCallCount = 0;
  /** running 是唯一允许发布事件和发起副作用的状态。 */
  private lifecycle: 'running' | 'settling' | 'settled' = 'running';
  /**
   * 当前唯一活动 Model/Tool Promise 的停止入口。
   *
   * <p>Runtime 串行等待异步端口，因此同一时刻只能存在一个 waiter；操作先完成时
   * 必须注销该入口，避免共享未决 Promise 为每个模型事件永久保留 reaction。
   */
  private activeOperationStop: (() => void) | null = null;
  /** 对外结果的内部成功入口。 */
  private readonly resolveResult: (result: AgentRunResult) => void;
  /** 对外结果的内部失败入口。 */
  private readonly rejectResult: (cause: unknown) => void;
  /** 整体 Execution Deadline 定时器；任一终态都必须释放。 */
  private deadlineTimer: ReturnType<typeof setTimeout> | null = null;
  /** 基于注入单调时钟计算的绝对截止点；定时器只负责唤醒挂起操作。 */
  private readonly deadlineAt: number;
  /** 最近一次时钟样本；每个关键边界都拒绝回退或非有限值。 */
  private lastObservedNow: number;
  /** begin 只能由 Runtime 在登记活动集合后调用一次。 */
  private begun = false;

  /** 创建尚未发布事件的执行；Runtime 登记活动集合后必须调用 begin。 */
  constructor(
    private readonly invokeModel: ModelCallNext,
    private readonly input: AgentRunInput,
    private readonly listener: (event: AgentExecutionEvent) => void,
    private readonly limits: AgentExecutionLimits,
    private readonly contextManager: ContextManager,
    private readonly createId: (kind: RuntimeIdKind) => string,
    private readonly now: () => number,
    private readonly hooks: readonly AgentHook[],
    private readonly onHookError: ((failure: AgentHookFailure) => void) | undefined,
    toolInterceptors: readonly ToolInterceptor[],
    onSettled: () => void,
  ) {
    let resolveResult!: (result: AgentRunResult) => void;
    let rejectResult!: (cause: unknown) => void;
    const internalResult = new Promise<AgentRunResult>((resolve, reject) => {
      resolveResult = resolve;
      rejectResult = reject;
    });
    this.resolveResult = resolveResult;
    this.rejectResult = rejectResult;
    this.result = internalResult.finally(onSettled);
    this.lastObservedNow = readRuntimeNow(this.now);
    this.deadlineAt = this.lastObservedNow + this.limits.maxDurationMs;
    if (!Number.isFinite(this.deadlineAt)) {
      throw new Error('Runtime Deadline 必须是有限毫秒值');
    }
    this.stableModelContext = snapshotModelContext(input.conversation.modelContext);
    this.invokeTool = composeToolInterceptors(
      toolInterceptors,
      invocation => this.input.toolSnapshot.invoke(
        invocation.tool.name,
        invocation.arguments,
        invocation.context,
        invocation.signal,
      ),
    );
  }

  /**
   * 在 Runtime 已登记本 Execution 后启动生命周期。
   *
   * <p>两阶段启动保证 Hook 在 execution-started 中重入 engine.dispose() 时能够找到并
   * 取消当前 Execution；构造器不发布事件，避免“尚未纳管就开始执行”的时间窗。
   */
  begin(): void {
    if (this.begun) {
      throw new Error('Execution 已经启动，不能重复 begin');
    }
    this.begun = true;
    this.deadlineTimer = setTimeout(
      () => this.settleFailure(executionTimeoutError(this.limits.maxDurationMs)),
      this.limits.maxDurationMs,
    );
    try {
      this.publishHook({
        type: 'execution-started',
        initialMessageCount: this.input.conversation.messages.length,
        toolRevision: this.input.toolSnapshot.revision,
      });
      if (!this.isRunning()) {
        return;
      }
      void this.execute().then(
        result => this.settleSuccess(result, false),
        cause => this.settleFailure(cause),
      );
    } catch (cause) {
      this.settleFailure(cause);
    }
  }

  /** 只接受当前挂起中断的 boolean 响应，迟到或错误类型必须显式失败。 */
  respond(response: AgentInterruptResponse): void {
    if (!this.isRunning()) {
      throw new Error('当前 Execution 已进入终态，不能再响应中断');
    }
    const pending = this.pendingInterrupt;
    if (pending == null) {
      throw new Error('当前 Execution 没有等待响应的中断');
    }
    if (pending.interrupt.id !== response.interruptId) {
      throw new Error(`中断响应标识不匹配: ${response.interruptId}`);
    }
    if (typeof response.value !== 'boolean') {
      throw new Error('tool-confirmation 中断响应必须是 boolean');
    }
    this.pendingInterrupt = null;
    try {
      this.publishHook({
        type: 'interrupt-resolved',
        interruptId: response.interruptId,
        value: response.value,
      });
    } catch (cause) {
      pending.resolve(null);
      this.settleFailure(cause);
      return;
    }
    pending.resolve(response.value);
  }

  /** 幂等取消模型、Tool 和等待中的用户确认。 */
  cancel(): void {
    this.settleSuccess(this.snapshotResult({ type: 'cancelled' }), true);
  }

  /** 终态是否仍开放；所有观察事件和 Tool 副作用都以此为统一门。 */
  private isRunning(): boolean {
    return this.lifecycle === 'running';
  }

  /**
   * 以成功、截断或取消结果竞争唯一终态。
   *
   * <p>取消先同步关闭发布门，再触发 Abort 和当前活动 waiter；不合作的 Promise
   * 即使永不返回，也不能阻止 result 收敛或在稍后继续发布事件。
   */
  private settleSuccess(result: AgentRunResult, abortUpstream: boolean): void {
    if (!this.isRunning()) {
      return;
    }
    this.lifecycle = 'settling';
    if (abortUpstream) {
      this.stopPendingWork();
    }
    this.clearDeadline();
    this.publishTerminalHook({
      type: 'execution-completed',
      outcome: result.outcome,
      addedMessageCount: result.messages.length,
    });
    this.lifecycle = 'settled';
    this.resolveResult(result);
  }

  /** 以异常竞争唯一终态，并确保仍在等待的 Model、Tool 或确认立即失去发布资格。 */
  private settleFailure(cause: unknown): void {
    if (!this.isRunning()) {
      return;
    }
    this.lifecycle = 'settling';
    this.stopPendingWork();
    this.clearDeadline();
    const error = toLifecycleError(cause);
    this.publishTerminalHook({
      type: 'execution-failed',
      errorCode: error.code,
      errorMessage: error.message,
    });
    this.lifecycle = 'settled';
    this.rejectResult(cause);
  }

  /** 同步关闭协作 Abort、当前活动操作 waiter 和人工确认槽。 */
  private stopPendingWork(): void {
    this.activeOperationStop?.();
    if (!this.abortController.signal.aborted) {
      this.abortController.abort();
    }
    const pending = this.pendingInterrupt;
    this.pendingInterrupt = null;
    pending?.resolve(null);
  }

  /** 任一终态都清理 Deadline，避免已完成 Execution 继续占用事件循环。 */
  private clearDeadline(): void {
    if (this.deadlineTimer == null) {
      return;
    }
    clearTimeout(this.deadlineTimer);
    this.deadlineTimer = null;
  }

  /** 执行模型 → Tool → 模型有界循环，并只保留已完成稳定消息。 */
  private async execute(): Promise<AgentRunResult> {
    const signal = this.abortController.signal;
    const working: AgentMessage[] = this.input.conversation.messages.map(
      snapshotAgentMessage,
    );
    this.initializeHistoricalToolCallIds(working);
    const tools = toModelTools(this.input.toolSnapshot.tools);

    for (let modelCall = 1; modelCall <= this.limits.maxModelCalls; modelCall += 1) {
      if (!this.isRunning()) {
        return this.snapshotResult({ type: 'cancelled' });
      }
      this.requireWithinDeadline();
      const pendingConversation = {
        messages: working,
        modelContext: this.stableModelContext,
      };
      const requiresCompaction = this.contextManager.requiresAutomaticCompaction(
        pendingConversation,
      );
      if (requiresCompaction) {
        this.publishEvent({ type: 'status', status: 'compacting-context' });
      }
      const preparedContext = requiresCompaction
        ? await this.contextManager.prepareForModelCall({
          conversation: pendingConversation,
          callContext: {
            traceId: this.input.traceId,
            conversationId: this.input.conversationId,
          },
          signal,
        })
        : {
          conversation: pendingConversation,
          modelMessages: this.contextManager.buildModelMessages(pendingConversation),
        };
      if (!this.isRunning()) {
        return this.snapshotResult({ type: 'cancelled' });
      }
      this.stableModelContext = snapshotModelContext(
        preparedContext.conversation.modelContext,
      );
      // 摘要调用有独立服务端审计；普通模型性能计时必须从压缩完成后开始，
      // 否则首 token 延迟会混入另一笔模型调用的耗时。
      const modelCallStartedAt = this.requireWithinDeadline();
      const responseMessageId = this.nextId('message');
      this.publishHook({
        type: 'model-call-started',
        callIndex: modelCall,
        responseMessageId,
      });
      if (!this.isRunning()) {
        return this.snapshotResult({ type: 'cancelled' });
      }
      this.publishEvent({ type: 'status', status: 'streaming' });
      if (!this.isRunning()) {
        return this.snapshotResult({ type: 'cancelled' });
      }
      this.requireWithinDeadline();
      const streamed = await this.streamModel(
        preparedContext.modelMessages,
        this.stableModelContext.modelState,
        responseMessageId,
        tools,
        signal,
        modelCallStartedAt,
      );
      if (streamed == null) {
        return this.snapshotResult({ type: 'cancelled' });
      }
      this.requireWithinDeadline();
      const { assembled, firstTokenLatencyMs, outputDurationMs } = streamed;
      const assistantMessage = snapshotAgentMessage(assembled.message);
      const preparedTools = this.prepareToolBatch(assistantMessage, modelCall);

      this.stableMessages.push(assistantMessage);
      working.push(assistantMessage);
      this.stableModelContext = snapshotModelContext(
        this.contextManager.recordModelResponse(
          {
            messages: working,
            modelContext: this.stableModelContext,
          },
          assistantMessage,
          assembled.usage,
          assembled.modelState,
        ).modelContext,
      );
      this.publishHook({
        type: 'model-call-completed',
        callIndex: modelCall,
        responseMessageId,
        stopReason: assembled.stopReason,
        usage: assembled.usage,
        firstTokenLatencyMs,
        outputDurationMs,
      });
      if (!this.isRunning()) {
        return this.snapshotResult({ type: 'cancelled' });
      }
      this.requireWithinDeadline();
      this.publishMessages();
      if (!this.isRunning()) {
        return this.snapshotResult({ type: 'cancelled' });
      }
      this.requireWithinDeadline();

      if (preparedTools.length === 0) {
        return this.snapshotResult(toRunOutcome(assembled.stopReason));
      }

      this.publishEvent({ type: 'status', status: 'calling-tool' });
      if (!this.isRunning()) {
        return this.snapshotResult({ type: 'cancelled' });
      }
      this.requireWithinDeadline();
      for (const prepared of preparedTools) {
        if (!this.isRunning()) {
          return this.snapshotResult({ type: 'cancelled' });
        }
        const toolOutcome = await this.executeToolCall(prepared, signal);
        if (toolOutcome == null || !this.isRunning()) {
          return this.snapshotResult({ type: 'cancelled' });
        }
        this.requireWithinDeadline();
        const toolMessage = snapshotAgentMessage(toToolResultMessage(
          this.nextId('message'),
          prepared.call,
          toolOutcome,
        ));
        working.push(toolMessage);
        this.stableMessages.push(toolMessage);
        this.publishMessages();
      }
    }
    throw maxModelCallsError(this.limits.maxModelCalls);
  }

  /**
   * 发起一次结构化模型流并采集性能指标。
   *
   * <p>首 token 取第一个非空正文、思考或 Tool 参数增量；空增量不计时。
   * message-stop 是唯一协议完成边界，此后的取消或迭代器清理失败不得推翻稳定响应。
   * Hook 只收到完成后的耗时数值，不接触逐增量事件。
   */
  private async streamModel(
    messages: readonly AgentMessage[],
    modelState: ModelState | null,
    responseMessageId: string,
    tools: readonly ModelToolDefinition[],
    signal: AbortSignal,
    modelCallStartedAt: number,
  ): Promise<ModelStreamOutcome | null> {
    const assembler = new ModelMessageAssembler(
      this.limits.maxModelOutputCharacters,
    );
    let firstTokenAt: number | null = null;
    let modelCallCompletedAt: number | null = null;
    const request: ModelRequest = {
      // Model 可能异步消费请求；必须切断 Runtime 后续 push 对已发请求的反向修改。
      messages: Object.freeze([...messages]),
      modelState,
      responseMessageId,
      tools,
    };
    let iterator: AsyncIterator<ModelStreamEvent> | null = null;
    try {
      this.requireWithinDeadline();
      iterator = this.invokeModel(
        request,
        {
          traceId: this.input.traceId,
          conversationId: this.input.conversationId,
        },
        signal,
      )[Symbol.asyncIterator]();
      for (;;) {
        if (!this.isRunning()) {
          closeModelIteratorDetached(iterator);
          return null;
        }
        this.requireWithinDeadline();
        const next = await this.waitForActiveOperation(iterator.next());
        if (next === EXECUTION_STOPPED || !this.isRunning()) {
          closeModelIteratorDetached(iterator);
          return null;
        }
        if (next.done) {
          break;
        }
        const eventReceivedAt = this.requireWithinDeadline();
        const event = next.value;
        assembler.consume(event);
        if (firstTokenAt == null && hasNonEmptyModelDelta(event)) {
          firstTokenAt = eventReceivedAt;
        }
        this.publishModelDelta(event);
        if (event.type === 'message-stop') {
          modelCallCompletedAt = eventReceivedAt;
          closeCompletedModelIteratorDetached(iterator);
          break;
        }
      }
    } catch (cause) {
      if (iterator != null) {
        closeModelIteratorDetached(iterator);
      }
      if (!this.isRunning()) {
        return null;
      }
      throw cause;
    }
    const assembled = assembler.assemble(responseMessageId);
    if (firstTokenAt == null || modelCallCompletedAt == null) {
      throw {
        code: 'MODEL_PROTOCOL_ERROR',
        message: '模型完成但没有产生可计时的非空内容增量',
        retryable: false,
      } satisfies AgentError;
    }
    return Object.freeze({
      assembled,
      firstTokenLatencyMs: elapsedMillis(modelCallStartedAt, firstTokenAt),
      outputDurationMs: elapsedMillis(firstTokenAt, modelCallCompletedAt),
    });
  }

  /** 执行一个稳定 Tool Call，包含明确中断、快照路由与错误回填。 */
  private async executeToolCall(
    prepared: PreparedToolCall,
    signal: AbortSignal,
  ): Promise<ToolExecutionOutcome | null> {
    const { call: toolCall, definition } = prepared;
    this.requireWithinDeadline();
    this.publishEvent({
      type: 'tool-call',
      toolCallId: toolCall.callId,
      toolName: toolCall.name,
      arguments: toolCall.input,
    });
    this.publishHook({
      type: 'tool-call-started',
      callId: toolCall.callId,
      toolName: toolCall.name,
      arguments: toolCall.input,
    });
    if (!this.isRunning()) {
      return null;
    }
    this.requireWithinDeadline();

    if (definition.annotations?.requireConfirmation) {
      const approved = await this.requestConfirmation(definition, toolCall.input);
      if (approved == null || !this.isRunning()) {
        return null;
      }
      this.requireWithinDeadline();
      if (!approved) {
        return this.toolFailure(toolCall, '用户拒绝了本次操作，未执行');
      }
    }

    let result: ToolCallResult;
    try {
      this.requireWithinDeadline();
      const operation = this.invokeTool({
        tool: definition,
        arguments: toolCall.input,
        context: {
          traceId: this.input.traceId,
          conversationId: this.input.conversationId,
          toolCallId: toolCall.callId,
        },
        signal,
      });
      const completed = await this.waitForActiveOperation(operation);
      if (completed === EXECUTION_STOPPED || !this.isRunning()) {
        return null;
      }
      result = snapshotToolCallResult(completed, toolCall.callId, toolCall.name);
    } catch (cause) {
      if (!this.isRunning()) {
        return null;
      }
      // Promise rejection 表示 Adapter、Interceptor 或基础设施失败，不能伪装成业务结果。
      throw cause;
    }
    this.requireWithinDeadline();
    assertToolResultLimit(result.content, this.limits.maxToolResultCharacters);
    // Hook 是观察端口：其异常必须终止 Execution，不能被误报成 Tool 业务失败。
    this.publishToolResult(toolCall, result);
    this.publishHook({
      type: 'tool-call-completed',
      callId: toolCall.callId,
      toolName: toolCall.name,
      isError: result.isError,
    });
    return { content: result.content, isError: result.isError };
  }

  /** 发布 Tool 确认中断，并等待当前 Execution 的精确响应。 */
  private requestConfirmation(
    tool: ToolDefinition,
    arguments_: JsonObject,
  ): Promise<boolean | null> {
    this.requireWithinDeadline();
    return new Promise<boolean | null>(resolve => {
      const interrupt: ToolConfirmationInterrupt = {
        id: this.nextId('interrupt'),
        type: 'tool-confirmation',
        tool,
        arguments: arguments_,
      };
      this.pendingInterrupt = { interrupt, resolve };
      this.publishHook({ type: 'interrupt-requested', interrupt });
      this.publishEvent({ type: 'interrupt', interrupt });
    });
  }

  /** 统一生成 Tool 失败结果和观察事件，保证拒绝与业务失败语义一致。 */
  private toolFailure(
    toolCall: ToolCallBlock,
    reason: string,
  ): ToolExecutionOutcome {
    const content = `工具 ${toolCall.name} 未执行：${reason}`;
    assertToolResultLimit(content, this.limits.maxToolResultCharacters);
    this.publishEvent({
      type: 'tool-result',
      toolCallId: toolCall.callId,
      toolName: toolCall.name,
      content,
      isError: true,
    });
    this.publishHook({
      type: 'tool-call-completed',
      callId: toolCall.callId,
      toolName: toolCall.name,
      isError: true,
    });
    return { content, isError: true };
  }

  /** 发布正常 Tool 结果观察事件。 */
  private publishToolResult(toolCall: ToolCallBlock, result: ToolCallResult): void {
    this.publishEvent({
      type: 'tool-result',
      toolCallId: toolCall.callId,
      toolName: toolCall.name,
      content: result.content,
      isError: result.isError,
    });
  }

  /** 只把可展示的文本和思考增量交给 Controller。 */
  private publishModelDelta(event: ModelStreamEvent): void {
    if (event.type !== 'block-delta') {
      return;
    }
    if (event.delta.type === 'text') {
      this.publishEvent({ type: 'text-delta', text: event.delta.text });
    } else if (event.delta.type === 'reasoning') {
      this.publishEvent({ type: 'reasoning-delta', text: event.delta.text });
    }
  }

  /** 发布与内部数组引用隔离的本轮稳定消息快照。 */
  private publishMessages(): void {
    this.publishEvent({
      type: 'messages',
      messages: [...this.stableMessages],
      modelContext: snapshotModelContext(this.stableModelContext),
    });
  }

  /**
   * 扫描输入上下文里的 Tool Call ID，建立本轮跨消息唯一性基线。
   *
   * <p>Tool Result 只是对既有 ID 的引用，不会再次加入集合；历史本身已经重复或
   * 含空 ID 时直接按模型协议错误失败，禁止把损坏上下文继续发给 Provider。
   */
  private initializeHistoricalToolCallIds(messages: readonly AgentMessage[]): void {
    for (const message of messages) {
      for (const block of message.blocks) {
        if (block.type !== 'tool-call') {
          continue;
        }
        if (block.callId.trim().length === 0) {
          throw modelProtocolError('历史上下文包含空 Tool Call ID');
        }
        if (this.acceptedToolCallIds.has(block.callId)) {
          throw modelProtocolError(`历史上下文包含重复 Tool Call ID: ${block.callId}`);
        }
        this.acceptedToolCallIds.add(block.callId);
      }
    }
  }

  /**
   * 在整批 Tool 产生任何观察事件或业务副作用前完成全部 Execution 级预检。
   *
   * <p>Assembler 已保证参数是完整 JSON 对象和 stopReason 一致；本方法只处理需要
   * Tool 快照、历史上下文和整轮计数才能判断的不变量。全部通过后才整体预占 ID
   * 与次数，执行阶段因此无需再出现逐 Tool 的存在性分支。
   */
  private prepareToolBatch(
    assistantMessage: AgentMessage,
    modelCallIndex: number,
  ): readonly PreparedToolCall[] {
    const calls = assistantMessage.blocks.filter(
      (block): block is ToolCallBlock => block.type === 'tool-call',
    );
    if (calls.length === 0) {
      return Object.freeze([]);
    }
    const definitions = new Map(
      this.input.toolSnapshot.tools.map(tool => [tool.name, tool] as const),
    );
    const batchIds = new Set<string>();
    const prepared: PreparedToolCall[] = [];
    for (const call of calls) {
      if (call.callId.trim().length === 0) {
        throw modelProtocolError('模型返回了空 Tool Call ID');
      }
      if (batchIds.has(call.callId) || this.acceptedToolCallIds.has(call.callId)) {
        throw modelProtocolError(`Tool Call ID 在上下文中重复: ${call.callId}`);
      }
      const definition = definitions.get(call.name);
      if (definition == null) {
        throw modelProtocolError(`模型调用了本轮未声明的 Tool: ${call.name}`);
      }
      if (call.input == null || typeof call.input !== 'object' || Array.isArray(call.input)) {
        throw modelProtocolError(`Tool ${call.name} 的参数必须是完整 JSON 对象`);
      }
      batchIds.add(call.callId);
      prepared.push(Object.freeze({ call, definition }));
    }
    if (this.acceptedToolCallCount + prepared.length > this.limits.maxToolCalls) {
      throw maxToolCallsError(this.limits.maxToolCalls);
    }
    if (modelCallIndex >= this.limits.maxModelCalls) {
      throw maxModelCallsError(this.limits.maxModelCalls);
    }
    for (const callId of batchIds) {
      this.acceptedToolCallIds.add(callId);
    }
    this.acceptedToolCallCount += prepared.length;
    return Object.freeze(prepared);
  }

  /** 用稳定消息与模型工作上下文快照创建不可由宿主改写的最终结果。 */
  private snapshotResult(outcome: AgentRunOutcome): AgentRunResult {
    return Object.freeze({
      messages: Object.freeze([...this.stableMessages]),
      modelContext: snapshotModelContext(this.stableModelContext),
      outcome: Object.freeze({ ...outcome }) as AgentRunOutcome,
    });
  }

  /**
   * 让一个不合作异步操作与当前 Execution 终态门竞速，并在操作先完成时注销 waiter。
   *
   * <p>Agent Loop 严格串行等待 Model/Tool，同一时刻出现第二个 waiter 表示 Runtime
   * 编程错误，必须明确失败而不是覆盖前一个停止入口。每次操作使用独立短生命周期
   * Promise，完成后不再被 Execution 字段引用，因此不会按模型事件数累积 reaction。
   */
  private async waitForActiveOperation<T>(
    operation: Promise<T>,
  ): Promise<ActiveOperationResult<T>> {
    if (!this.isRunning()) {
      // operation 可能在求值时重入终态；安装 rejection 处理以隔离它的迟到失败。
      void operation.catch(() => undefined);
      return EXECUTION_STOPPED;
    }
    if (this.activeOperationStop != null) {
      void operation.catch(() => undefined);
      throw new Error('Runtime 同一时刻只能等待一个活动 Model 或 Tool 操作');
    }
    let resolveStopped!: (result: typeof EXECUTION_STOPPED) => void;
    const stopped = new Promise<typeof EXECUTION_STOPPED>(resolve => {
      resolveStopped = resolve;
    });
    const stop = (): void => resolveStopped(EXECUTION_STOPPED);
    this.activeOperationStop = stop;
    if (!this.isRunning()) {
      stop();
    }
    try {
      return await Promise.race([operation, stopped]);
    } finally {
      if (this.activeOperationStop === stop) {
        this.activeOperationStop = null;
      }
    }
  }

  /**
   * 在关键异步边界读取单调时钟并执行 Deadline 判定。
   *
   * <p>定时器负责唤醒永不返回的 Model/Tool；本方法负责处理持续产生已就绪
   * Promise 的微任务饥饿场景。两者共用同一终态错误，不形成第二套超时语义。
   */
  private requireWithinDeadline(): number {
    const current = readRuntimeNow(this.now);
    if (current < this.lastObservedNow) {
      throw new Error('Runtime now 时钟必须返回不回退的有限毫秒值');
    }
    this.lastObservedNow = current;
    if (current >= this.deadlineAt) {
      throw executionTimeoutError(this.limits.maxDurationMs);
    }
    return current;
  }

  /** 发布普通 Execution 事件；终态竞争开始后所有迟到事件直接隔离。 */
  private publishEvent(event: AgentExecutionEvent): void {
    if (!this.isRunning()) {
      return;
    }
    this.listener(event);
  }

  /** 调用注入生成器并拒绝空 ID，防止持久化后无法关联状态。 */
  private nextId(kind: RuntimeIdKind): string {
    const id = this.createId(kind);
    if (id.trim().length === 0) {
      throw new Error(`${kind} ID 生成器返回了空值`);
    }
    return id;
  }

  /** 按注册顺序同步发布只读 Hook 事件。 */
  private publishHook(event: AgentLifecycleEvent): void {
    if (!this.isRunning()) {
      return;
    }
    this.publishHooks(event);
  }

  /** 发布生命周期事件；终态事件由 settle 方法在关闭普通发布门后使用。 */
  private publishTerminalHook(event: AgentTerminalLifecycleEvent): void {
    const context = this.hookContext();
    const frozenEvent = freezeLifecycleEvent(event);
    this.hooks.forEach((hook, hookIndex) => {
      try {
        hook.onEvent(frozenEvent, context);
      } catch (cause) {
        this.reportTerminalHookFailure(Object.freeze({
          hookIndex,
          event: frozenEvent,
          context,
          cause,
        }));
      }
    });
  }

  /**
   * 向固定 Hook 快照发布普通事件，并尊重 Hook 内重入终态。
   *
   * <p>普通事件若前一个 Hook 触发取消，后续 Hook 不得在
   * cancelled 之后再看到迟到的 started/completed 事实。
   */
  private publishHooks(event: AgentLifecycleEvent): void {
    const context = this.hookContext();
    const frozenEvent = freezeLifecycleEvent(event);
    for (const hook of this.hooks) {
      if (!this.isRunning()) {
        return;
      }
      hook.onEvent(frozenEvent, context);
    }
  }

  /** 创建一次 Hook 广播共享的冻结执行上下文。 */
  private hookContext(): AgentHookContext {
    return Object.freeze({
      traceId: this.input.traceId,
      conversationId: this.input.conversationId,
    });
  }

  /**
   * 报告终态 Hook 失败且绝不把 diagnostics 变成第二条 Execution 结果通道。
   *
   * <p>console.error 只服务“终态观察者或其 diagnostics 违约”这一异常路径，
   * 不属于业务降级、静默容错或正常运行日志。即使自定义 diagnostics 再次抛错，
   * 已经选定的终态仍必须继续交付给剩余 Hook 和 result。
   */
  private reportTerminalHookFailure(failure: AgentHookFailure): void {
    if (this.onHookError == null) {
      reportHookFailureToConsole(failure);
      return;
    }
    try {
      this.onHookError(failure);
    } catch (diagnosticsCause) {
      reportHookDiagnosticsFailureToConsole(failure, diagnosticsCause);
    }
  }
}

/**
 * 为观察型 Hook 建立独立深冻结事件。
 *
 * <p>Tool 参数随后还要交给执行器，Hook 绝不能通过嵌套对象引用改写真实调用；
 * Tool 定义也在观察边界复制，确保任何合法 Port 实现都无法通过对象别名修改当前 Execution。
 */
function freezeLifecycleEvent<T extends AgentLifecycleEvent>(event: T): T {
  if (event.type === 'execution-completed') {
    return Object.freeze({
      ...event,
      outcome: Object.freeze({ ...event.outcome }),
    }) as T;
  }
  if (event.type === 'tool-call-started') {
    return Object.freeze({
      ...event,
      arguments: copyAndFreezeJsonObject(event.arguments),
    }) as T;
  }
  if (event.type === 'interrupt-requested') {
    return Object.freeze({
      ...event,
      interrupt: Object.freeze({
        ...event.interrupt,
        tool: copyAndFreezeToolDefinition(event.interrupt.tool),
        arguments: copyAndFreezeJsonObject(event.interrupt.arguments),
      }),
    }) as T;
  }
  return Object.freeze({ ...event }) as T;
}

/** 复制并冻结 Hook 可见的 ToolDefinition，切断它与执行快照的嵌套引用。 */
function copyAndFreezeToolDefinition(tool: ToolDefinition): ToolDefinition {
  const annotations = tool.annotations == null
    ? null
    : Object.freeze({ ...tool.annotations });
  return Object.freeze({
    ...tool,
    inputSchema: copyAndFreezeJsonObject(tool.inputSchema),
    annotations,
    permissions: Object.freeze([...tool.permissions]),
  });
}

/** ToolRegistrySnapshot 的公开定义映射成最小 Model Tool 端口。 */
function toModelTools(tools: readonly ToolDefinition[]): readonly ModelToolDefinition[] {
  return Object.freeze(tools.map(tool => Object.freeze({
    name: tool.name,
    description: tool.description,
    inputSchema: tool.inputSchema,
  })));
}

/** 把 Tool 调用结果包装成厂商中立 ToolResultBlock 消息。 */
function toToolResultMessage(
  messageId: string,
  toolCall: ToolCallBlock,
  outcome: ToolExecutionOutcome,
): AgentMessage {
  return {
    id: messageId,
    role: 'tool',
    blocks: [{
      type: 'tool-result',
      callId: toolCall.callId,
      name: toolCall.name,
      status: outcome.isError ? 'error' : 'success',
      content: [{ type: 'text', text: outcome.content }],
    }],
  };
}

/**
 * 严格校验并冻结 Adapter 返回的 ToolCallResult。
 *
 * <p>HTTP JSON 和宿主 Adapter 都是运行时边界，TypeScript readonly 不能保证真实值。
 * 本方法拒绝自定义原型、访问器、未知字段、错误字段类型和关联 ID 漂移；只有完成
 * 该快照后，isError 才有资格被解释为可继续 Agent Loop 的业务失败。
 */
function snapshotToolCallResult(
  value: unknown,
  expectedCallId: string,
  toolName: string,
): ToolCallResult {
  if (value == null || typeof value !== 'object' || Array.isArray(value)) {
    throw modelProtocolError(`Tool ${toolName} 返回结果必须是对象`);
  }
  let prototype: object | null;
  let descriptors: PropertyDescriptorMap;
  try {
    prototype = Object.getPrototypeOf(value) as object | null;
    descriptors = Object.getOwnPropertyDescriptors(value);
  } catch {
    throw modelProtocolError(`Tool ${toolName} 返回结果无法读取`);
  }
  if (prototype !== Object.prototype && prototype !== null) {
    throw modelProtocolError(`Tool ${toolName} 返回结果不能包含自定义原型`);
  }
  const keys = Reflect.ownKeys(descriptors);
  const expectedKeys = ['toolCallId', 'content', 'isError'] as const;
  if (keys.length !== expectedKeys.length
    || expectedKeys.some(key => !Object.prototype.hasOwnProperty.call(descriptors, key))) {
    throw modelProtocolError(
      `Tool ${toolName} 返回结果字段必须精确为 toolCallId、content、isError`,
    );
  }
  const descriptorValues = expectedKeys.map(key => {
    const descriptor = descriptors[key];
    if (descriptor == null || !Object.prototype.hasOwnProperty.call(descriptor, 'value')) {
      throw modelProtocolError(`Tool ${toolName} 返回结果字段 ${key} 不能是访问器`);
    }
    return descriptor.value as unknown;
  });
  const [toolCallId, content, isError] = descriptorValues;
  if (typeof toolCallId !== 'string' || toolCallId !== expectedCallId) {
    throw modelProtocolError(`Tool ${toolName} 返回的 toolCallId 与当前调用不匹配`);
  }
  if (typeof content !== 'string') {
    throw modelProtocolError(`Tool ${toolName} 返回的 content 必须是字符串`);
  }
  if (typeof isError !== 'boolean') {
    throw modelProtocolError(`Tool ${toolName} 返回的 isError 必须是 boolean`);
  }
  return Object.freeze({ toolCallId, content, isError });
}

/**
 * 复制并校验 Runtime 的完整资源预算。
 *
 * <p>所有计数都参与数组、字符串或定时器边界，必须是安全正整数；Duration 还受
 * 浏览器单次 setTimeout 的 32 位上限约束，超出时拒绝而不是被平台隐式改成 1ms。
 */
function snapshotExecutionLimits(limits: AgentExecutionLimits): AgentExecutionLimits {
  if (limits == null) {
    throw new Error('limits 不可为空');
  }
  const values: ReadonlyArray<readonly [keyof AgentExecutionLimits, number]> = [
    ['maxModelCalls', limits.maxModelCalls],
    ['maxToolCalls', limits.maxToolCalls],
    ['maxDurationMs', limits.maxDurationMs],
    ['maxModelOutputCharacters', limits.maxModelOutputCharacters],
    ['maxToolResultCharacters', limits.maxToolResultCharacters],
  ];
  for (const [name, value] of values) {
    if (!Number.isSafeInteger(value) || value <= 0) {
      throw new Error(`${name} 必须是安全正整数`);
    }
  }
  if (limits.maxDurationMs > 2_147_483_647) {
    throw new Error('maxDurationMs 超出浏览器定时器支持范围');
  }
  return Object.freeze({ ...limits });
}

/** message-stop 或逻辑终态后发起非阻塞迭代器清理，迟到清理失败不改写稳定结果。 */
function closeModelIteratorDetached(iterator: AsyncIterator<ModelStreamEvent>): void {
  if (iterator.return == null) {
    return;
  }
  try {
    void Promise.resolve(iterator.return()).catch(() => {
      // 清理发生在协议消息或 Execution 已封闭之后，没有第二条错误通道。
    });
  } catch {
    // 同步清理失败同样不能推翻已线性化的消息或 Execution 终态。
  }
}

/**
 * 在稳定 message-stop 后把迭代器清理推迟到下一宏任务。
 *
 * <p>模型消息已经线性化，当前微任务仍需完成聚合、预检和终态竞争；如果立即调用
 * return，某些 Adapter 会在 generator finally 中同步触发取消或清理异常，使迟到的
 * 传输生命周期反向推翻稳定结果。宏任务只延后清理入口，不等待不合作的 return。
 */
function closeCompletedModelIteratorDetached(
  iterator: AsyncIterator<ModelStreamEvent>,
): void {
  setTimeout(() => closeModelIteratorDetached(iterator), 0);
}

/** 把无 Tool 的合法停止原因映射为唯一 Execution Outcome。 */
function toRunOutcome(stopReason: AssembledModelMessage['stopReason']): AgentRunOutcome {
  switch (stopReason) {
    case 'max-tokens':
      return { type: 'max-tokens' };
    case 'end-turn':
    case 'stop-sequence':
    case 'other':
      return { type: 'completed', stopReason };
    case 'tool-use':
      throw modelProtocolError('tool-use 响应缺少可调度的 Tool Call');
  }
}

/** Tool 结果在任何事件、Hook、消息或下一次模型调用前执行字符预算检查。 */
function assertToolResultLimit(content: string, limit: number): void {
  if (content.length <= limit) {
    return;
  }
  throw {
    code: 'TOOL_RESULT_LIMIT_EXCEEDED',
    message: `Tool 结果字符数超过上限 ${limit}`,
    retryable: false,
  } satisfies AgentError;
}

/** 仅非空内容增量算首 token；协议壳事件和空增量不能提前计时。 */
function hasNonEmptyModelDelta(event: ModelStreamEvent): boolean {
  if (event.type !== 'block-delta') {
    return false;
  }
  return event.delta.type === 'tool-call'
    ? event.delta.argumentsDelta.length > 0
    : event.delta.text.length > 0;
}

/** 计算单调时钟差；注入的时钟回退属于配置错误，必须明确失败。 */
function elapsedMillis(startedAt: number, endedAt: number): number {
  const elapsed = endedAt - startedAt;
  if (!Number.isFinite(elapsed) || elapsed < 0) {
    throw new Error('Runtime now 时钟必须返回不回退的有限毫秒值');
  }
  return elapsed;
}

/** 读取一次有限单调时钟样本；跨样本的回退校验由 Execution 统一维护。 */
function readRuntimeNow(now: () => number): number {
  const current = now();
  if (!Number.isFinite(current)) {
    throw new Error('Runtime now 时钟必须返回不回退的有限毫秒值');
  }
  return current;
}

/** 默认使用高精度单调时钟，避免系统时间校准造成负耗时。 */
function defaultRuntimeNow(): number {
  return globalThis.performance.now();
}

/** 默认使用浏览器安全随机 UUID；运行环境缺失时应明确失败而不是生成弱标识。 */
function defaultRuntimeId(kind: RuntimeIdKind): string {
  return `${kind}-${globalThis.crypto.randomUUID()}`;
}

/** 构造达到模型调用上限的稳定不可重试错误。 */
function maxModelCallsError(maxModelCalls: number): AgentError {
  return {
    code: 'AGENT_MAX_MODEL_CALLS',
    message: `模型调用次数达到上限 ${maxModelCalls}，已终止以防止无限循环`,
    retryable: false,
  };
}

/** 构造整批 Tool 数量达到上限的稳定不可重试错误。 */
function maxToolCallsError(maxToolCalls: number): AgentError {
  return {
    code: 'AGENT_MAX_TOOL_CALLS',
    message: `Tool 调用次数将超过上限 ${maxToolCalls}，整批未执行`,
    retryable: false,
  };
}

/** 构造整个 Execution Deadline 到达的稳定不可重试错误。 */
function executionTimeoutError(maxDurationMs: number): AgentError {
  return {
    code: 'AGENT_EXECUTION_TIMEOUT',
    message: `Agent 执行超过时限 ${maxDurationMs}ms，已终止`,
    retryable: false,
  };
}

/** 构造 Runtime 级模型协议错误，不把 Provider 违约伪装成可重试网络失败。 */
function modelProtocolError(message: string): AgentError {
  return {
    code: 'MODEL_PROTOCOL_ERROR',
    message,
    retryable: false,
  };
}

/**
 * 使用浏览器控制台报告未配置 diagnostics 时的终态 Hook 失败。
 *
 * <p>该出口只会在扩展违约路径执行，不属于正常日志或业务降级。控制台本身若被宿主
 * 替换为会抛错的实现，也不能阻止已线性化的 Execution 终态完成。
 */
function reportHookFailureToConsole(failure: AgentHookFailure): void {
  try {
    console.error('Agent Runtime 终态 Hook 执行失败；已保留原终态', failure);
  } catch {
    // console 是无自定义 diagnostics 时的最后观察出口，其自身违约不能成为第二终态。
  }
}

/**
 * 报告自定义 Hook diagnostics 自身抛错，保留原 Hook 失败便于宿主同时定位两层问题。
 *
 * <p>这里同样只处理扩展违约，绝不把 diagnostics 失败传播回已经选定的业务终态。
 */
function reportHookDiagnosticsFailureToConsole(
  failure: AgentHookFailure,
  diagnosticsCause: unknown,
): void {
  try {
    console.error(
      'Agent Runtime Hook diagnostics 执行失败；已保留原终态',
      Object.freeze({ failure, diagnosticsCause }),
    );
  } catch {
    // diagnostics 与 console 同时违约时已无第三条安全观察通道，只能保护终态完成。
  }
}

/** 把未知失败投影成 Hook 可观察的稳定错误，且不改变原异常传播。 */
function toLifecycleError(cause: unknown): AgentError {
  if (cause != null
    && typeof cause === 'object'
    && typeof (cause as Partial<AgentError>).code === 'string'
    && typeof (cause as Partial<AgentError>).message === 'string'
    && typeof (cause as Partial<AgentError>).retryable === 'boolean') {
    return cause as AgentError;
  }
  return {
    code: 'AGENT_EXECUTION_FAILED',
    message: cause instanceof Error ? cause.message : 'Agent 执行失败',
    retryable: false,
  };
}
