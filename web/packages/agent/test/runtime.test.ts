/**
 * DefaultAgentRuntime 测试：用脚本化 Model AsyncIterable 验证厂商中立 Agent Loop。
 *
 * <p>测试关注稳定 ContentBlock、本轮唯一 Tool 快照、ModelState 续接、有界循环、
 * Human-in-the-loop 和取消边界；不允许重新引入厂商 chunk 或历史消息兼容假设。
 */
import { DefaultContextManager } from '../src/contextManager';
import { describe, expect, it, vi } from 'vitest';
import type {
  Model,
  ModelCallContext,
  ModelRequest,
  ModelStreamEvent,
  ModelUsage,
} from '../src/clients/modelClient';
import type {
  AgentExecution,
  AgentExecutionEvent,
  AgentRunInput,
} from '../src/engine';
import type {
  AgentHook,
  AgentLifecycleEvent,
} from '../src/extensions';
import { DefaultAgentRuntime } from '../src/runtime';
import type { AgentExecutionLimits } from '../src/runtime';
import type { ToolRegistrySnapshot } from '../src/toolRegistry';
import type {
  AgentMessage,
  JsonObject,
  ModelState,
  ToolCallResult,
  ToolDefinition,
  ToolResultBlock,
} from '../src/types';
import {
  TEST_MODEL_USAGE,
  TEST_TARGET,
  testContextManager,
  testModelContext,
} from './testContext';

/** 脚本化模型调用记录，用于验证 Runtime 传入的稳定上下文。 */
interface ModelCall {
  /** 本次完整厂商中立请求。 */
  readonly request: ModelRequest;
  /** Runtime 生成的链路上下文。 */
  readonly context: ModelCallContext;
}

/**
 * 每次 stream 消费一段结构化事件脚本；特殊取消测试可以替换 streamImpl，
 * 在事件之间设置异步闸门，而无需改变 Runtime 实现。
 */
class ScriptedModel implements Model {
  /** 按调用顺序记录所有请求。 */
  public readonly calls: ModelCall[] = [];
  /** 默认每次模型调用消费一段事件数组。 */
  public scripts: ModelStreamEvent[][] = [];
  /** 可选自定义流实现，只用于需要精确控制时序的测试。 */
  public streamImpl: ((signal: AbortSignal) => AsyncIterable<ModelStreamEvent>) | null = null;

  /** 返回当前脚本对应的 AsyncIterable。 */
  async *stream(
    request: ModelRequest,
    context: ModelCallContext,
    signal: AbortSignal,
  ): AsyncIterable<ModelStreamEvent> {
    // Model 端口在入口观察到的是一次调用快照；测试记录必须避免后续工作数组引用变化。
    this.calls.push({
      request: {
        ...request,
        messages: [...request.messages],
        tools: [...request.tools],
      },
      context: { ...context },
    });
    const custom = this.streamImpl;
    if (custom != null) {
      for await (const event of custom(signal)) {
        yield event;
      }
      return;
    }
    for (const event of this.scripts.shift() ?? []) {
      yield event;
    }
  }
}

/** Tool 快照调用记录，证明定义和执行来自同一冻结对象。 */
interface ToolInvocation {
  /** 被调度的完整 Tool 名称。 */
  readonly name: string;
  /** 已完成 JSON 解析的业务参数。 */
  readonly arguments: JsonObject;
  /** 模型生成的 Tool Call 标识。 */
  readonly toolCallId: string;
}

/** 创建具有明确风险注记的测试 Tool。 */
function toolNamed(name: string, requireConfirmation = false): ToolDefinition {
  return {
    name,
    title: name,
    description: '测试工具',
    inputSchema: { type: 'object' },
    annotations: {
      readOnlyHint: !requireConfirmation,
      destructiveHint: requireConfirmation,
      idempotentHint: true,
      requireConfirmation,
    },
    source: 'LOCAL',
    permissions: [],
  };
}

/** 创建本轮唯一 Tool 快照，并暴露实际调用记录。 */
function createToolSnapshot(tools: readonly ToolDefinition[]): {
  readonly snapshot: ToolRegistrySnapshot;
  readonly invocations: ToolInvocation[];
} {
  const invocations: ToolInvocation[] = [];
  const snapshot: ToolRegistrySnapshot = {
    revision: 7,
    tools,
    invoke: async (name, arguments_, context) => {
      invocations.push({ name, arguments: arguments_, toolCallId: context.toolCallId });
      return {
        toolCallId: context.toolCallId,
        content: '工具执行成功',
        isError: false,
      };
    },
  };
  return { snapshot, invocations };
}

/** 创建包含一条稳定用户消息的执行输入。 */
function baseInput(
  toolSnapshot: ToolRegistrySnapshot,
  modelState: ModelState | null = null,
): AgentRunInput {
  return {
    conversation: {
      messages: [{
        id: 'user-1',
        role: 'user',
        blocks: [{ type: 'text', text: '查询设备' }],
      }],
      modelTarget: TEST_TARGET,
      modelContext: testModelContext(modelState),
    },
    toolSnapshot,
    conversationId: 'conversation-1',
    traceId: 'trace-1',
  };
}

/** 用真实持久化块表达上一轮未知结果；文本刻意不带关键词，恢复不得解析提示文案。 */
function unverifiedInput(snapshot: ToolRegistrySnapshot): AgentRunInput {
  const input = baseInput(snapshot);
  return {
    ...input,
    conversation: {
      ...input.conversation,
      messages: [
        { id: 'old-call', role: 'assistant', blocks: [{
          type: 'tool-call', callId: 'old-id', name: 'local.query', input: {},
        }] },
        { id: 'old-result', role: 'tool', blocks: [{
          type: 'tool-result', callId: 'old-id', name: 'local.query',
          status: 'error', execution: 'unknown', content: [{ type: 'text', text: '中止记录' }],
        }] },
        ...input.conversation.messages,
      ],
    },
  };
}

/** 创建 ID 可预测的 Runtime，避免测试依赖随机 UUID。 */
function createRuntime(model: Model, maxModelCalls = 4): DefaultAgentRuntime {
  const counters = { message: 0, interrupt: 0 };
  return new DefaultAgentRuntime(model, {
    limits: testLimits({ maxModelCalls }),
    contextManager: testContextManager(),
    createId: kind => `${kind}-${++counters[kind]}`,
  });
}

/** 测试统一使用的完整资源预算；单项边界由用例显式覆盖。 */
function testLimits(
  overrides: Partial<AgentExecutionLimits> = {},
): AgentExecutionLimits {
  return {
    maxModelCalls: 4,
    maxToolCalls: 8,
    maxDurationMs: 10_000,
    maxModelOutputCharacters: 10_000,
    maxToolResultCharacters: 10_000,
    ...overrides,
  };
}

/** 构造完整文本消息事件。 */
function textResponse(
  text: string,
  modelState: ModelState | null = null,
  usage: ModelUsage | null = TEST_MODEL_USAGE,
): ModelStreamEvent[] {
  return [
    { type: 'block-start', index: 0, block: { type: 'text' } },
    { type: 'block-delta', index: 0, delta: { type: 'text', text } },
    { type: 'block-stop', index: 0 },
    { type: 'message-stop', stopReason: 'end-turn', usage, modelState },
  ];
}

/** 构造一个参数分片到达的 Tool Call 消息。 */
function toolResponse(
  name: string,
  modelState: ModelState | null = null,
): ModelStreamEvent[] {
  return [
    { type: 'block-start', index: 0, block: { type: 'reasoning' } },
    { type: 'block-delta', index: 0, delta: { type: 'reasoning', text: '先查询设备' } },
    { type: 'block-stop', index: 0 },
    {
      type: 'block-start',
      index: 1,
      block: { type: 'tool-call', callId: 'call-1', name },
    },
    {
      type: 'block-delta',
      index: 1,
      delta: { type: 'tool-call', argumentsDelta: '{"serial' },
    },
    {
      type: 'block-delta',
      index: 1,
      delta: { type: 'tool-call', argumentsDelta: '":"DEV-1"}' },
    },
    { type: 'block-stop', index: 1 },
    { type: 'message-stop', stopReason: 'tool-use', usage: TEST_MODEL_USAGE, modelState },
  ];
}

/** 手工构造标准 AbortError，模拟底层 Model 对 AbortSignal 的响应。 */
function nativeAbortError(): Error {
  const error = new Error('The operation was aborted');
  error.name = 'AbortError';
  return error;
}

describe('DefaultAgentRuntime', () => {
  it('按 block index 生成有序 ContentBlock，并分别发布正文与思考增量', async () => {
    const model = new ScriptedModel();
    const state: ModelState = {
      format: 'provider-state/v1',
      data: { responseMessageId: 'message-1' },
    };
    model.scripts = [[
      { type: 'block-start', index: 1, block: { type: 'text' } },
      { type: 'block-delta', index: 1, delta: { type: 'text', text: '最终答案' } },
      { type: 'block-stop', index: 1 },
      { type: 'block-start', index: 0, block: { type: 'reasoning' } },
      { type: 'block-delta', index: 0, delta: { type: 'reasoning', text: '推理摘要' } },
      { type: 'block-stop', index: 0 },
      { type: 'message-stop', stopReason: 'end-turn', usage: TEST_MODEL_USAGE, modelState: state },
    ]];
    const { snapshot } = createToolSnapshot([]);
    const events: AgentExecutionEvent[] = [];

    const execution = createRuntime(model).start(baseInput(snapshot), event => events.push(event));
    const result = await execution.result;

    expect(result).toEqual({
      messages: [{
        id: 'message-1',
        role: 'assistant',
        blocks: [
          { type: 'reasoning', text: '推理摘要' },
          { type: 'text', text: '最终答案' },
        ],
      }],
      modelContext: {
        checkpoint: null,
        firstRetainedMessageId: null,
        modelState: state,
        usage: {
          totalTokens: TEST_MODEL_USAGE.totalTokens,
          source: 'provider',
          toolDefinitionTokens: 0,
          measuredThroughMessageId: 'message-1',
        },
      },
      outcome: { type: 'completed', stopReason: 'end-turn' },
    });
    expect(events.filter(event => event.type === 'reasoning-delta')).toEqual([
      { type: 'reasoning-delta', text: '推理摘要' },
    ]);
    expect(events.filter(event => event.type === 'text-delta')).toEqual([
      { type: 'text-delta', text: '最终答案' },
    ]);
  });

  it('Tool 参数完成后解析成对象，并用唯一快照完成定义、调用和结果回填', async () => {
    const firstState: ModelState = {
      format: 'provider-state/v1',
      data: { responseMessageId: 'message-1' },
    };
    const finalState: ModelState = {
      format: 'provider-state/v1',
      data: { responseMessageId: 'message-3' },
    };
    const initialState: ModelState = {
      format: 'provider-state/v1',
      data: { responseMessageId: 'history-1' },
    };
    const model = new ScriptedModel();
    model.scripts = [
      toolResponse('local.device_get', firstState),
      textResponse('设备正常', finalState),
    ];
    const { snapshot, invocations } = createToolSnapshot([
      toolNamed('local.device_get'),
    ]);

    const result = await createRuntime(model).start(
      baseInput(snapshot, initialState),
      () => {},
    ).result;

    expect(invocations).toEqual([{
      name: 'local.device_get',
      arguments: { serial: 'DEV-1' },
      toolCallId: 'call-1',
    }]);
    expect(Object.isFrozen(invocations[0]?.arguments)).toBe(true);
    expect(result.messages).toEqual([
      {
        id: 'message-1',
        role: 'assistant',
        blocks: [
          { type: 'reasoning', text: '先查询设备' },
          {
            type: 'tool-call',
            callId: 'call-1',
            name: 'local.device_get',
            input: { serial: 'DEV-1' },
          },
        ],
      },
      {
        id: 'message-2',
        role: 'tool',
        blocks: [{
          type: 'tool-result',
          callId: 'call-1',
          name: 'local.device_get',
          execution: 'completed',
          status: 'success',
          content: [{ type: 'text', text: '工具执行成功' }],
        }],
      },
      {
        id: 'message-3',
        role: 'assistant',
        blocks: [{ type: 'text', text: '设备正常' }],
      },
    ]);
    expect(model.calls[0]?.request).toMatchObject({
      responseMessageId: 'message-1',
      modelState: initialState,
      tools: [{
        name: 'local.device_get',
        description: '测试工具',
        inputSchema: { type: 'object' },
      }],
    });
    expect(model.calls[1]?.request.responseMessageId).toBe('message-3');
    expect(model.calls[1]?.request.modelState).toEqual(firstState);
    expect(model.calls[1]?.request.messages.at(-1)).toEqual(result.messages[1]);
    expect(Object.isFrozen(model.calls[0]?.request.messages[0])).toBe(true);
    expect(Object.isFrozen(result.messages[0]?.blocks)).toBe(true);
    expect(result.modelContext.modelState).toEqual(finalState);
  });

  it('Tool 确认以 interrupt 暂停，并由同一 Execution.respond 精确恢复', async () => {
    const model = new ScriptedModel();
    model.scripts = [
      toolResponse('local.device_restart'),
      textResponse('已重启'),
    ];
    const { snapshot, invocations } = createToolSnapshot([
      toolNamed('local.device_restart', true),
    ]);
    const events: AgentExecutionEvent[] = [];
    const execution = createRuntime(model).start(baseInput(snapshot), event => events.push(event));

    await vi.waitFor(() => {
      expect(events.some(event => event.type === 'interrupt')).toBe(true);
    });
    const interrupt = events.find(event => event.type === 'interrupt');
    expect(interrupt).toMatchObject({
      type: 'interrupt',
      interrupt: {
        id: 'interrupt-1',
        type: 'tool-confirmation',
        arguments: { serial: 'DEV-1' },
      },
    });
    execution.respond({ interruptId: 'interrupt-1', value: true });

    await expect(execution.result).resolves.toMatchObject({
      outcome: { type: 'completed', stopReason: 'end-turn' },
    });
    expect(invocations).toHaveLength(1);
  });

  it('等待 Tool 确认时取消会消费中断，且不会调用 Tool', async () => {
    const model = new ScriptedModel();
    model.scripts = [toolResponse('local.device_restart')];
    const { snapshot, invocations } = createToolSnapshot([
      toolNamed('local.device_restart', true),
    ]);
    const events: AgentExecutionEvent[] = [];
    const execution = createRuntime(model).start(baseInput(snapshot), event => events.push(event));

    await vi.waitFor(() => {
      expect(events.some(event => event.type === 'interrupt')).toBe(true);
    });
    execution.cancel();

    await expect(execution.result).resolves.toMatchObject({
      outcome: { type: 'cancelled' },
    });
    expect(invocations).toEqual([]);
    expect(() => execution.respond({ interruptId: 'interrupt-1', value: true }))
      .toThrow('已进入终态');
  });

  it('达到 maxModelCalls 时明确失败，不把无限 Tool 循环伪装成成功', async () => {
    const model = new ScriptedModel();
    model.scripts = [toolResponse('local.device_get')];
    const { snapshot, invocations } = createToolSnapshot([
      toolNamed('local.device_get'),
    ]);

    const result = createRuntime(model, 1).start(baseInput(snapshot), () => {}).result;

    await expect(result).rejects.toMatchObject({
      code: 'AGENT_MAX_MODEL_CALLS',
      retryable: false,
    });
    expect(model.calls).toHaveLength(1);
    expect(invocations).toHaveLength(0);
  });

  it('取消正在生成的块时不提交半截稳定消息', async () => {
    const model = new ScriptedModel();
    let release: (() => void) | null = null;
    const gate = new Promise<void>(resolve => {
      release = resolve;
    });
    model.streamImpl = async function* (signal): AsyncIterable<ModelStreamEvent> {
      yield { type: 'block-start', index: 0, block: { type: 'text' } };
      yield { type: 'block-delta', index: 0, delta: { type: 'text', text: '半截' } };
      await gate;
      if (signal.aborted) {
        throw nativeAbortError();
      }
      yield { type: 'block-stop', index: 0 };
      yield { type: 'message-stop', stopReason: 'end-turn', usage: TEST_MODEL_USAGE, modelState: null };
    };
    const { snapshot } = createToolSnapshot([]);
    const events: AgentExecutionEvent[] = [];
    const lifecycleEvents: AgentLifecycleEvent[] = [];
    let messageId = 0;
    const runtime = new DefaultAgentRuntime(model, {
      limits: testLimits(),
      contextManager: testContextManager(),
      hooks: [{ onEvent: event => lifecycleEvents.push(event) }],
      createId: kind => kind === 'message' ? `message-${++messageId}` : 'interrupt-1',
    });
    const execution = runtime.start(baseInput(snapshot), event => events.push(event));

    await vi.waitFor(() => {
      expect(events).toContainEqual({ type: 'text-delta', text: '半截' });
    });
    execution.cancel();
    release?.();

    await expect(execution.result).resolves.toEqual({
      messages: [],
      modelContext: testModelContext(),
      outcome: { type: 'cancelled' },
    });
    // 取消会无条件发布一次与稳定历史一致的快照；任何快照都不得携带半截消息。
    for (const event of events) {
      if (event.type === 'messages') {
        expect(event.messages).toEqual([]);
      }
    }
    expect(lifecycleEvents.some(event => event.type === 'model-call-started')).toBe(true);
    expect(lifecycleEvents.some(event => event.type === 'model-call-completed')).toBe(false);
  });

  it('message-stop 后的取消与清理错误不推翻稳定模型结果', async () => {
    const model = new ScriptedModel();
    const { snapshot } = createToolSnapshot([]);
    const lifecycleEvents: AgentLifecycleEvent[] = [];
    const samples = [
      0,
      100,
      100,
      100,
      100,
      100,
      100,
      100,
      250,
      250,
      250,
      250,
      350,
      350,
      350,
      350,
    ];
    let sampleIndex = 0;
    let execution: AgentExecution | null = null;
    model.streamImpl = async function* (): AsyncIterable<ModelStreamEvent> {
      try {
        for (const event of textResponse('稳定结果', null, {
          inputTokens: 20,
          outputTokens: 10,
          totalTokens: 30,
        })) {
          yield event;
        }
      } finally {
        execution?.cancel();
        throw {
          code: 'NETWORK_ERROR',
          message: 'message-stop 后的迟到清理错误',
          retryable: true,
        };
      }
    };
    const runtime = new DefaultAgentRuntime(model, {
      limits: testLimits({ maxModelCalls: 1 }),
      contextManager: testContextManager(),
      hooks: [{ onEvent: event => lifecycleEvents.push(event) }],
      createId: () => 'message-1',
      now: () => {
        const sample = samples[sampleIndex++];
        if (sample == null) {
          throw new Error('测试时钟样本不足');
        }
        return sample;
      },
    });

    execution = runtime.start(baseInput(snapshot), () => {});

    await expect(execution.result).resolves.toMatchObject({
      outcome: { type: 'completed', stopReason: 'end-turn' },
      messages: [{ role: 'assistant', blocks: [{ type: 'text', text: '稳定结果' }] }],
    });
    expect(lifecycleEvents.find(event => event.type === 'model-call-completed')).toMatchObject({
      firstTokenLatencyMs: 150,
      outputDurationMs: 100,
    });
    expect(lifecycleEvents.at(-1)).toMatchObject({
      type: 'execution-completed',
      outcome: { type: 'completed', stopReason: 'end-turn' },
    });
    expect(sampleIndex).toBe(samples.length);
  });

  it('结构化事件乱序时以 MODEL_PROTOCOL_ERROR 失败', async () => {
    const model = new ScriptedModel();
    model.scripts = [[
      { type: 'block-delta', index: 0, delta: { type: 'text', text: '非法顺序' } },
    ]];
    const { snapshot } = createToolSnapshot([]);

    await expect(
      createRuntime(model).start(baseInput(snapshot), () => {}).result,
    ).rejects.toMatchObject({ code: 'MODEL_PROTOCOL_ERROR', retryable: false });
  });

  it('Tool 参数不是 JSON 对象时拒绝形成稳定 ToolCallBlock', async () => {
    const model = new ScriptedModel();
    model.scripts = [[
      {
        type: 'block-start',
        index: 0,
        block: { type: 'tool-call', callId: 'call-1', name: 'local.device_get' },
      },
      {
        type: 'block-delta',
        index: 0,
        delta: { type: 'tool-call', argumentsDelta: '[]' },
      },
      { type: 'block-stop', index: 0 },
      { type: 'message-stop', stopReason: 'tool-use', usage: TEST_MODEL_USAGE, modelState: null },
    ]];
    const { snapshot } = createToolSnapshot([toolNamed('local.device_get')]);

    await expect(
      createRuntime(model).start(baseInput(snapshot), () => {}).result,
    ).rejects.toMatchObject({ code: 'MODEL_PROTOCOL_ERROR' });
  });

  it('模型完成事件包含首 token 延迟与首 token 后输出耗时', async () => {
    const model = new ScriptedModel();
    model.scripts = [[
      { type: 'block-start', index: 0, block: { type: 'text' } },
      { type: 'block-delta', index: 0, delta: { type: 'text', text: '' } },
      { type: 'block-delta', index: 0, delta: { type: 'text', text: '完成' } },
      { type: 'block-stop', index: 0 },
      {
        type: 'message-stop',
        stopReason: 'end-turn',
        usage: { inputTokens: 20, outputTokens: 102, totalTokens: 122 },
        modelState: null,
      },
    ]];
    const { snapshot } = createToolSnapshot([]);
    const events: AgentLifecycleEvent[] = [];
    const samples = [
      0,
      1_000,
      1_000,
      1_000,
      1_000,
      1_000,
      1_000,
      1_000,
      1_000,
      41_600,
      41_600,
      41_600,
      41_600,
      42_600,
      42_600,
      42_600,
      42_600,
      42_600,
    ];
    let sampleIndex = 0;
    const runtime = new DefaultAgentRuntime(model, {
      limits: testLimits({ maxModelCalls: 1, maxDurationMs: 100_000 }),
      contextManager: testContextManager(),
      hooks: [{ onEvent: event => events.push(event) }],
      createId: () => 'message-1',
      now: () => {
        const sample = samples[sampleIndex++];
        if (sample == null) {
          throw new Error('测试时钟样本不足');
        }
        return sample;
      },
    });

    await runtime.start(baseInput(snapshot), () => {}).result;

    expect(events.find(event => event.type === 'model-call-completed')).toMatchObject({
      firstTokenLatencyMs: 40_600,
      outputDurationMs: 1000,
      usage: { outputTokens: 102 },
    });
    expect(sampleIndex).toBe(samples.length);
  });

  it('性能计时时钟回退时明确失败且不发布模型完成事件', async () => {
    const model = new ScriptedModel();
    model.scripts = [textResponse('完成')];
    const { snapshot } = createToolSnapshot([]);
    const events: AgentLifecycleEvent[] = [];
    const samples = [100, 90, 200];
    let sampleIndex = 0;
    const runtime = new DefaultAgentRuntime(model, {
      limits: testLimits({ maxModelCalls: 1 }),
      contextManager: testContextManager(),
      hooks: [{ onEvent: event => events.push(event) }],
      createId: () => 'message-1',
      now: () => samples[sampleIndex++] ?? Number.NaN,
    });

    await expect(runtime.start(baseInput(snapshot), () => {}).result)
      .rejects.toThrow('Runtime now 时钟必须返回不回退的有限毫秒值');

    expect(events.some(event => event.type === 'model-call-completed')).toBe(false);
    expect(events.at(-1)).toMatchObject({
      type: 'execution-failed',
      errorCode: 'AGENT_EXECUTION_FAILED',
    });
  });

  it('首个非空 Tool 参数增量可以作为首 token 计时起点', async () => {
    const model = new ScriptedModel();
    model.scripts = [[
      {
        type: 'block-start',
        index: 0,
        block: { type: 'tool-call', callId: 'call-1', name: 'local.device_get' },
      },
      {
        type: 'block-delta',
        index: 0,
        delta: { type: 'tool-call', argumentsDelta: '{"serial":"DEV-1"}' },
      },
      { type: 'block-stop', index: 0 },
      {
        type: 'message-stop',
        stopReason: 'tool-use',
        usage: { inputTokens: 20, outputTokens: 10, totalTokens: 30 },
        modelState: null,
      },
    ], textResponse('Tool 调用后的最终回答')];
    const { snapshot } = createToolSnapshot([toolNamed('local.device_get')]);
    const events: AgentLifecycleEvent[] = [];
    const samples = [
      0,
      100,
      100,
      100,
      100,
      100,
      100,
      100,
      250,
      250,
      250,
      250,
      350,
      350,
      350,
      350,
    ];
    let sampleIndex = 0;
    let messageId = 0;
    const runtime = new DefaultAgentRuntime(model, {
      limits: testLimits({ maxModelCalls: 2 }),
      contextManager: testContextManager(),
      hooks: [{ onEvent: event => events.push(event) }],
      createId: kind => kind === 'message' ? `message-${++messageId}` : 'interrupt-1',
      now: () => {
        return samples[sampleIndex++] ?? 350;
      },
    });

    await expect(runtime.start(baseInput(snapshot), () => {}).result)
      .resolves.toMatchObject({
        outcome: { type: 'completed', stopReason: 'end-turn' },
      });

    expect(events.find(event => event.type === 'model-call-completed')).toMatchObject({
      firstTokenLatencyMs: 150,
      outputDurationMs: 100,
    });
    expect(sampleIndex).toBeGreaterThanOrEqual(samples.length);
  });

  it('Hook 按稳定生命周期顺序观察执行，且不接收正文或思考 token 增量', async () => {
    const model = new ScriptedModel();
    model.scripts = [
      toolResponse('local.device_get'),
      textResponse('设备正常', null, {
        inputTokens: 120,
        outputTokens: 8,
        totalTokens: 128,
      }),
    ];
    const { snapshot } = createToolSnapshot([toolNamed('local.device_get')]);
    const hookEvents: AgentLifecycleEvent[] = [];
    const hookContexts: Array<{ traceId: string; conversationId: string | null }> = [];
    const hook: AgentHook = {
      onEvent: (event, context) => {
        hookEvents.push(event);
        hookContexts.push(context);
      },
    };
    let messageIds = 0;
    const runtime = new DefaultAgentRuntime(model, {
      limits: testLimits(),
      contextManager: testContextManager(),
      hooks: [hook],
      createId: kind => kind === 'message'
        ? `message-${++messageIds}`
        : 'interrupt-unexpected',
    });

    await runtime.start(baseInput(snapshot), () => {}).result;

    expect(hookEvents.map(event => event.type)).toEqual([
      'execution-started',
      'model-call-started',
      'model-call-completed',
      'tool-call-started',
      'tool-call-completed',
      'model-call-started',
      'model-call-completed',
      'execution-completed',
    ]);
    expect(hookContexts.every(context =>
      context.traceId === 'trace-1'
      && context.conversationId === 'conversation-1')).toBe(true);
    expect(hookEvents.some(event => event.type.includes('delta'))).toBe(false);
    const completed = hookEvents.filter(event => event.type === 'model-call-completed');
    expect(completed[1]).toMatchObject({
      usage: { inputTokens: 120, outputTokens: 8, totalTokens: 128 },
    });
    expect(JSON.stringify(hookEvents)).not.toContain('设备正常');
    expect(JSON.stringify(hookEvents)).not.toContain('先查询设备');
    expect(JSON.stringify(hookEvents)).not.toContain('modelState');
  });

  it('Hook 只能观察 Tool 参数，不能通过嵌套引用改写真实调用', async () => {
    const model = new ScriptedModel();
    model.scripts = [
      toolResponse('local.device_get'),
      textResponse('设备正常'),
    ];
    const { snapshot, invocations } = createToolSnapshot([
      toolNamed('local.device_get'),
    ]);
    let mutationAccepted: boolean | null = null;
    let messageId = 0;
    const runtime = new DefaultAgentRuntime(model, {
      limits: testLimits(),
      contextManager: testContextManager(),
      hooks: [{
        onEvent: event => {
          if (event.type === 'tool-call-started') {
            mutationAccepted = Reflect.set(event.arguments, 'serial', 'MUTATED');
          }
        },
      }],
      createId: kind => kind === 'message' ? `message-${++messageId}` : 'interrupt-1',
    });

    await runtime.start(baseInput(snapshot), () => {}).result;

    expect(mutationAccepted).toBe(false);
    expect(invocations[0]?.arguments).toEqual({ serial: 'DEV-1' });
  });

  it('Execution 异常通过稳定 failure Hook 收口，且原异常继续传播', async () => {
    const model = new ScriptedModel();
    model.scripts = [toolResponse('local.device_get')];
    const { snapshot } = createToolSnapshot([toolNamed('local.device_get')]);
    const hookEvents: AgentLifecycleEvent[] = [];
    let messageId = 0;
    const runtime = new DefaultAgentRuntime(model, {
      limits: testLimits({ maxModelCalls: 1 }),
      contextManager: testContextManager(),
      hooks: [{ onEvent: event => hookEvents.push(event) }],
      createId: kind => kind === 'message' ? `message-${++messageId}` : 'interrupt-1',
    });

    await expect(runtime.start(baseInput(snapshot), () => {}).result)
      .rejects.toMatchObject({ code: 'AGENT_MAX_MODEL_CALLS' });
    expect(hookEvents.at(-1)).toEqual({
      type: 'execution-failed',
      errorCode: 'AGENT_MAX_MODEL_CALLS',
      errorMessage: '模型调用次数达到上限 1，已终止以防止无限循环',
    });
  });

  // ---------- VA-03：取消/失败终态的继续契约 ----------

  /** 构造同批两个 Tool Call 的模型响应。 */
  function twoToolResponse(first: string, second: string): ModelStreamEvent[] {
    return [
      {
        type: 'block-start',
        index: 0,
        block: { type: 'tool-call', callId: 'call-1', name: first },
      },
      { type: 'block-delta', index: 0, delta: { type: 'tool-call', argumentsDelta: '{}' } },
      { type: 'block-stop', index: 0 },
      {
        type: 'block-start',
        index: 1,
        block: { type: 'tool-call', callId: 'call-2', name: second },
      },
      { type: 'block-delta', index: 1, delta: { type: 'tool-call', argumentsDelta: '{}' } },
      { type: 'block-stop', index: 1 },
      { type: 'message-stop', stopReason: 'tool-use', usage: TEST_MODEL_USAGE, modelState: null },
    ];
  }

  it('VA-03：确认前取消补写“未执行”记录，原 Tool 调用次数为零', async () => {
    const { snapshot, invocations } = createToolSnapshot([toolNamed('local.restart', true)]);
    const model = new ScriptedModel();
    model.scripts = [toolResponse('local.restart'), textResponse('不应调用')];
    const events: AgentExecutionEvent[] = [];

    const execution = createRuntime(model).start(
      baseInput(snapshot),
      event => events.push(event),
    );
    await vi.waitFor(() =>
      expect(events.some(event => event.type === 'interrupt')).toBe(true));
    execution.cancel();
    const result = await execution.result;

    expect(result.outcome).toEqual({ type: 'cancelled' });
    expect(invocations).toHaveLength(0);
    // 稳定历史：assistant Tool Call 后紧跟明确的取消记录，严格配对
    expect(result.messages).toHaveLength(2);
    expect(result.messages[1]).toMatchObject({
      role: 'tool',
      blocks: [{
        type: 'tool-result',
        callId: 'call-1',
        name: 'local.restart',
        status: 'error',
        content: [{ text: expect.stringContaining('未执行') }],
      }],
    });
    // 终态前发布了一次包含记录的稳定消息事件
    const committed = events.filter(event => event.type === 'messages');
    expect(committed.at(-1)?.messages).toHaveLength(2);
  });

  it('VA-03：多 Tool 批次部分完成后取消，已知结果与未执行部分分别表达', async () => {
    const { snapshot, invocations } = createToolSnapshot([
      toolNamed('local.query', false),
      toolNamed('local.restart', true),
    ]);
    const model = new ScriptedModel();
    model.scripts = [
      twoToolResponse('local.query', 'local.restart'),
      textResponse('不应调用'),
    ];
    const events: AgentExecutionEvent[] = [];

    const execution = createRuntime(model).start(
      baseInput(snapshot),
      event => events.push(event),
    );
    // call-1 直接执行并发布结果，call-2 等待确认时取消
    await vi.waitFor(() =>
      expect(events.some(event => event.type === 'interrupt')).toBe(true));
    execution.cancel();
    const result = await execution.result;

    expect(invocations).toHaveLength(1);
    expect(result.messages).toHaveLength(3);
    // call-1 的真实结果不被取消抹除
    expect(result.messages[1]).toMatchObject({
      role: 'tool',
      blocks: [{
        type: 'tool-result',
        callId: 'call-1',
        execution: 'completed',
        status: 'success',
        content: [{ text: '工具执行成功' }],
      }],
    });
    // call-2 明确记为未执行
    expect(result.messages[2]).toMatchObject({
      role: 'tool',
      blocks: [{
        type: 'tool-result',
        callId: 'call-2',
        status: 'error',
        content: [{ text: expect.stringContaining('未执行') }],
      }],
    });
  });

  it('VA-03：调用已发出后取消表达“结果未知”，不假设成功或失败', async () => {
    const snapshot: ToolRegistrySnapshot = {
      revision: 7,
      tools: [toolNamed('local.slow')],
      invoke: () => new Promise<ToolCallResult>(() => undefined),
    };
    const model = new ScriptedModel();
    model.scripts = [toolResponse('local.slow')];
    const events: AgentExecutionEvent[] = [];

    const execution = createRuntime(model).start(
      baseInput(snapshot),
      event => events.push(event),
    );
    await vi.waitFor(() =>
      expect(events.some(event => event.type === 'tool-call')).toBe(true));
    execution.cancel();
    const result = await execution.result;

    expect(result.outcome).toEqual({ type: 'cancelled' });
    expect(result.messages).toHaveLength(2);
    expect(result.messages[1]).toMatchObject({
      role: 'tool',
      blocks: [{
        type: 'tool-result',
        callId: 'call-1',
        status: 'error',
        content: [{ text: expect.stringContaining('结果未知') }],
      }],
    });
    expect(JSON.stringify(result.messages[1])).toContain('核实');
  });

  it('VA-03：失败终态同样补写记录，历史不留下未配对 Tool Call', async () => {
    const adapterFailure = new Error('Tool Adapter 连接失败');
    const snapshot: ToolRegistrySnapshot = {
      revision: 7,
      tools: [toolNamed('local.failure')],
      invoke: () => Promise.reject(adapterFailure),
    };
    const model = new ScriptedModel();
    model.scripts = [toolResponse('local.failure'), textResponse('不应调用')];
    const events: AgentExecutionEvent[] = [];

    await expect(createRuntime(model).start(
      baseInput(snapshot),
      event => events.push(event),
    ).result).rejects.toBe(adapterFailure);

    // 调用已发出但结果未知：失败路径发布一次包含记录的稳定消息
    const committed = events.filter(event => event.type === 'messages');
    expect(committed).toHaveLength(2);
    expect(committed[1]?.messages).toHaveLength(2);
    expect(committed[1]?.messages[1]).toMatchObject({
      role: 'tool',
      blocks: [{
        type: 'tool-result',
        callId: 'call-1',
        status: 'error',
        content: [{ text: expect.stringContaining('结果未知') }],
      }],
    });
  });

  it('P1-4：Assistant 消息发布时取消，未执行 Tool Call 仍补写配对终态记录', async () => {
    const { snapshot, invocations } = createToolSnapshot([toolNamed('local.restart', true)]);
    const model = new ScriptedModel();
    model.scripts = [toolResponse('local.restart'), textResponse('不应调用')];
    const events: AgentExecutionEvent[] = [];
    let execution: AgentExecution | null = null;
    const counters = { message: 0, interrupt: 0 };
    const runtime = new DefaultAgentRuntime(model, {
      limits: testLimits(),
      contextManager: testContextManager(),
      hooks: [{
        onEvent: event => {
          // 审查复现：Hook 回调中同步取消，此刻 Assistant 消息刚稳定提交
          if (event.type === 'model-call-completed') {
            execution?.cancel();
          }
        },
      }],
      createId: kind => `${kind}-${++counters[kind]}`,
    });
    execution = runtime.start(baseInput(snapshot), event => events.push(event));
    const result = await execution.result;

    expect(result.outcome).toEqual({ type: 'cancelled' });
    expect(invocations).toHaveLength(0);
    // Assistant Tool Call 之后必须紧跟明确的“未执行”记录，历史严格配对
    expect(result.messages).toHaveLength(2);
    expect(result.messages[1]).toMatchObject({
      role: 'tool',
      blocks: [{
        type: 'tool-result',
        callId: 'call-1',
        status: 'error',
        content: [{ text: expect.stringContaining('未执行') }],
      }],
    });
    // 最终发布的 messages 快照与 result 稳定历史一致
    const committed = events.filter(event => event.type === 'messages').at(-1);
    expect(committed?.messages).toHaveLength(2);
  });

  it('P1-4：工具消息发布回调中取消，已收敛的真实结果不重复记录为结果未知', async () => {
    const { snapshot, invocations } = createToolSnapshot([toolNamed('local.query')]);
    const model = new ScriptedModel();
    model.scripts = [toolResponse('local.query'), textResponse('不应调用')];
    let execution: AgentExecution | null = null;
    const runtime = createRuntime(model);

    execution = runtime.start(baseInput(snapshot), event => {
      // 审查复现：订阅回调里对含 Tool Result 的 messages 快照同步取消
      if (event.type === 'messages'
          && event.messages.some(message => message.role === 'tool')) {
        execution?.cancel();
      }
    });
    const result = await execution.result;

    expect(result.outcome).toEqual({ type: 'cancelled' });
    expect(invocations).toHaveLength(1);
    const callResults = result.messages
      .flatMap(message => message.blocks)
      .filter((block): block is ToolResultBlock =>
        block.type === 'tool-result' && block.callId === 'call-1');
    // 同一调用只允许出现一次真实成功结果，绝不同时携带“结果未知”记录
    expect(callResults).toHaveLength(1);
    expect(callResults[0]).toMatchObject({
      status: 'success',
      content: [{ text: '工具执行成功' }],
    });
  });

  it('P1-4：完成 Hook 中取消，真实成功结果保留而不被改写为结果未知', async () => {
    const { snapshot, invocations } = createToolSnapshot([toolNamed('local.query')]);
    const model = new ScriptedModel();
    model.scripts = [toolResponse('local.query'), textResponse('不应调用')];
    let execution: AgentExecution | null = null;
    const counters = { message: 0, interrupt: 0 };
    const runtime = new DefaultAgentRuntime(model, {
      limits: testLimits(),
      contextManager: testContextManager(),
      hooks: [{
        onEvent: event => {
          // 审查复现：结果已真实返回，取消发生在完成 Hook 回调中
          if (event.type === 'tool-call-completed') {
            execution?.cancel();
          }
        },
      }],
      createId: kind => `${kind}-${++counters[kind]}`,
    });
    execution = runtime.start(baseInput(snapshot), () => {});
    const result = await execution.result;

    expect(result.outcome).toEqual({ type: 'cancelled' });
    expect(invocations).toHaveLength(1);
    const callResults = result.messages
      .flatMap(message => message.blocks)
      .filter((block): block is ToolResultBlock =>
        block.type === 'tool-result' && block.callId === 'call-1');
    expect(callResults).toHaveLength(1);
    expect(callResults[0]).toMatchObject({ status: 'success' });
    expect(JSON.stringify(result.messages)).not.toContain('结果未知');
  });

  it('P1-1：未核实集合中的只读 Tool 强制人工确认并携带原因', async () => {
    const { snapshot, invocations } = createToolSnapshot([toolNamed('local.query')]);
    const model = new ScriptedModel();
    model.scripts = [toolResponse('local.query'), textResponse('已完成')];
    const events: AgentExecutionEvent[] = [];
    const runtime = createRuntime(model);

    // local.query 声明只读，但上一轮存在结果未核实的调用：必须先人工核实。
    const input = unverifiedInput(snapshot);
    const execution = runtime.start(input, event => events.push(event));
    await vi.waitFor(() =>
      expect(events.some(event => event.type === 'interrupt')).toBe(true));
    execution.respond({ interruptId: 'interrupt-1', value: true });
    const result = await execution.result;

    expect(result.outcome).toEqual({ type: 'completed', stopReason: 'end-turn' });
    expect(invocations).toHaveLength(1);
    const interrupt = events.find(event => event.type === 'interrupt');
    if (interrupt?.type === 'interrupt' && interrupt.interrupt.type === 'tool-confirmation') {
      expect(interrupt.interrupt.reason).toBe('unverified-previous-invocation');
    } else {
      expect.unreachable('必须发布 Tool 确认中断');
    }
    // 真实结果落地后未核实状态解除
    expect(result.messages.flatMap(message => message.blocks)).toContainEqual(
      expect.objectContaining({ type: 'tool-result', execution: 'completed' }),
    );
  });

  it('P1-1：结果未知的调用保持未核实，等待确认的取消不产生未核实事实', async () => {
    const model = new ScriptedModel();
    const runtime = createRuntime(model);

    // 场景一：等待确认时取消——调用从未执行，不产生未核实事实。
    const confirmSnapshot = createToolSnapshot([toolNamed('local.restart', true)]);
    model.scripts = [toolResponse('local.restart'), textResponse('不应调用')];
    const events: AgentExecutionEvent[] = [];
    const confirmExecution = runtime.start(
      baseInput(confirmSnapshot.snapshot), event => events.push(event));
    await vi.waitFor(() =>
      expect(events.some(event => event.type === 'interrupt')).toBe(true));
    confirmExecution.cancel();
    const confirmed = await confirmExecution.result;
    expect(confirmed.messages.flatMap(message => message.blocks)).toContainEqual(
      expect.objectContaining({ type: 'tool-result', execution: 'not-executed' }),
    );

    // 场景二：调用已发出后取消——结果未知，Tool 名保持未核实，输入集合也保留。
    const slowModel = new ScriptedModel();
    slowModel.scripts = [toolResponse('local.slow')];
    const slowRuntime = createRuntime(slowModel);
    const slowSnapshot: ToolRegistrySnapshot = {
      revision: 7,
      tools: [toolNamed('local.slow')],
      invoke: () => new Promise<ToolCallResult>(() => undefined),
    };
    const slowEvents: AgentExecutionEvent[] = [];
    const slowExecution = slowRuntime.start(
      unverifiedInput(slowSnapshot), event => slowEvents.push(event));
    await vi.waitFor(() =>
      expect(slowEvents.some(event => event.type === 'tool-call')).toBe(true));
    slowExecution.cancel();
    const cancelled = await slowExecution.result;
    expect(cancelled.messages.flatMap(message => message.blocks)).toContainEqual(
      expect.objectContaining({ name: 'local.slow', execution: 'unknown' }),
    );
  });

  it('P1-1：用户拒绝强制核实的调用不解除未核实状态', async () => {
    const { snapshot, invocations } = createToolSnapshot([toolNamed('local.query')]);
    const model = new ScriptedModel();
    model.scripts = [toolResponse('local.query'), textResponse('已跳过')];
    const events: AgentExecutionEvent[] = [];
    const runtime = createRuntime(model);

    const input = unverifiedInput(snapshot);
    const execution = runtime.start(input, event => events.push(event));
    await vi.waitFor(() =>
      expect(events.some(event => event.type === 'interrupt')).toBe(true));
    execution.respond({ interruptId: 'interrupt-1', value: false });
    const result = await execution.result;

    expect(invocations).toHaveLength(0);
    // 拒绝只是“这次不执行”，上一轮未知结果仍未被核实。
    expect(result.messages.flatMap(message => message.blocks)).toContainEqual(
      expect.objectContaining({ name: 'local.query', execution: 'not-executed' }),
    );
    // 下一轮使用上一轮完整消息，换新 callId 后仍然必须确认。
    model.scripts.push(toolResponse('local.query').map(event =>
      event.type === 'block-start' && event.block.type === 'tool-call'
        ? { ...event, block: { ...event.block, callId: 'call-next' } } : event));
    const nextEvents: AgentExecutionEvent[] = [];
    const next = runtime.start({
      ...input,
      conversation: { messages: [...input.conversation.messages, ...result.messages],
        modelContext: result.modelContext },
    }, event => nextEvents.push(event));
    await vi.waitFor(() => expect(nextEvents.some(event => event.type === 'interrupt')).toBe(true));
    next.cancel();
    await next.result;
    expect(JSON.stringify(result.messages)).toContain('未执行');
  });

  it('VA-03：下一轮输入携带终态记录后与历史 Tool Call 严格配对', async () => {
    const { snapshot } = createToolSnapshot([toolNamed('local.restart', true)]);
    const model = new ScriptedModel();
    model.scripts = [
      toolResponse('local.restart'),
      textResponse('收到，继续'),
    ];
    const events: AgentExecutionEvent[] = [];
    const runtime = createRuntime(model);

    const first = runtime.start(baseInput(snapshot), event => events.push(event));
    await vi.waitFor(() =>
      expect(events.some(event => event.type === 'interrupt')).toBe(true));
    first.cancel();
    await first.result;

    // 以取消后的稳定历史作为下一轮输入：出站消息不允许未配对 Tool Call
    const cancelledMessages = (await first.result).messages;
    const secondInput: AgentRunInput = {
      conversation: {
        messages: [
          ...baseInput(snapshot).conversation.messages,
          ...cancelledMessages,
        ],
        modelContext: (await first.result).modelContext,
      },
      toolSnapshot: snapshot,
      conversationId: 'conversation-1',
      traceId: 'trace-2',
    };
    const second = runtime.start(secondInput, () => undefined);
    const secondResult = await second.result;

    const request = model.calls[1]?.request;
    const callIds = request?.messages.flatMap(message =>
      message.blocks.filter(block => block.type === 'tool-call')
        .map(block => block.callId));
    const resultIds = request?.messages.flatMap(message =>
      message.blocks.filter(block => block.type === 'tool-result')
        .map(block => block.callId));
    expect(callIds).toEqual(['call-1']);
    expect(resultIds).toEqual(['call-1']);
    expect(secondResult.outcome).toEqual({ type: 'completed', stopReason: 'end-turn' });
  });
  it('取消的最终消息监听器抛错时 result 明确拒绝，重入取消不重复通知', async () => {
    const model = new ScriptedModel();
    model.scripts = [toolResponse('local.query')];
    const { snapshot, invocations } = createToolSnapshot([toolNamed('local.query')]);
    const runtime = createRuntime(model);
    let execution: AgentExecution;
    let notifications = 0;
    execution = runtime.start(baseInput(snapshot), event => {
      if (event.type !== 'messages') return;
      notifications += 1;
      execution.cancel();
      if (event.messages.some(message => message.role === 'tool')) {
        throw new Error('终态订阅者异常');
      }
    });
    await expect(execution.result).rejects.toThrow('终态订阅者异常');
    expect(notifications).toBe(2);
    expect(invocations).toHaveLength(0);
  });

  it('未知调用已移出压缩后的模型输入时，仍从完整历史恢复核实约束', async () => {
    const model = new ScriptedModel();
    model.scripts = [toolResponse('local.query')];
    const { snapshot, invocations } = createToolSnapshot([toolNamed('local.query')]);
    const manager = new DefaultContextManager({
      configuration: async () => testContextManager().getConfiguration(TEST_TARGET),
      compact: async () => { throw new Error('本测试不应再次压缩'); },
    });
    await manager.loadConfiguration(TEST_TARGET);
    const input = unverifiedInput(snapshot);
    const restored: AgentRunInput = {
      ...input,
      conversation: {
        ...input.conversation,
        modelContext: {
          checkpoint: { id: 'checkpoint', summary: '旧对话摘要', trigger: 'manual',
            compactedAt: '2026-09-06T00:00:00Z', tokensBefore: 500, estimatedTokensAfter: 100,
            compactionCount: 1 },
          firstRetainedMessageId: 'user-1', modelState: null,
          usage: { totalTokens: 100, source: 'estimated', measuredThroughMessageId: 'user-1',
            toolDefinitionTokens: 0 },
        },
      },
    };
    const runtime = new DefaultAgentRuntime(model, { limits: testLimits(), contextManager: manager });
    const events: AgentExecutionEvent[] = [];
    const execution = runtime.start(restored, event => events.push(event));
    await vi.waitFor(() => expect(events.some(event => event.type === 'interrupt')).toBe(true));
    expect(model.calls[0].request.messages.map(message => message.id)).not.toContain('old-result');
    expect(events).toContainEqual(expect.objectContaining({
      type: 'interrupt', interrupt: expect.objectContaining({ reason: 'unverified-previous-invocation' }),
    }));
    expect(invocations).toHaveLength(0);
    execution.cancel();
    await execution.result;
  });

});
