/**
 * DefaultAgentController 测试：验证 Controller 只协调 ConversationContext、
 * AgentExecution 与统一 Tool Registry，并在导航、保存和取消竞态中守住唯一状态源。
 * Engine 与 Client 都是可编程假实现，不发起真实网络请求。
 */
import { describe, expect, it, vi } from 'vitest';
import type {
  ConversationClient,
  ConversationDetail,
  ConversationSaveBody,
} from '../src/clients/conversationClient';
import type { ToolClient } from '../src/clients/toolClient';
import { DefaultAgentController } from '../src/controller';
import type { ContextManager } from '../src/contextManager';
import type {
  AgentEngine,
  AgentExecution,
  AgentExecutionEvent,
  AgentInterruptResponse,
  AgentRunInput,
  AgentRunResult,
} from '../src/engine';
import { DefaultAgentRuntime } from '../src/runtime';
import { CallTraceStore } from '../src/callTrace';
import { BackendToolProvider, DefaultToolRegistry } from '../src/toolRegistry';
import type { ModelStreamEvent, ModelUsage } from '../src/clients/modelClient';
import type {
  AgentMessage,
  AgentState,
  Conversation,
  ModelState,
  ToolDefinition,
} from '../src/types';
import { testContextManager, testModelContext } from './testContext';

/** 单次可控执行句柄，事件、完成和取消都明确绑定自身。 */
class FakeExecution implements AgentExecution {
  /** 最终结果 Promise，模拟真实 Runtime 的异步生命周期。 */
  public readonly result: Promise<AgentRunResult>;
  /** Controller 发回的精确中断响应。 */
  public readonly responses: AgentInterruptResponse[] = [];
  /** cancel 幂等调用次数，用于验证取消是否落在当前 Execution。 */
  public cancelCount = 0;
  /** 防止测试重复完成同一执行。 */
  private settled = false;
  /** Promise 的成功收敛入口。 */
  private readonly resolveResult: (result: AgentRunResult) => void;
  /** Promise 的失败收敛入口。 */
  private readonly rejectResult: (cause: unknown) => void;

  /** 创建与一轮输入和监听器绑定的执行。 */
  constructor(
    public readonly input: AgentRunInput,
    private readonly listener: (event: AgentExecutionEvent) => void,
  ) {
    let resolveResult!: (result: AgentRunResult) => void;
    let rejectResult!: (cause: unknown) => void;
    this.result = new Promise<AgentRunResult>((resolve, reject) => {
      resolveResult = resolve;
      rejectResult = reject;
    });
    this.resolveResult = resolveResult;
    this.rejectResult = rejectResult;
  }

  /** 发布只属于本轮执行的结构化事件。 */
  emit(event: AgentExecutionEvent): void {
    this.listener(event);
  }

  /** 正常完成本轮执行。 */
  finish(result: AgentRunResult): void {
    if (this.settled) {
      return;
    }
    this.settled = true;
    this.resolveResult(result);
  }

  /** 以明确异常结束本轮执行。 */
  fail(cause: unknown): void {
    if (this.settled) {
      return;
    }
    this.settled = true;
    this.rejectResult(cause);
  }

  /** 记录 Controller 对 Human-in-the-loop 的结构化响应。 */
  respond(response: AgentInterruptResponse): void {
    this.responses.push(response);
  }

  /** 模拟 Runtime 的幂等取消，并把 result 收敛为 cancelled。 */
  cancel(): void {
    if (this.cancelCount > 0) {
      return;
    }
    this.cancelCount += 1;
    this.finish({
      messages: [],
      modelContext: this.input.conversation.modelContext,
      outcome: { type: 'cancelled' },
    });
  }
}

/** 可编程假 Engine：每次 start 都返回独立 Execution。 */
class FakeEngine implements AgentEngine {
  /** 按启动顺序保存执行，便于竞态测试引用准确对象。 */
  public readonly executions: FakeExecution[] = [];
  /** dispose 是否已由 Controller 传播。 */
  public disposed = false;

  /** 创建并记录一轮新执行。 */
  start(
    input: AgentRunInput,
    listener: (event: AgentExecutionEvent) => void,
  ): AgentExecution {
    const execution = new FakeExecution(input, listener);
    this.executions.push(execution);
    return execution;
  }

  /** 释放全部测试执行。 */
  dispose(): void {
    this.disposed = true;
    for (const execution of this.executions) {
      execution.cancel();
    }
  }

  /** 返回最近启动的执行；没有执行时为 null。 */
  latest(): FakeExecution | null {
    return this.executions.at(-1) ?? null;
  }
}

/** 会话保存记录保留整个请求体，专门验证 context 原子快照。 */
interface SavedConversation {
  /** 保存目标会话。 */
  readonly id: string;
  /** 完整消息与 ModelContext 同属的完整保存体。 */
  readonly body: ConversationSaveBody;
}

/** 可编程假会话 Client，支持控制导航和保存完成顺序。 */
class FakeConversationClient implements ConversationClient {
  /** list 返回的会话目录。 */
  public conversations: Conversation[] = [];
  /** 所有首轮创建标题。 */
  public readonly createdTitles: Array<string | null> = [];
  /** 自定义会话创建逻辑，用于在测试中挂起创建请求。 */
  public createImpl: (title: string | null) => Promise<Conversation> = async title =>
    conversation('conversation-new', 0, title);
  /** 所有原子保存请求。 */
  public readonly saved: SavedConversation[] = [];
  /** 所有删除目标。 */
  public readonly deleted: string[] = [];
  /** 自定义会话加载逻辑。 */
  public getImpl: (id: string) => Promise<ConversationDetail> = async id => ({
    conversation: conversation(id, 5),
    context: {
      messages: [textMessage(`user-${id}`, 'user', `会话 ${id}`)],
      modelContext: testModelContext(),
    },
  });
  /** 自定义会话保存逻辑。 */
  public saveImpl: (
    id: string,
    body: ConversationSaveBody,
  ) => Promise<Conversation> = async (id, body) => conversation(id, body.revision + 1);

  /** 返回隔离的会话列表。 */
  async list(): Promise<Conversation[]> {
    return [...this.conversations];
  }

  /** 模拟首轮创建会话。 */
  async create(title: string | null): Promise<Conversation> {
    this.createdTitles.push(title);
    return this.createImpl(title);
  }

  /** 委托测试提供的加载脚本。 */
  async get(id: string): Promise<ConversationDetail> {
    return this.getImpl(id);
  }

  /** 记录完整 context 后委托保存脚本。 */
  async save(id: string, body: ConversationSaveBody): Promise<Conversation> {
    this.saved.push({ id, body });
    return this.saveImpl(id, body);
  }

  /** 记录删除目标。 */
  async delete(id: string): Promise<void> {
    this.deleted.push(id);
  }
}

/** Controller 测试只需要 Tool 发现，实际调度属于 Engine。 */
class FakeToolClient implements ToolClient {
  /** 当前后端 Tool 定义。 */
  public definitions: ToolDefinition[] = [];

  /** 返回本轮 Registry 刷新结果。 */
  async list(): Promise<ToolDefinition[]> {
    return this.definitions;
  }

  /** Controller 不得绕过 Engine 直接调用 Tool。 */
  async call(): Promise<never> {
    throw new Error('Controller 不直接调用 Tool');
  }
}

/** 创建稳定会话元数据。 */
function conversation(
  id: string,
  revision: number,
  title: string | null = null,
): Conversation {
  return {
    conversationId: id,
    title,
    revision,
    status: 'ACTIVE',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  };
}

/** 创建稳定文本消息。 */
function textMessage(
  id: string,
  role: 'user' | 'assistant',
  text: string,
): AgentMessage {
  return { id, role, blocks: [{ type: 'text', text }] };
}

/** 创建需要确认的测试 Tool。 */
function confirmationTool(): ToolDefinition {
  return {
    name: 'local.device_restart',
    title: '重启设备',
    description: '重启指定设备',
    inputSchema: { type: 'object' },
    annotations: {
      readOnlyHint: false,
      destructiveHint: true,
      idempotentHint: false,
      requireConfirmation: true,
    },
    source: 'LOCAL',
    permissions: [],
  };
}

/** 创建完整 Controller 测试夹具。 */
function makeController(contextManager: ContextManager = testContextManager()): {
  readonly engine: FakeEngine;
  readonly conversations: FakeConversationClient;
  readonly toolClient: FakeToolClient;
  readonly controller: DefaultAgentController;
} {
  const engine = new FakeEngine();
  const conversations = new FakeConversationClient();
  const toolClient = new FakeToolClient();
  const tools = new DefaultToolRegistry([new BackendToolProvider(toolClient)]);
  const controller = new DefaultAgentController({
    engine,
    conversations,
    tools,
    contextManager,
  });
  return { engine, conversations, toolClient, controller };
}

/** 等待 Controller 完成 Tool 刷新并取得本轮 Execution。 */
async function waitForExecution(engine: FakeEngine): Promise<FakeExecution> {
  await vi.waitFor(() => {
    expect(engine.latest()).not.toBeNull();
  });
  return engine.latest() as FakeExecution;
}

/** 一轮完成后使用的 Provider 私有状态。 */
const MODEL_STATE: ModelState = {
  format: 'provider-state/v1',
  data: { responseMessageId: 'assistant-1' },
};

/** 自然结束用例复用的统一完成态。 */
const COMPLETED_OUTCOME = {
  type: 'completed',
  stopReason: 'end-turn',
} as const;

/** 一轮完成后的明确模型工作上下文：usage 与可见消息严格对应本轮。 */
function runModelContext(): ReturnType<typeof testModelContext> {
  return Object.freeze({
    ...testModelContext(),
    usage: Object.freeze({
      totalTokens: 120,
      source: 'provider' as const,
      measuredThroughMessageId: null,
    }),
  });
}

/** VA-03 端到端用例使用的测试用量。 */
const SCRIPT_USAGE: ModelUsage = Object.freeze({
  inputTokens: 100,
  outputTokens: 20,
  totalTokens: 120,
});

/** 可脚本化的模型端口：按调用顺序消费事件脚本并记录完整请求。 */
class ScriptedControllerModel {
  /** 每次模型调用消费一段事件数组。 */
  public scripts: ModelStreamEvent[][] = [];
  /** 按顺序记录每次请求的消息快照。 */
  public readonly requests: AgentRunInput['conversation']['messages'][] = [];

  /** 返回当前脚本对应的 AsyncIterable，并记录请求消息。 */
  async *stream(
    request: Parameters<import('../src/clients/modelClient').Model['stream']>[0],
  ): AsyncIterable<ModelStreamEvent> {
    this.requests.push([...request.messages]);
    for (const event of this.scripts.shift() ?? []) {
      yield event;
    }
  }
}

/** 构造带单个 Tool Call 的完整模型响应事件。 */
function scriptedToolUse(callId: string, toolName: string): ModelStreamEvent[] {
  return [
    {
      type: 'block-start',
      index: 0,
      block: { type: 'tool-call', callId, name: toolName },
    },
    {
      type: 'block-delta',
      index: 0,
      delta: { type: 'tool-call', argumentsDelta: '{"target":"dev-1"}' },
    },
    { type: 'block-stop', index: 0 },
    { type: 'message-stop', stopReason: 'tool-use', usage: SCRIPT_USAGE, modelState: null },
  ];
}

/** 构造自然结束的文本响应事件。 */
function scriptedText(text: string): ModelStreamEvent[] {
  return [
    { type: 'block-start', index: 0, block: { type: 'text' } },
    { type: 'block-delta', index: 0, delta: { type: 'text', text } },
    { type: 'block-stop', index: 0 },
    { type: 'message-stop', stopReason: 'end-turn', usage: SCRIPT_USAGE, modelState: null },
  ];
}

/** VA-03 端到端用例的执行预算。 */
const RUNTIME_LIMITS = {
  maxModelCalls: 4,
  maxToolCalls: 8,
  maxDurationMs: 10_000,
  maxModelOutputCharacters: 10_000,
  maxToolResultCharacters: 10_000,
} as const;

/** 创建可手动压缩的测试 ModelContext。 */
function compactableModelContext(): ReturnType<typeof testModelContext> & {
  readonly usage: NonNullable<ReturnType<typeof testModelContext>['usage']>;
} {
  return Object.freeze({
    ...testModelContext(MODEL_STATE),
    usage: Object.freeze({
      totalTokens: 900,
      source: 'provider' as const,
      measuredThroughMessageId: 'assistant-recent',
    }),
  });
}

/** 创建小窗口手动压缩测试所需的 ContextManager。 */
function manualCompactionManager(
  compact: ContextManager['compact'],
): ContextManager {
  const base = testContextManager();
  const configuration = Object.freeze({
    contextWindowTokens: 1_000,
    automaticThresholdTokens: 800,
    keepRecentTokens: 40,
  });
  return {
    ...base,
    loadConfiguration: async () => configuration,
    getConfiguration: () => configuration,
    compact,
  };
}

describe('DefaultAgentController', () => {
  it('subscribe 立即推送唯一状态源的当前快照', () => {
    const { controller } = makeController();
    const states: AgentState[] = [];

    controller.subscribe(state => states.push(state));

    expect(states).toHaveLength(1);
    expect(states[0]?.status).toBe('idle');
  });

  it('空闲时 abort 是幂等空操作，不伪造 cancelled 终态', () => {
    const { controller } = makeController();

    controller.abort();

    expect(controller.getState()).toMatchObject({
      status: 'idle',
      runOutcome: null,
    });
  });

  it('消费 Execution 事件并把完整消息与 ModelContext 作为一个 context 原子保存', async () => {
    const { engine, conversations, controller } = makeController();
    const states: AgentState[] = [];
    controller.subscribe(state => states.push(state));
    const sending = controller.sendMessage('查一下设备');
    const execution = await waitForExecution(engine);
    const assistant = textMessage('assistant-1', 'assistant', '设备正常');

    execution.emit({ type: 'status', status: 'streaming' });
    execution.emit({ type: 'reasoning-delta', text: '先读取设备状态' });
    execution.emit({ type: 'text-delta', text: '设备正常' });
    execution.emit({
      type: 'messages',
      messages: [assistant],
      modelContext: testModelContext(MODEL_STATE),
    });
    execution.finish({
      messages: [assistant],
      modelContext: testModelContext(MODEL_STATE),
      outcome: COMPLETED_OUTCOME,
    });
    await sending;

    expect(states.map(state => state.status)).toEqual(expect.arrayContaining([
      'loading-tools',
      'streaming',
      'saving',
      'done',
    ]));
    expect(execution.input).not.toHaveProperty('tools');
    expect(execution.input.toolSnapshot.tools).toBeDefined();
    expect(conversations.createdTitles).toEqual(['查一下设备']);
    expect(conversations.saved).toHaveLength(1);
    const user = execution.input.conversation.messages.at(-1);
    expect(user).toMatchObject({
      role: 'user',
      blocks: [{ type: 'text', text: '查一下设备' }],
    });
    expect(conversations.saved[0]).toEqual({
      id: 'conversation-new',
      body: {
        title: '查一下设备',
        revision: 0,
        context: {
          messages: [user, assistant],
          modelContext: testModelContext(MODEL_STATE),
        },
      },
    });
    expect(controller.getState()).toMatchObject({
      status: 'done',
      modelContext: testModelContext(MODEL_STATE),
      runOutcome: COMPLETED_OUTCOME,
    });
  });

  it('max-tokens 保留可见终态并像自然完成一样保存稳定上下文', async () => {
    const { engine, conversations, controller } = makeController();
    const sending = controller.sendMessage('生成较长报告');
    const execution = await waitForExecution(engine);
    const assistant = textMessage('assistant-limited', 'assistant', '报告的已完成部分');

    execution.finish({
      messages: [assistant],
      modelContext: testModelContext(MODEL_STATE),
      outcome: { type: 'max-tokens' },
    });
    await sending;

    expect(conversations.createdTitles).toEqual(['生成较长报告']);
    expect(conversations.saved).toHaveLength(1);
    expect(conversations.saved[0]?.body.context).toEqual({
      messages: [execution.input.conversation.messages.at(-1), assistant],
      modelContext: testModelContext(MODEL_STATE),
    });
    expect(controller.getState()).toMatchObject({
      status: 'done',
      modelContext: testModelContext(MODEL_STATE),
      runOutcome: { type: 'max-tokens' },
    });
  });

  it('Engine 返回 cancelled 时保留本地稳定消息但不创建或保存会话', async () => {
    const { engine, conversations, controller } = makeController();
    const sending = controller.sendMessage('执行到一半停止');
    const execution = await waitForExecution(engine);
    const stable = textMessage('assistant-stable', 'assistant', '停止前已封闭的内容');

    execution.finish({
      messages: [stable],
      modelContext: testModelContext(MODEL_STATE),
      outcome: { type: 'cancelled' },
    });
    await sending;

    expect(conversations.createdTitles).toEqual([]);
    expect(conversations.saved).toEqual([]);
    expect(controller.getState()).toMatchObject({
      status: 'done',
      messages: [execution.input.conversation.messages.at(-1), stable],
      modelContext: testModelContext(MODEL_STATE),
      runOutcome: { type: 'cancelled' },
    });
  });

  it('加载的 ConversationContext 原样进入下一轮 Execution', async () => {
    const { engine, conversations, controller } = makeController();
    const history = textMessage('user-history', 'user', '历史');
    conversations.getImpl = async id => ({
      conversation: conversation(id, 12, '旧会话'),
      context: { messages: [history], modelContext: testModelContext(MODEL_STATE) },
    });
    await controller.loadConversation('conversation-7');

    expect(controller.getState().messages).toEqual([history]);
    expect(controller.getState().modelContext.modelState).toEqual(MODEL_STATE);

    const sending = controller.sendMessage('继续');
    const execution = await waitForExecution(engine);
    expect(execution.input.conversation.modelContext.modelState).toEqual(MODEL_STATE);
    expect(execution.input.conversation.messages[0]).toEqual(history);
    execution.finish({
      messages: [],
      modelContext: testModelContext(MODEL_STATE),
      outcome: COMPLETED_OUTCOME,
    });
    await sending;
    expect(conversations.saved[0]?.body.revision).toBe(12);
  });

  it('手动摘要失败时完整历史与原 ModelContext 保持原子不变', async () => {
    const failure = new Error('摘要模型失败');
    const compact = vi.fn<ContextManager['compact']>(async () => { throw failure; });
    const { conversations, controller } = makeController(manualCompactionManager(compact));
    const originalContext = {
      messages: [
        textMessage('user-old', 'user', '旧问题'.repeat(100)),
        textMessage('assistant-old', 'assistant', '旧答案'.repeat(100)),
        textMessage('user-recent', 'user', '近期问题'),
        textMessage('assistant-recent', 'assistant', '近期答案'),
      ],
      modelContext: compactableModelContext(),
    };
    conversations.getImpl = async id => ({
      conversation: conversation(id, 4, '压缩测试'),
      context: originalContext,
    });
    await controller.initialize();
    await controller.loadConversation('conversation-compact');

    await controller.compactContext();

    expect(compact).toHaveBeenCalledTimes(1);
    expect(conversations.saved).toEqual([]);
    expect(controller.getState()).toMatchObject({
      status: 'error',
      messages: originalContext.messages,
      modelContext: originalContext.modelContext,
      error: { message: '摘要模型失败' },
    });
  });

  it('摘要成功但会话保存失败时不提前提交候选 ModelContext', async () => {
    const originalModelContext = compactableModelContext();
    const candidateModelContext = Object.freeze({
      checkpoint: Object.freeze({
        id: 'checkpoint-1',
        summary: '候选摘要',
        trigger: 'manual' as const,
        compactedAt: '2026-08-27T08:00:00.000Z',
        tokensBefore: 900,
        estimatedTokensAfter: 120,
        compactionCount: 1,
      }),
      firstRetainedMessageId: 'user-recent',
      modelState: null,
      usage: Object.freeze({
        totalTokens: 120,
        source: 'estimated' as const,
        measuredThroughMessageId: 'assistant-recent',
      }),
    });
    const compact = vi.fn<ContextManager['compact']>(async current => Object.freeze({
      messages: current.messages,
      modelContext: candidateModelContext,
    }));
    const { conversations, controller } = makeController(manualCompactionManager(compact));
    const messages = [
      textMessage('user-old', 'user', '旧问题'.repeat(100)),
      textMessage('assistant-old', 'assistant', '旧答案'.repeat(100)),
      textMessage('user-recent', 'user', '近期问题'),
      textMessage('assistant-recent', 'assistant', '近期答案'),
    ];
    conversations.getImpl = async id => ({
      conversation: conversation(id, 4, '压缩测试'),
      context: { messages, modelContext: originalModelContext },
    });
    conversations.saveImpl = async () => { throw new Error('保存失败'); };
    await controller.initialize();
    await controller.loadConversation('conversation-compact');

    await controller.compactContext();

    expect(conversations.saved[0]?.body.context.modelContext)
      .toEqual(candidateModelContext);
    expect(controller.getState()).toMatchObject({
      status: 'error',
      messages,
      modelContext: originalModelContext,
      error: { message: '保存失败' },
    });
  });

  it('Tool Confirmation 通过 Execution.respond 精确处理批准与拒绝', async () => {
    for (const approved of [true, false]) {
      const { engine, controller } = makeController();
      const sending = controller.sendMessage('重启设备');
      const execution = await waitForExecution(engine);
      execution.emit({
        type: 'interrupt',
        interrupt: {
          id: `interrupt-${String(approved)}`,
          type: 'tool-confirmation',
          tool: confirmationTool(),
          arguments: { serial: 'DEV-1' },
        },
      });

      expect(controller.getState().status).toBe('waiting-confirmation');
      expect(controller.getState().pendingConfirmation).toMatchObject({
        interruptId: `interrupt-${String(approved)}`,
        arguments: { serial: 'DEV-1' },
      });
      if (approved) {
        controller.approveTool();
      } else {
        controller.rejectTool();
      }

      expect(execution.responses).toEqual([{
        interruptId: `interrupt-${String(approved)}`,
        value: approved,
      }]);
      expect(controller.getState().pendingConfirmation).toBeNull();
      execution.finish({
        messages: [],
        modelContext: testModelContext(),
        outcome: COMPLETED_OUTCOME,
      });
      await sending;
    }
  });

  it('abort 只取消当前 Execution，并丢弃该执行后续迟到事件', async () => {
    const { engine, conversations, controller } = makeController();
    const sending = controller.sendMessage('长回答');
    const execution = await waitForExecution(engine);
    execution.emit({ type: 'status', status: 'streaming' });
    execution.emit({ type: 'text-delta', text: '半截' });

    controller.abort();
    await sending;
    execution.emit({
      type: 'messages',
      messages: [textMessage('assistant-late', 'assistant', '迟到结果')],
      modelContext: testModelContext(MODEL_STATE),
    });

    expect(execution.cancelCount).toBe(1);
    expect(controller.getState().status).toBe('done');
    expect(controller.getState().streamingAssistant).toBeNull();
    expect(controller.getState().messages).toEqual([
      execution.input.conversation.messages.at(-1),
    ]);
    expect(controller.getState().modelContext.modelState).toBeNull();
    expect(controller.getState().runOutcome).toEqual({ type: 'cancelled' });
    expect(conversations.createdTitles).toEqual([]);
    expect(conversations.saved).toEqual([]);
  });

  it('自动压缩阶段停止会取消所属 Execution 而不是只处理手动摘要', async () => {
    const { engine, conversations, controller } = makeController();
    const sending = controller.sendMessage('达到窗口阈值后的长回答');
    const execution = await waitForExecution(engine);
    execution.emit({ type: 'status', status: 'compacting-context' });

    controller.abort();
    await sending;

    expect(execution.cancelCount).toBe(1);
    expect(conversations.createdTitles).toEqual([]);
    expect(conversations.saved).toEqual([]);
    expect(controller.getState()).toMatchObject({
      status: 'done',
      runOutcome: { type: 'cancelled' },
    });
  });

  it('ToolInspectionSource 在运行中固定为模型实际快照，结束后再跟随 Registry', async () => {
    const { engine, controller } = makeController();
    controller.registerTool({
      name: 'frontend.before_run',
      description: '运行开始前已存在的 Tool',
      inputSchema: { type: 'object' },
      annotations: { readOnlyHint: true },
      execute: () => 'before',
    });
    const inspection = controller.getToolInspectionSource();
    const sending = controller.sendMessage('检查工具快照');
    const execution = await waitForExecution(engine);

    expect(inspection.snapshot()).toMatchObject({
      revision: execution.input.toolSnapshot.revision,
      scope: 'current-execution',
      tools: [expect.objectContaining({ name: 'frontend.before_run' })],
    });
    controller.registerTool({
      name: 'frontend.after_run_started',
      description: '本轮开始后才注册的 Tool',
      inputSchema: { type: 'object' },
      annotations: { readOnlyHint: true },
      execute: () => 'after',
    });
    expect(inspection.snapshot().tools.map(tool => tool.name)).toEqual([
      'frontend.before_run',
    ]);

    execution.finish({
      messages: [],
      modelContext: testModelContext(),
      outcome: COMPLETED_OUTCOME,
    });
    await sending;

    expect(inspection.snapshot().scope).toBe('current-registry');
    expect(inspection.snapshot().tools.map(tool => tool.name)).toEqual([
      'frontend.after_run_started',
      'frontend.before_run',
    ]);
  });

  it('导航代次阻止较早加载结果覆盖后发会话', async () => {
    const { conversations, controller } = makeController();
    const resolvers = new Map<string, (detail: ConversationDetail) => void>();
    conversations.getImpl = id => new Promise(resolve => resolvers.set(id, resolve));

    const loadA = controller.loadConversation('conversation-a');
    const loadB = controller.loadConversation('conversation-b');
    resolvers.get('conversation-b')?.({
      conversation: conversation('conversation-b', 2),
      context: {
        messages: [textMessage('user-b', 'user', 'B')],
        modelContext: testModelContext(MODEL_STATE),
      },
    });
    await loadB;
    resolvers.get('conversation-a')?.({
      conversation: conversation('conversation-a', 1),
      context: {
        messages: [textMessage('user-a', 'user', 'A')],
        modelContext: testModelContext(),
      },
    });
    await loadA;

    expect(controller.getState().conversation?.conversationId).toBe('conversation-b');
    expect(controller.getState().messages).toEqual([
      textMessage('user-b', 'user', 'B'),
    ]);
    expect(controller.getState().modelContext.modelState).toEqual(MODEL_STATE);
  });

  it('保存阶段切换会话时，迟到保存不能提交旧 context', async () => {
    const { engine, conversations, controller } = makeController();
    await controller.loadConversation('conversation-a');
    let resolveSave: ((conversation: Conversation) => void) | null = null;
    conversations.saveImpl = () => new Promise(resolve => {
      resolveSave = resolve;
    });
    const sending = controller.sendMessage('保存 A');
    const execution = await waitForExecution(engine);
    execution.finish({
      messages: [textMessage('assistant-a', 'assistant', 'A 的回答')],
      modelContext: testModelContext(MODEL_STATE),
      outcome: COMPLETED_OUTCOME,
    });
    await vi.waitFor(() => {
      expect(controller.getState().status).toBe('saving');
    });

    await controller.loadConversation('conversation-b');
    resolveSave?.(conversation('conversation-a', 6));
    await sending;

    expect(controller.getState().conversation?.conversationId).toBe('conversation-b');
    expect(controller.getState().messages).toEqual([
      textMessage('user-conversation-b', 'user', '会话 conversation-b'),
    ]);
  });

  it('进入保存阶段后 abort 不得把已封闭的 max-tokens 结果改写为 cancelled', async () => {
    const { engine, conversations, controller } = makeController();
    await controller.loadConversation('conversation-a');
    let resolveSave: ((conversation: Conversation) => void) | null = null;
    conversations.saveImpl = () => new Promise(resolve => {
      resolveSave = resolve;
    });
    const sending = controller.sendMessage('生成长回答');
    const execution = await waitForExecution(engine);
    execution.finish({
      messages: [textMessage('assistant-limited', 'assistant', '已封闭的截断内容')],
      modelContext: testModelContext(MODEL_STATE),
      outcome: { type: 'max-tokens' },
    });
    await vi.waitFor(() => {
      expect(controller.getState().status).toBe('saving');
    });

    controller.abort();

    expect(execution.cancelCount).toBe(0);
    expect(controller.getState().runOutcome).toEqual({ type: 'max-tokens' });
    resolveSave?.(conversation('conversation-a', 6));
    await sending;
    expect(conversations.saved).toHaveLength(1);
    expect(controller.getState()).toMatchObject({
      status: 'done',
      runOutcome: { type: 'max-tokens' },
    });
  });

  it('多模态输入使用 ImageSource，并按文本、Base64、URL 的顺序完整保存', async () => {
    const { engine, conversations, controller } = makeController();
    const sending = controller.sendMessage('比较两张图', [
      {
        source: {
          type: 'base64',
          mediaType: 'image/png',
          data: 'QUJD',
        },
      },
      {
        source: {
          type: 'url',
          url: 'https://example.com/device.png',
        },
      },
    ]);
    const execution = await waitForExecution(engine);
    const user = execution.input.conversation.messages.at(-1);

    expect(user).toMatchObject({
      role: 'user',
      blocks: [
        { type: 'text', text: '比较两张图' },
        {
          type: 'image',
          source: { type: 'base64', mediaType: 'image/png', data: 'QUJD' },
        },
        {
          type: 'image',
          source: { type: 'url', url: 'https://example.com/device.png' },
        },
      ],
    });
    execution.finish({
      messages: [],
      modelContext: testModelContext(),
      outcome: COMPLETED_OUTCOME,
    });
    await sending;

    expect(conversations.saved[0]?.body.context.messages[0]).toEqual(user);
  });

  it('文本和图片均为空时不创建 Execution', async () => {
    const { engine, controller } = makeController();

    await controller.sendMessage('   ');

    expect(engine.executions).toEqual([]);
    expect(controller.getState().status).toBe('idle');
  });

  it('dispose 释放 Engine，之后的发送不产生副作用', async () => {
    const { engine, controller } = makeController();
    controller.dispose();

    await controller.sendMessage('不会发送');

    expect(engine.disposed).toBe(true);
    expect(engine.executions).toEqual([]);
  });

  // ---------- VA-02：首轮保存的完整快照与归属检查 ----------

  it('VA-02：首轮创建挂起期间加载 B，保存体不得混用 A/B 且不提交迟到写请求', async () => {
    const { engine, conversations, controller } = makeController();
    // 挂起 A 的首轮创建请求
    let releaseCreate: ((value: Conversation) => void) | null = null;
    conversations.createImpl = () => new Promise<Conversation>(resolve => {
      releaseCreate = resolve;
    });

    const sending = controller.sendMessage('A 问题');
    const execution = await waitForExecution(engine);
    execution.finish({
      messages: [textMessage('assistant-a', 'assistant', 'A 回答')],
      modelContext: runModelContext(),
      outcome: COMPLETED_OUTCOME,
    });
    // 等待持久化流程进入挂起的 create()
    await vi.waitFor(() => {
      expect(conversations.createdTitles).toHaveLength(1);
      expect(releaseCreate).not.toBeNull();
    });

    // 创建挂起期间加载 B：导航作废本轮执行
    await controller.loadConversation('B');
    expect(controller.getState().conversation?.conversationId).toBe('B');

    releaseCreate?.(conversation('conversation-A', 0, 'A 问题'));
    await sending;

    // 保存写请求从未发出：A 的内容不能带着 B 的 ModelContext 提交
    expect(conversations.saved).toHaveLength(0);
    // UI 保持 B；迟到结果不得覆盖当前视图
    expect(controller.getState().conversation?.conversationId).toBe('B');
    expect(controller.getState().messages).toEqual([
      textMessage('user-B', 'user', '会话 B'),
    ]);
  });

  it('VA-02：创建挂起期间新建空会话同样阻止写请求', async () => {
    const { engine, conversations, controller } = makeController();
    let releaseCreate: ((value: Conversation) => void) | null = null;
    conversations.createImpl = () => new Promise<Conversation>(resolve => {
      releaseCreate = resolve;
    });

    const sending = controller.sendMessage('A 问题');
    const execution = await waitForExecution(engine);
    execution.finish({
      messages: [textMessage('assistant-a', 'assistant', 'A 回答')],
      modelContext: testModelContext(),
      outcome: COMPLETED_OUTCOME,
    });
    await vi.waitFor(() => expect(releaseCreate).not.toBeNull());

    controller.startNewConversation();
    releaseCreate?.(conversation('conversation-A', 0));
    await sending;

    expect(conversations.saved).toHaveLength(0);
    expect(controller.getState().conversation).toBeNull();
    expect(controller.getState().messages).toEqual([]);
  });

  it('VA-02：创建挂起期间释放 Controller 同样阻止写请求', async () => {
    const { engine, conversations, controller } = makeController();
    let releaseCreate: ((value: Conversation) => void) | null = null;
    conversations.createImpl = () => new Promise<Conversation>(resolve => {
      releaseCreate = resolve;
    });

    const sending = controller.sendMessage('A 问题');
    const execution = await waitForExecution(engine);
    execution.finish({
      messages: [textMessage('assistant-a', 'assistant', 'A 回答')],
      modelContext: testModelContext(),
      outcome: COMPLETED_OUTCOME,
    });
    await vi.waitFor(() => expect(releaseCreate).not.toBeNull());

    controller.dispose();
    releaseCreate?.(conversation('conversation-A', 0));
    await sending;

    expect(conversations.saved).toHaveLength(0);
  });

  it('VA-02：首轮创建失败产生明确错误结果，不留成功假象', async () => {
    const { engine, conversations, controller } = makeController();
    conversations.createImpl = async () => {
      throw new Error('会话服务不可用');
    };

    const sending = controller.sendMessage('A 问题');
    const execution = await waitForExecution(engine);
    execution.finish({
      messages: [textMessage('assistant-a', 'assistant', 'A 回答')],
      modelContext: runModelContext(),
      outcome: COMPLETED_OUTCOME,
    });
    await sending;

    expect(conversations.saved).toHaveLength(0);
    expect(controller.getState().status).toBe('error');
    expect(controller.getState().error).toMatchObject({ code: 'INVALID_STATE' });
    // 稳定消息保留在当前页，完整历史不受失败影响
    expect(controller.getState().messages.at(-1)).toMatchObject({ id: 'assistant-a' });
  });

  it('VA-02：已持久化会话的保存体在保存前固定完整 ModelContext', async () => {
    const { engine, conversations, controller } = makeController();
    // 预置当前会话 C，发送后保存体必须引用 C 的消息与 C 的模型状态
    conversations.conversations = [conversation('C', 3)];
    await controller.initialize('C');

    const sending = controller.sendMessage('C 问题');
    const execution = await waitForExecution(engine);
    execution.finish({
      messages: [textMessage('assistant-c', 'assistant', 'C 回答')],
      modelContext: runModelContext(),
      outcome: COMPLETED_OUTCOME,
    });
    await sending;

    const saved = conversations.saved.at(-1);
    expect(saved?.id).toBe('C');
    // 保存体携带 initialize('C') 时取得的 revision，保证乐观锁归属一致
    expect(saved?.body.revision).toBe(5);
    expect(saved?.body.context.messages.at(-1)).toMatchObject({ id: 'assistant-c' });
    // ModelContext 与消息来自同一轮快照，不混入其他会话状态
    expect(saved?.body.context.modelContext.usage?.totalTokens).toBe(120);
    expect(saved?.body.context.modelContext.modelState).toBeNull();
  });

  // ---------- VA-03：取消后下一轮工作上下文的继续契约 ----------

  it('VA-03：确认前取消后再次发送，第二次模型输入严格配对且原 Tool 未执行', async () => {
    const model = new ScriptedControllerModel();
    let sequence = 0;
    // 启用轨迹采集：终态记录必须通过 Call Trace 的整批引用校验而不破坏轨迹；
    // Hook 收到未知 traceId 会抛错，轨迹校验因此真实覆盖本用例的全部消息事件。
    const callTrace = new CallTraceStore({ mode: 'memory' });
    const runtime = new DefaultAgentRuntime(model, {
      limits: RUNTIME_LIMITS,
      contextManager: testContextManager(),
      createId: kind => `${kind}-${++sequence}`,
      hooks: [callTrace],
    });
    const engine = new FakeEngine();
    const conversations = new FakeConversationClient();
    const tools = new DefaultToolRegistry();
    let executed = 0;
    tools.register({
      name: 'page.restart',
      description: '重启设备',
      inputSchema: { type: 'object' },
      annotations: {
        readOnlyHint: false,
        destructiveHint: true,
        idempotentHint: false,
        requireConfirmation: true,
      },
      execute: () => {
        executed += 1;
        return '已重启';
      },
    });
    const controller = new DefaultAgentController({
      engine: runtime,
      conversations,
      tools,
      contextManager: testContextManager(),
      callTrace,
    });

    // 第一轮：模型要求执行需要确认的前端 Tool，等待确认时用户取消
    model.scripts.push(scriptedToolUse('call-1', 'page.restart'));
    const first = controller.sendMessage('第一轮');
    await vi.waitFor(() =>
      expect(controller.getState().pendingConfirmation).not.toBeNull());
    controller.abort();
    await first;

    expect(executed).toBe(0);
    expect(controller.getState().runOutcome).toEqual({ type: 'cancelled' });
    // 当前页历史包含明确的取消记录，Assistant Tool Call 不再悬空
    const visibleTools = controller.getState().messages.filter(message =>
      message.role === 'tool');
    expect(visibleTools).toHaveLength(1);
    expect(visibleTools[0]).toMatchObject({
      role: 'tool',
      blocks: [{
        type: 'tool-result',
        callId: 'call-1',
        name: 'page.restart',
        status: 'error',
      }],
    });
    expect(JSON.stringify(visibleTools[0])).toContain('未执行');

    // 第二轮：出站消息必须严格配对，不允许把未闭合 Tool Call 发给 Provider
    model.scripts.push(scriptedText('好的，已跳过重启'));
    const second = controller.sendMessage('第二轮');
    await vi.waitFor(() =>
      expect(conversations.createdTitles).toHaveLength(1));
    // 第二轮模型请求在运行开始即发出，等待运行收敛后统一断言
    await vi.waitFor(() => expect(model.requests).toHaveLength(2));
    await second;
    const secondRequest = model.requests[1];
    expect(secondRequest).toHaveLength(4);
    expect(secondRequest[0]).toMatchObject({ role: 'user' });
    expect(secondRequest[1]).toMatchObject({
      role: 'assistant',
      blocks: [{ type: 'tool-call', callId: 'call-1' }],
    });
    expect(secondRequest[2]).toMatchObject({
      role: 'tool',
      blocks: [{
        type: 'tool-result',
        callId: 'call-1',
        status: 'error',
      }],
    });
    expect(secondRequest[3]).toMatchObject({ role: 'user' });
    expect(JSON.stringify(secondRequest)).not.toContain('"type":"tool-call","callId":"call-2"');

    // 轨迹存活且未被回填校验拒绝：取消记录关联到已登记的 Tool Call
    const traces = controller.getCallTraceSource().snapshot().traces;
    expect(traces.length).toBeGreaterThanOrEqual(1);
  });
});
