/**
 * AgentController：Browser 层唯一 Application Service（设计文档第 4 节）。
 *
 * <p>承担 AgentState 所有权、会话导航、消息发送、Tool Confirmation、
 * 状态转换与保存时序；不承担 DOM、SSE 协议、Tool HTTP 细节。
 *
 * <p>并发模型（Latest Request Wins，设计文档第 10、11 节）：
 * <ul>
 *   <li>Navigation（initialize / refresh / load）用 navigationGeneration +
 *       navigationAbort 双保险——Abort 用于停止工作，Generation 用于阻止旧结果提交；</li>
 *   <li>Agent Run 用 runGeneration + runActive 双保险：Abort 负责停止工作，
 *       Generation 覆盖模型执行结束后的异步保存阶段，旧结果不能提交到新会话；</li>
 *   <li>sendMessage 前取消未完成 navigation，loadConversation / startNew 前
 *       abort 当前 run——两个域互斥，避免旧会话的结果写进新会话。</li>
 * </ul>
 */
import type { ConversationClient } from './clients/conversationClient';
import type { ModelTargetClient } from './clients/modelTargetClient';
import { sameTarget } from './clients/modelTargetClient';
import { toModelTools } from './runtime';
import type { ContextManager } from './contextManager';
import type {
  AgentEngine,
  AgentExecution,
  AgentExecutionEvent,
} from './engine';
import {
  conversationConflictError,
  invalidStateError,
} from './errors';
import {
  createInitialAgentState,
  reduceAgentState,
} from './stateMachine';
import type { AgentStateEvent } from './stateMachine';
import type { BrowserTool, ToolRegistration, ToolRegistry } from './toolRegistry';
import {
  ExecutionAwareToolInspectionSource,
  type ToolInspectionSource,
} from './toolInspection';
import { CallTraceStore, disabledCallTraceSource, type CallTraceSource } from './callTrace';
import type {
  AgentError,
  AgentMessage,
  AgentState,
  AgentStatus,
  ContentBlock,
  ImageAttachment,
  ModelTargetRef,
} from './types';
import {
  snapshotAgentMessage,
  snapshotConversationContext,
  snapshotModelContext,
} from './messageValues';

/** Controller 依赖注入项：engine 与统一 Tool Registry 在同一应用生命周期内共享。 */
export interface AgentControllerOptions {
  engine: AgentEngine;
  conversations: ConversationClient;
  /** 目录、草稿及保存会话切换共用的服务端端口。 */
  modelTargets: ModelTargetClient;
  tools: ToolRegistry;
  /** 自动与手动压缩共享的模型上下文管理器。 */
  contextManager: ContextManager;
  /** localStorage 缓存键前缀（仅缓存“上次打开的会话 ID”，属 UX Cache 而非真相）。 */
  storageKey?: string;
  /**
   * 注入已注册为 Runtime Hook 的轨迹存储；工厂装配默认 Runtime 时必传同一实例。
   * 缺省为 null：不采集轨迹，getCallTraceSource 返回显式空数据源——
   * 采集是显式决策，Controller 不再自建隐式实例。
   */
  callTrace?: CallTraceStore | null;
}

/** 默认 Browser Application Service：协调依赖并把所有状态转换交给纯 reducer。 */
export class DefaultAgentController implements PatchBridgeAgentController {
  /** 可替换的 Agent 执行端口。 */
  private readonly engine: AgentEngine;
  /** 会话列表与原子 Context 持久化端口。 */
  private readonly conversations: ConversationClient;
  /** 统一模型目标目录和显式切换入口。 */
  private readonly modelTargets: ModelTargetClient;
  /** 所有 Tool 来源与执行路由的唯一 Registry。 */
  private readonly tools: ToolRegistry;
  /** 模型窗口配置、边界选择和摘要调用的统一入口。 */
  private readonly contextManager: ContextManager;
  /** 只读调试端口：执行中钉住本轮快照，空闲时跟随 Registry。 */
  private readonly toolInspection: ExecutionAwareToolInspectionSource;
  /** 调用轨迹端口；null 表示采集未启用，对外只暴露显式空源。 */
  private readonly callTrace: CallTraceStore | null;
  /** 仅保存上次会话 ID 的浏览器 UX 缓存键。 */
  private readonly storageKey: string;

  /** 只允许由 dispatch 调用纯函数状态机替换，异步流程不得直接修改。 */
  private state: AgentState = createInitialAgentState();

  /** AgentState 只读观察者；不能直接改写内部状态。 */
  private readonly listeners = new Set<(state: AgentState) => void>();

  /** Navigation 令牌：只有最新一代允许提交会话导航结果。 */
  private navigationGeneration = 0;
  private navigationAbort: AbortController | null = null;

  /** Run 令牌：会话切换或新 run 会推进代次，覆盖无法取消的保存请求。 */
  private runGeneration = 0;
  /** Run 哨兵：为 true 时才接收当前 Engine 的事件。 */
  private runActive = false;
  /** 当前 run 的消息基线（历史 + 本轮用户消息），messages 快照相对它拼接。 */
  private runBaseMessages: readonly AgentMessage[] = [];
  /** 当前运行句柄；取消与中断响应只能作用于该 Execution。 */
  private currentExecution: AgentExecution | null = null;

  /** 手动压缩独立取消源；生成中的摘要可以停止，进入保存后只允许导航作废提交。 */
  private contextCompactionAbort: AbortController | null = null;
  /** 手动压缩代次：导航、释放或后续操作可阻止迟到结果提交。 */
  private contextCompactionGeneration = 0;

  /** 标识 Controller 是否已经释放。 */
  private disposed = false;

  /** 注入 Application Service 的全部端口，不在内部定位全局依赖。 */
  constructor(options: AgentControllerOptions) {
    this.engine = options.engine;
    this.conversations = options.conversations;
    this.modelTargets = options.modelTargets;
    this.tools = options.tools;
    this.contextManager = options.contextManager;
    this.toolInspection = new ExecutionAwareToolInspectionSource(options.tools);
    this.callTrace = options.callTrace ?? null;
    this.storageKey = options.storageKey ?? 'patchbridge-agent:last-conversation';
    // 轨迹展示跟随唯一状态源的会话身份：把自身的会话切换同步到轨迹存储，
    // 避免在每个导航分支里重复接线（统一入口原则）。
    this.subscribe(state =>
      this.callTrace?.setActiveConversation(
        state.conversation?.conversationId ?? null,
      ),
    );
  }

  /** 返回与内部数组引用隔离的状态快照。 */
  getState(): AgentState {
    // 快照隔离：UI 拿到的数组引用不能与内部状态共享（设计文档 4.3）
    return {
      ...this.state,
      conversations: [...this.state.conversations],
      modelTargets: [...this.state.modelTargets],
      messages: [...this.state.messages],
    };
  }

  /** 订阅时立即发布当前状态，返回幂等取消函数。 */
  subscribe(listener: (state: AgentState) => void): () => void {
    this.listeners.add(listener);
    // 订阅即推送当前状态：UI 无需在订阅之外再做一次初始化读取（设计文档 4.2）
    listener(this.getState());
    return () => this.listeners.delete(listener);
  }

  /** 加载会话列表并恢复显式指定、UX 缓存或最近会话。 */
  async initialize(preferredConversationId?: string | null): Promise<void> {
    const generation = this.beginNavigation();
    this.dispatch({ type: 'CONVERSATIONS_LOADING_STARTED' });
    try {
      const catalog = await this.modelTargets.catalog(this.navigationAbort?.signal);
      if (generation !== this.navigationGeneration || this.disposed) {
        return;
      }
      this.dispatch({ type: 'MODEL_TARGETS_LOADED', targets: catalog.targets,
        defaultTarget: catalog.defaultTarget });
      if (catalog.defaultTarget != null) {
        const configuration = await this.contextManager.loadConfiguration(
          catalog.defaultTarget, this.navigationAbort?.signal);
        if (generation !== this.navigationGeneration || this.disposed) return;
        this.dispatch({ type: 'CONTEXT_CONFIGURATION_LOADED', configuration });
      }
      const conversations = await this.conversations.list(
        this.navigationAbort?.signal,
      );
      if (generation !== this.navigationGeneration || this.disposed) {
        return;
      }
      this.dispatch({ type: 'CONVERSATIONS_LOADED', conversations });

      // 恢复目标优先级：显式指定 > 上次打开（UX Cache，归属仍由服务端校验）> 最近一条
      const cached = preferredConversationId ?? this.readLastConversationId();
      const target =
        conversations.find(c => c.conversationId === cached)?.conversationId ??
        conversations[0]?.conversationId ??
        null;
      if (target == null) {
        this.dispatch({ type: 'EMPTY_INITIALIZATION_COMPLETED' });
        return;
      }
      await this.loadConversation(target);
    } catch (cause) {
      this.failNavigation(generation, cause);
    }
  }

  /** 只刷新会话元数据列表，不改变当前会话 Context。 */
  async refreshConversations(): Promise<void> {
    const generation = this.beginNavigation();
    try {
      const conversations = await this.conversations.list(
        this.navigationAbort?.signal,
      );
      if (generation !== this.navigationGeneration || this.disposed) {
        return;
      }
      // 刷新不改变当前选中会话，只更新列表本身
      this.dispatch({ type: 'CONVERSATIONS_REFRESHED', conversations });
    } catch (cause) {
      this.failNavigation(generation, cause);
    }
  }

  /** 原子加载指定会话的元数据、完整消息与 ModelContext。 */
  async loadConversation(id: string): Promise<void> {
    // Navigation 与 Run 互斥：切换会话前终止进行中的生成（设计文档第 11 节）
    this.abortRun();
    const generation = this.beginNavigation();
    this.dispatch({ type: 'CONVERSATION_LOADING_STARTED' });
    try {
      if (this.state.modelTargets.length === 0) {
        const catalog = await this.modelTargets.catalog(this.navigationAbort?.signal);
        if (generation !== this.navigationGeneration || this.disposed) return;
        this.dispatch({ type: 'MODEL_TARGETS_LOADED', targets: catalog.targets,
          defaultTarget: catalog.defaultTarget });
      }
      const detail = await this.conversations.get(
        id,
        this.navigationAbort?.signal,
      );
      if (generation !== this.navigationGeneration || this.disposed) {
        return;
      }
      if (!this.state.modelTargets.some(target => sameTarget(target.ref, detail.context.modelTarget))) {
        throw invalidStateError('会话模型目标不可用或配置修订已变化');
      }
      const configuration = await this.contextManager.loadConfiguration(
        detail.context.modelTarget, this.navigationAbort?.signal);
      if (generation !== this.navigationGeneration || this.disposed) return;
      this.dispatch({ type: 'CONTEXT_CONFIGURATION_LOADED', configuration });
      this.writeLastConversationId(id);
      this.dispatch({
        type: 'CONVERSATION_LOADED',
        conversation: detail.conversation,
        context: snapshotConversationContext(detail.context),
      });
    } catch (cause) {
      this.failNavigation(generation, cause);
    }
  }

  /** 取消当前运行并进入尚未持久化的空白会话。 */
  startNewConversation(): void {
    this.abortRun();
    this.beginNavigation();
    this.writeLastConversationId(null);
    this.dispatch({ type: 'NEW_CONVERSATION_STARTED' });
  }

  /** 空闲时显式切换模型目标；服务端先检查完整上下文和工具目录。 */
  async switchModelTarget(targetId: string): Promise<void> {
    if (this.disposed || !this.idleForSending()) {
      throw invalidStateError('只有 Agent 空闲时才能切换模型目标');
    }
    const target = this.state.modelTargets.find(item => item.ref.targetId === targetId);
    if (target == null) throw invalidStateError('模型目标不可用');
    const current = this.requireCurrentModelTarget();
    if (sameTarget(current, target.ref)) return;
    const source = snapshotConversationContext({
      messages: this.state.messages, modelTarget: current, modelContext: this.state.modelContext,
    });
    const conversation = this.state.conversation;
    const generation = this.beginNavigation();
    this.dispatch({ type: 'CONVERSATION_LOADING_STARTED' });
    try {
      // 已保存会话切换会在服务端提交；提交前先确认新窗口可读取，避免提交后配置请求失败造成界面仍显示旧目标。
      const configuration = await this.contextManager.loadConfiguration(
        target.ref, this.navigationAbort?.signal);
      if (generation !== this.navigationGeneration || this.disposed) return;
      const snapshot = await this.tools.refresh();
      if (generation !== this.navigationGeneration || this.disposed) return;
      const tools = toModelTools(snapshot.tools);
      const switched = conversation == null
        ? { conversation: null, context: await this.modelTargets.switchDraft(
          source, target.ref, tools, this.navigationAbort?.signal) }
        : await this.modelTargets.switchConversation(conversation.conversationId,
          conversation.revision, target.ref, tools, this.navigationAbort?.signal);
      if (generation !== this.navigationGeneration || this.disposed) return;
      if (!sameTarget(switched.context.modelTarget, target.ref)) {
        throw invalidStateError('服务端切换结果的模型目标不一致');
      }
      this.dispatch({ type: 'MODEL_TARGET_SWITCHED', context: switched.context,
        conversation: switched.conversation, configuration });
    } catch (cause) {
      this.failNavigation(generation, cause);
    }
  }

  /** 删除指定会话；删除当前会话时同时收敛本地状态。 */
  async deleteConversation(id: string): Promise<void> {
    await this.conversations.delete(id);
    // 会话与它的本地轨迹同生共死：服务端数据删除后本地轨迹必须一并清理。
    this.callTrace?.removeConversation(id);
    if (this.state.conversation?.conversationId === id) {
      // 删除的是当前会话：同步作废它所属的导航与运行，再由状态机回到空白会话。
      this.abortRun();
      this.beginNavigation();
      this.writeLastConversationId(null);
    }
    this.dispatch({ type: 'CONVERSATION_DELETED', conversationId: id });
  }

  /**
   * 发送一轮用户输入；文本与图片按有序 ContentBlock 组装。
   * 文本与图片均为空时不发送。
   */
  async sendMessage(
    text: string,
    images: readonly ImageAttachment[] = [],
  ): Promise<void> {
    const trimmed = text.trim();
    if (trimmed.length === 0 && images.length === 0) {
      return;
    }
    if (!this.idleForSending()) {
      // 忙碌中静默忽略：是否禁用输入由 View 依据状态决定（View 职责）
      return;
    }
    if (this.disposed) {
      return;
    }

    // 发送消息属于当前会话域：作废所有未完成的导航提交
    this.beginNavigation();
    const runGeneration = this.beginRun();
    this.runBaseMessages = [...this.state.messages, composeUserMessage(trimmed, images)];
    this.dispatch({
      type: 'RUN_STARTED',
      messages: [...this.runBaseMessages],
    });

    try {
      if (this.state.modelTarget == null) {
        const catalog = await this.modelTargets.catalog(this.navigationAbort?.signal);
        if (!this.isCurrentRun(runGeneration)) return;
        this.dispatch({ type: 'MODEL_TARGETS_LOADED', targets: catalog.targets,
          defaultTarget: catalog.defaultTarget });
        if (catalog.defaultTarget == null) throw invalidStateError('当前用户没有可用的默认模型目标');
        const configuration = await this.contextManager.loadConfiguration(
          catalog.defaultTarget, this.navigationAbort?.signal);
        if (!this.isCurrentRun(runGeneration)) return;
        this.dispatch({ type: 'CONTEXT_CONFIGURATION_LOADED', configuration });
      }
      // 每轮刷新并冻结统一 Tool 快照：Engine 与 Inspector 消费同一 revision。
      const toolSnapshot = await this.tools.refresh();
      if (!this.isCurrentRun(runGeneration)) {
        return;
      }
      this.toolInspection.activate(toolSnapshot);
      const traceId = newTraceId();
      const execution = this.engine.start({
        conversation: {
          messages: this.runBaseMessages,
          modelTarget: this.requireCurrentModelTarget(),
          modelContext: this.state.modelContext,
        },
        toolSnapshot,
        conversationId: this.state.conversation?.conversationId ?? null,
        traceId,
      }, event => this.onEngineEvent(runGeneration, event));
      // execution-started 在 start 内同步发布，轨迹已存在；用户输入作为过程起点补入。
      this.callTrace?.noteUserInput(traceId, trimmed, images.length);
      this.currentExecution = execution;
      const result = await execution.result;
      if (!this.isCurrentRun(runGeneration)) {
        return;
      }
      if (this.currentExecution === execution) {
        this.currentExecution = null;
      }
      this.toolInspection.deactivate();
      this.commitRunResult(result.messages, result.modelContext);
      this.dispatch({ type: 'RUN_FINISHED', outcome: result.outcome });
      if (result.outcome.type === 'cancelled') {
        // 主动取消只保留当前页已完成的稳定消息，不创建或保存本轮会话。
        this.runActive = false;
        return;
      }
      // completed 与 max-tokens 都已由 message-stop 封闭，必须保存消息和续接状态。
      // 会话标题取自本轮用户输入：纯图片消息没有可读文本，用固定占位
      await this.persistRound(
        trimmed.length > 0 ? trimmed : '[图片]',
        runGeneration,
        traceId,
      );
    } catch (cause) {
      if (!this.isCurrentRun(runGeneration)) {
        return;
      }
      this.currentExecution = null;
      this.toolInspection.deactivate();
      this.dispatch({ type: 'RUN_FAILED', error: normalizeRunError(cause) });
      this.runActive = false;
    }
  }

  /** 批准当前 Execution 精确等待的 Tool 中断。 */
  approveTool(): void {
    this.respondToConfirmation(true);
  }

  /** 拒绝当前 Execution 精确等待的 Tool 中断。 */
  rejectTool(): void {
    this.respondToConfirmation(false);
  }

  /**
   * 在空闲状态使用当前模型生成检查点，并在持久化会话中原子保存后才提交本地状态。
   *
   * <p>摘要成功但保存冲突仍视为整体失败；完整消息和原模型工作上下文始终保持不变。
   */
  async compactContext(): Promise<void> {
    if (this.disposed) {
      return;
    }
    if (!this.idleForSending()) {
      throw invalidStateError('只有 Agent 空闲时才能手动压缩上下文');
    }
    if (this.state.contextWindow.currentTokens == null) {
      throw invalidStateError('尚未取得模型 token usage，不能手动压缩上下文');
    }
    if (!this.state.contextWindow.compactable) {
      throw invalidStateError('当前模型上下文没有可安全压缩的历史前缀');
    }
    const generation = this.beginContextCompaction();
    const abort = this.contextCompactionAbort;
    if (abort == null) {
      throw new Error('手动压缩取消源未创建');
    }
    this.dispatch({ type: 'CONTEXT_COMPACTION_STARTED' });
    try {
      const candidate = await this.contextManager.compact(
        {
          messages: this.state.messages,
          modelTarget: this.requireCurrentModelTarget(),
          modelContext: this.state.modelContext,
        },
        'manual',
        {
          traceId: newTraceId(),
          conversationId: this.state.conversation?.conversationId ?? null,
        },
        abort.signal,
      );
      if (!this.isCurrentContextCompaction(generation)) {
        return;
      }
      const conversation = this.state.conversation;
      if (conversation == null) {
        this.finishContextCompaction(generation);
        this.dispatch({
          type: 'CONTEXT_COMPACTION_COMPLETED',
          modelContext: snapshotModelContext(candidate.modelContext),
          conversation: null,
        });
        return;
      }
      this.dispatch({ type: 'CONTEXT_COMPACTION_SAVING' });
      const saved = await this.conversations.save(conversation.conversationId, {
        title: conversation.title,
        revision: conversation.revision,
        context: candidate,
      }, abort.signal);
      if (!this.isCurrentContextCompaction(generation)) {
        return;
      }
      this.finishContextCompaction(generation);
      this.dispatch({
        type: 'CONTEXT_COMPACTION_COMPLETED',
        modelContext: snapshotModelContext(candidate.modelContext),
        conversation: saved,
      });
    } catch (cause) {
      if (!this.isCurrentContextCompaction(generation)) {
        return;
      }
      this.finishContextCompaction(generation);
      this.dispatch({
        type: 'CONTEXT_COMPACTION_FAILED',
        error: isConflict(cause)
          ? conversationConflictError()
          : normalizeRunError(cause),
      });
    }
  }

  /** 返回 Controller 持有的唯一 Unified Tool Registry。 */
  getToolRegistry(): ToolRegistry {
    return this.tools;
  }

  /** 返回执行感知的只读 Tool 数据源，供可选 Inspector 精确展示能力。 */
  getToolInspectionSource(): ToolInspectionSource {
    return this.toolInspection;
  }

  /** 返回当前会话的只读调用轨迹数据源；采集未启用时返回恒空的显式空源。 */
  getCallTraceSource(): CallTraceSource {
    return this.callTrace ?? disabledCallTraceSource;
  }

  /** 向唯一 Registry 注册页面生命周期内的纯前端 Tool。 */
  registerTool(tool: BrowserTool): ToolRegistration {
    if (this.disposed) {
      throw new Error('AgentController 已释放，不能注册 Tool');
    }
    return this.tools.register(tool);
  }

  /** 中止当前 Execution，并保留已经完成的稳定消息。 */
  abort(): void {
    if (this.state.status === 'compacting-context') {
      // 自动压缩属于当前 Execution，必须取消整个 Run；手动压缩没有活动 Run，
      // 只取消它自己的摘要请求。两者共享 View 状态，但生命周期所有者不同。
      if (this.runActive) {
        this.abortRun();
        this.dispatch({ type: 'RUN_FINISHED', outcome: { type: 'cancelled' } });
        return;
      }
      this.cancelContextCompaction();
      this.dispatch({ type: 'CONTEXT_COMPACTION_CANCELLED' });
      return;
    }
    if (!this.runActive || this.state.runOutcome != null) {
      // 空闲或已经进入保存阶段时不存在可取消的 Execution，保持既有终态不变。
      return;
    }
    this.abortRun();
    this.cancelContextCompaction();
    // 主动停止立即投影 cancelled；Execution 的迟到结果由 runGeneration 丢弃。
    this.dispatch({ type: 'RUN_FINISHED', outcome: { type: 'cancelled' } });
  }

  /** 幂等释放导航、Execution、Engine 与全部观察者。 */
  dispose(): void {
    this.disposed = true;
    this.abortRun();
    this.navigationAbort?.abort();
    this.toolInspection.dispose();
    this.callTrace?.dispose();
    this.engine.dispose();
    this.listeners.clear();
  }

  // ---------- 内部：Run 与确认 ----------

  /** 开始新一轮执行并返回其唯一代次；保存阶段也必须持有该代次才能提交状态。 */
  private beginRun(): number {
    this.runGeneration += 1;
    this.runActive = true;
    return this.runGeneration;
  }

  /** 判断异步结果是否仍属于当前 run，统一收敛所有提交前的竞态检查。 */
  private isCurrentRun(generation: number): boolean {
    return this.runActive && generation === this.runGeneration && !this.disposed;
  }

  /** Engine 事件 → State 转换；runActive 失效后事件一律丢弃。 */
  private onEngineEvent(
    generation: number,
    event: AgentExecutionEvent,
  ): void {
    if (!this.isCurrentRun(generation)) {
      return;
    }
    switch (event.type) {
      case 'status':
        this.dispatch({ type: 'RUN_STATUS_CHANGED', status: event.status });
        break;
      case 'text-delta':
        this.dispatch({ type: 'ASSISTANT_CONTENT_RECEIVED', text: event.text });
        break;
      case 'reasoning-delta':
        this.dispatch({ type: 'ASSISTANT_REASONING_RECEIVED', text: event.text });
        break;
      case 'messages':
        // 稳定消息提交时补全轨迹里的正文与 Tool 结果内容（按消息 ID / callId 关联）。
        this.callTrace?.noteCommittedMessages(event.messages);
        this.dispatch({
          type: 'RUN_MESSAGES_COMMITTED',
          baseMessages: this.runBaseMessages,
          addedMessages: event.messages,
          modelContext: event.modelContext,
        });
        break;
      case 'interrupt':
        this.dispatch({
          type: 'TOOL_CONFIRMATION_REQUESTED',
          confirmation: {
            interruptId: event.interrupt.id,
            tool: event.interrupt.tool,
            arguments: event.interrupt.arguments,
            ...(event.interrupt.reason == null
              ? {}
              : { reason: event.interrupt.reason }),
          },
        });
        break;
      default:
        // tool-call / tool-result 的展示信息已包含在 messages 快照中
        break;
    }
  }

  /** 把 View 意图转换为当前 Execution 的精确中断响应。 */
  private respondToConfirmation(approved: boolean): void {
    const pending = this.state.pendingConfirmation;
    const execution = this.currentExecution;
    if (pending == null || execution == null) {
      throw new Error('当前没有等待响应的 Tool 确认');
    }
    execution.respond({ interruptId: pending.interruptId, value: approved });
    this.dispatch({ type: 'TOOL_CONFIRMATION_RESOLVED' });
  }

  /**
   * 终止当前 run：作废事件提交、消费挂起的确认请求。
   *
   * <p>取消顺序很关键：先在代次仍然有效时同步取消 Execution，让 Runtime 的
   * 终态 Tool 记录（未执行/结果未知）通过最后一次 messages 事件进入当前视图；
   * 之后才推进 generation 作废后续迟到结果（尤其是无法取消的保存写请求）。
   */
  private abortRun(): void {
    if (this.runActive) {
      const execution = this.currentExecution;
      execution?.cancel();
      if (execution != null && this.currentExecution === execution) {
        this.currentExecution = null;
      }
      this.runActive = false;
    }
    this.runGeneration += 1;
    this.runBaseMessages = [];
    this.toolInspection.deactivate();
  }

  /** 创建新的手动压缩代次，并取消此前尚未收敛的摘要调用。 */
  private beginContextCompaction(): number {
    this.cancelContextCompaction();
    this.contextCompactionAbort = new AbortController();
    this.contextCompactionGeneration += 1;
    return this.contextCompactionGeneration;
  }

  /** 判断摘要或保存结果是否仍属于当前手动压缩。 */
  private isCurrentContextCompaction(generation: number): boolean {
    return generation === this.contextCompactionGeneration
      && this.contextCompactionAbort != null
      && !this.disposed;
  }

  /** 成功或失败收敛当前代次，但不额外推进代次。 */
  private finishContextCompaction(generation: number): void {
    if (generation === this.contextCompactionGeneration) {
      this.contextCompactionAbort = null;
    }
  }

  /** 作废并取消当前摘要或保存请求；迟到结果由 generation 屏障丢弃。 */
  private cancelContextCompaction(): void {
    this.contextCompactionGeneration += 1;
    this.contextCompactionAbort?.abort();
    this.contextCompactionAbort = null;
  }

  /** run 以任意非异常终态结束时提交同一快照的稳定消息与 ModelContext。 */
  private commitRunResult(
    added: readonly AgentMessage[],
    modelContext: AgentState['modelContext'],
  ): void {
    this.dispatch({
      type: 'RUN_MESSAGES_COMMITTED',
      baseMessages: this.runBaseMessages,
      addedMessages: added,
      modelContext,
    });
  }

  /**
   * 一轮正常完成后持久化：必要时先创建会话，再整回合全量保存。
   *
   * <p>完整保存命令（消息、ModelContext、目标会话与 revision）在首个异步操作前
   * 一次性固定；等待创建期间发生的会话导航只能作废本轮执行，不能把新会话的
   * ModelContext 或消息混进保存体。写请求开始前再次确认执行仍然有效，
   * 失效即放弃保存——迟到写请求由 generation 屏障阻止，而不是静默提交。
   */
  private async persistRound(
    firstUserText: string,
    runGeneration: number,
    traceId: string,
  ): Promise<void> {
    this.dispatch({ type: 'CONVERSATION_SAVE_STARTED' });
    const messages = [...this.state.messages];
    const modelContext = this.state.modelContext;
    const modelTarget = this.requireCurrentModelTarget();
    // 将归属会话固定在本轮局部变量中；导航可以改变全局 state，但不能改变保存目标。
    let conversation = this.state.conversation;
    try {
      if (conversation == null) {
        // 首轮对话时才创建会话，避免留下大量空会话
        conversation = await this.conversations.create(deriveTitle(firstUserText), {
          messages, modelTarget, modelContext,
        });
        // 首个写请求开始前的归属检查：创建挂起期间的导航/释放/新会话操作
        // 已经作废本轮执行，此时不得再向任何目标写入内容。
        if (!this.isCurrentRun(runGeneration)) {
          return;
        }
      }
      const saved = this.state.conversation == null ? conversation
        : await this.conversations.save(conversation.conversationId, {
          title: conversation.title, revision: conversation.revision,
          context: { messages, modelTarget, modelContext },
        });
      if (!this.isCurrentRun(runGeneration)) {
        return;
      }
      this.writeLastConversationId(saved.conversationId);
      // 首轮轨迹从草稿桶迁移到真实会话；持久化模式才写 localStorage。后续轮次为幂等空操作。
      this.callTrace?.attachConversation(traceId, saved.conversationId);
      this.dispatch({
        type: 'CONVERSATION_SAVED',
        conversation: saved,
      });
      this.runActive = false;
    } catch (cause) {
      if (!this.isCurrentRun(runGeneration)) {
        return;
      }
      if (isConflict(cause)) {
        // 多 Tab 并发写：明确提示重新加载，绝不静默覆盖（设计文档第 19 节）
        this.dispatch({
          type: 'CONVERSATION_SAVE_CONFLICTED',
          error: conversationConflictError(),
        });
        this.runActive = false;
        return;
      }
      this.dispatch({
        type: 'CONVERSATION_SAVE_FAILED',
        error: normalizeRunError(cause),
      });
      this.runActive = false;
    }
  }

  // ---------- 内部：Navigation ----------

  /** 开始一次导航：终止旧导航并推进令牌；返回本次导航的 generation。 */
  private beginNavigation(): number {
    this.cancelContextCompaction();
    this.navigationAbort?.abort();
    this.navigationAbort = new AbortController();
    this.navigationGeneration += 1;
    return this.navigationGeneration;
  }

  /** 导航失败提交（仅最新一代）；错误统一进入 error 状态并保留稳定消息。 */
  private failNavigation(generation: number, cause: unknown): void {
    if (generation !== this.navigationGeneration || this.disposed) {
      return;
    }
    this.dispatch({ type: 'NAVIGATION_FAILED', error: normalizeRunError(cause) });
  }

  // ---------- 内部：状态发布 ----------

  /** 将唯一领域事件交给纯函数状态机，并向订阅者发布隔离快照。 */
  private dispatch(event: AgentStateEvent): void {
    this.state = reduceAgentState(this.state, event);
    const publishedState = this.state;
    const snapshot = this.getState();
    for (const listener of [...this.listeners]) {
      // 订阅者同步取消会发布更新的终态；外层广播不能随后把旧快照再交给其余订阅者。
      if (this.state !== publishedState) {
        break;
      }
      listener(snapshot);
    }
  }

  private readLastConversationId(): string | null {
    try {
      return localStorage.getItem(this.storageKey);
    } catch {
      // 隐私模式等场景 localStorage 不可用：缓存本来就是可选的
      return null;
    }
  }

  private writeLastConversationId(id: string | null): void {
    try {
      if (id == null) {
        localStorage.removeItem(this.storageKey);
      } else {
        localStorage.setItem(this.storageKey, id);
      }
    } catch {
      // 同上：UX Cache 写入失败不影响业务
    }
  }

  /** 允许发送消息的状态：无进行中的 run / 导航载入 / 保存。 */
  private idleForSending(): boolean {
    const busy: AgentStatus[] = [
      'loading-conversations',
      'loading-conversation',
      'loading-tools',
      'compacting-context',
      'streaming',
      'waiting-confirmation',
      'calling-tool',
      'saving',
    ];
    return !busy.includes(this.state.status);
  }

  /** 路由身份必须来自当前会话或用户显式选择，绝不猜测服务器默认值。 */
  private requireCurrentModelTarget(): ModelTargetRef {
    if (this.state.modelTarget == null) throw invalidStateError('尚未选择可用模型目标');
    return this.state.modelTarget;
  }
}

/**
 * Controller 公共端口：View 只依赖这些用户意图与状态订阅能力。
 * 显式声明接口而不是继承具体类，避免 private 字段形成名义类型、阻止宿主替换实现。
 */
export interface PatchBridgeAgentController {
  /** 获取与内部数组引用隔离的当前状态快照。 */
  getState(): AgentState;
  /** 订阅状态；注册时立即收到当前快照，返回取消订阅函数。 */
  subscribe(listener: (state: AgentState) => void): () => void;
  /** 初始化会话列表，并按显式 ID、UX 缓存、最近会话的顺序恢复。 */
  initialize(preferredConversationId?: string | null): Promise<void>;
  /** 只刷新会话列表，不改变当前选中会话。 */
  refreshConversations(): Promise<void>;
  /** 加载指定会话，并作废旧导航与运行结果。 */
  loadConversation(id: string): Promise<void>;
  /** 开始空白会话。 */
  startNewConversation(): void;
  /** 显式切换草稿或持久化会话的目标模型。 */
  switchModelTarget(targetId: string): Promise<void>;
  /** 删除指定会话。 */
  deleteConversation(id: string): Promise<void>;
  /** 发送一轮文本或多模态消息。 */
  sendMessage(text: string, images?: readonly ImageAttachment[]): Promise<void>;
  /** 在空闲状态使用当前模型手动生成上下文检查点。 */
  compactContext(): Promise<void>;
  /** 返回 Engine 与只读 Inspector 共用的唯一 Tool Registry。 */
  getToolRegistry(): ToolRegistry;
  /** 返回执行中固定、空闲时跟随 Registry 的 Tool 调试数据源。 */
  getToolInspectionSource(): ToolInspectionSource;
  /** 返回当前会话的只读调用轨迹数据源。 */
  getCallTraceSource(): CallTraceSource;
  /** 为宿主页面提供一行式纯前端 Tool 注册入口。 */
  registerTool(tool: BrowserTool): ToolRegistration;
  /** 批准当前待确认 Tool。 */
  approveTool(): void;
  /** 拒绝当前待确认 Tool。 */
  rejectTool(): void;
  /** 中止当前 Agent Run。 */
  abort(): void;
  /** 释放 Controller、Engine 与订阅资源。 */
  dispose(): void;
}

/** run / 保存链路异常 → AgentError（已是标准错误则原样透传）。 */
function normalizeRunError(cause: unknown): AgentError {
  if (isAgentError(cause)) {
    return cause;
  }
  return invalidStateError(cause instanceof Error ? cause.message : '操作失败');
}

function isAgentError(cause: unknown): cause is AgentError {
  return (
    cause != null &&
    typeof cause === 'object' &&
    typeof (cause as AgentError).code === 'string' &&
    typeof (cause as AgentError).message === 'string' &&
    typeof (cause as AgentError).retryable === 'boolean'
  );
}

function isConflict(cause: unknown): boolean {
  return isAgentError(cause) && cause.code === 'CONVERSATION_CONFLICT';
}

/** 首条用户消息截断作为默认标题。 */
function deriveTitle(text: string): string {
  return text.length > 30 ? `${text.slice(0, 30)}…` : text;
}

/**
 * 组装厂商中立用户消息：正文在前、图片在后，保持用户选择顺序。
 * 消息 ID 只属于框架，Model Provider 编码上游请求时必须剥离。
 */
function composeUserMessage(
  text: string,
  images: readonly ImageAttachment[],
): AgentMessage {
  const blocks: ContentBlock[] = images.map(attachment => ({
    type: 'image',
    source: attachment.source,
  }));
  if (text.length > 0) {
    blocks.unshift({ type: 'text', text });
  }
  return snapshotAgentMessage({ id: newMessageId(), role: 'user', blocks });
}

/** 生成只用于框架和持久化的稳定消息标识。 */
function newMessageId(): string {
  return `message-${globalThis.crypto.randomUUID()}`;
}

/** 生成短 traceId：一条浏览器链路的可读标识（服务端审计串联用）。 */
function newTraceId(): string {
  return `web-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}
