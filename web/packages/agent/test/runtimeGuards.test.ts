/**
 * Browser Agent Runtime 生产级执行守卫契约测试。
 *
 * <p>本文件只验证跨模型调用才能判断的不变量：整批 Tool 预检、Execution 资源预算、
 * 单一终态、非合作异步任务隔离和 Tool 错误分类。单次响应事件顺序与 stopReason
 * 决策表由 modelMessageAssembler.test.ts 覆盖，避免两套测试重复表达同一规则。
 */
import { describe, expect, it, vi } from 'vitest';
import type {
  Model,
  ModelCallContext,
  ModelRequest,
  ModelStopReason,
  ModelStreamEvent,
} from '../src/clients/modelClient';
import type {
  AgentExecution,
  AgentExecutionEvent,
  AgentRunInput,
} from '../src/engine';
import type { AgentHookFailure, AgentLifecycleEvent } from '../src/extensions';
import {
  DEFAULT_AGENT_EXECUTION_LIMITS,
  DefaultAgentRuntime,
} from '../src/runtime';
import type { AgentExecutionLimits } from '../src/runtime';
import type { ToolRegistrySnapshot } from '../src/toolRegistry';
import type {
  AgentMessage,
  ToolCallResult,
  ToolDefinition,
} from '../src/types';
import {
  TEST_MODEL_USAGE,
  testContextManager,
  testModelContext,
} from './testContext';

/** 测试默认预算足以运行普通脚本，单项边界由用例覆盖。 */
const TEST_LIMITS: AgentExecutionLimits = Object.freeze({
  maxModelCalls: 4,
  maxToolCalls: 8,
  maxDurationMs: 10_000,
  maxModelOutputCharacters: 10_000,
  maxToolResultCharacters: 10_000,
});

/** 可控 Promise 的公开门闩。 */
interface Deferred<T> {
  /** 被测异步端口持有的 Promise。 */
  readonly promise: Promise<T>;
  /** 测试在终态之后注入迟到成功。 */
  readonly resolve: (value: T) => void;
  /** 测试在终态之后注入迟到失败。 */
  readonly reject: (cause: unknown) => void;
}

/** 创建不会自行完成的 Promise 门闩。 */
function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  let reject!: (cause: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

/** 记录一次模型调用的稳定请求和取消信号。 */
interface RecordedModelCall {
  /** Runtime 交给 Model 的厂商中立请求。 */
  readonly request: ModelRequest;
  /** 整轮 Execution 共用的协作取消信号。 */
  readonly signal: AbortSignal;
}

/** 按调用顺序返回结构化事件脚本的 Model。 */
class QueueModel implements Model {
  /** 尚未消费的模型响应脚本。 */
  readonly scripts: ModelStreamEvent[][];
  /** 已发生的模型调用。 */
  readonly calls: RecordedModelCall[] = [];

  /** 固定脚本快照，防止测试调用方稍后改写数组。 */
  constructor(scripts: readonly (readonly ModelStreamEvent[])[]) {
    this.scripts = scripts.map(script => [...script]);
  }

  /** 返回下一份脚本；脚本耗尽表示测试装配错误。 */
  stream(
    request: ModelRequest,
    _context: ModelCallContext,
    signal: AbortSignal,
  ): AsyncIterable<ModelStreamEvent> {
    this.calls.push({ request, signal });
    const script = this.scripts.shift();
    if (script == null) {
      throw new Error('测试模型脚本已经耗尽');
    }
    return {
      /** 每次调用创建独立异步迭代器。 */
      async *[Symbol.asyncIterator](): AsyncIterator<ModelStreamEvent> {
        for (const event of script) {
          yield event;
        }
      },
    };
  }
}

/** 永不完成 next/return 的不合作 Model，用于验证逻辑终态门。 */
class HangingModel implements Model {
  /** 首个 next 的迟到结果由测试控制。 */
  readonly nextResult = deferred<IteratorResult<ModelStreamEvent>>();
  /** Runtime 传入的取消信号。 */
  signal: AbortSignal | null = null;
  /** 真实 Model 端口调用次数。 */
  calls = 0;
  /** Runtime 是否尝试清理上游迭代器。 */
  returnCalls = 0;

  /** 返回忽略 AbortSignal 且 return 也永不完成的迭代器。 */
  stream(
    _request: ModelRequest,
    _context: ModelCallContext,
    signal: AbortSignal,
  ): AsyncIterable<ModelStreamEvent> {
    this.calls += 1;
    this.signal = signal;
    const owner = this;
    return {
      /** 同一迭代器只会挂起在首个 next。 */
      [Symbol.asyncIterator](): AsyncIterator<ModelStreamEvent> {
        return {
          /** 模拟永不响应取消的网络读取。 */
          next: () => owner.nextResult.promise,
          /** 记录清理请求，但刻意不合作。 */
          return: () => {
            owner.returnCalls += 1;
            return new Promise<IteratorResult<ModelStreamEvent>>(() => undefined);
          },
        };
      },
    };
  }
}

/** 立即产生事件的 Model，用于证明单调时钟能阻止微任务饥饿越界。 */
class ImmediateModel implements Model {
  /** 已调用 next 的次数。 */
  nextCalls = 0;
  /** Runtime 是否发起迭代器清理。 */
  returnCalls = 0;

  /** 返回始终就绪的迭代器，不给原生 Deadline 定时器主动让出宏任务。 */
  stream(): AsyncIterable<ModelStreamEvent> {
    const owner = this;
    return {
      /** 创建单个立即就绪迭代器。 */
      [Symbol.asyncIterator](): AsyncIterator<ModelStreamEvent> {
        return {
          /** 首条事件合法；后续空增量只用于持续占用微任务队列。 */
          next: async () => {
            const index = owner.nextCalls++;
            return index === 0
              ? {
                  done: false,
                  value: { type: 'block-start', index: 0, block: { type: 'text' } },
                }
              : {
                  done: false,
                  value: {
                    type: 'block-delta',
                    index: 0,
                    delta: { type: 'text', text: '' },
                  },
                };
          },
          /** 清理立即完成，便于断言边界检查确实关闭了迭代器。 */
          return: async () => {
            owner.returnCalls += 1;
            return { done: true, value: undefined };
          },
        };
      },
    };
  }
}

/** 在正文 delta 之后继续计数读取，用于验证 listener 重入取消不会多读一次上游。 */
class ReentrantCancellationModel implements Model {
  /** 迭代器 next 的总调用次数。 */
  nextCalls = 0;
  /** Runtime 请求清理迭代器的次数。 */
  returnCalls = 0;

  /** 返回 block-start 与首个正文 delta，之后的任何 next 都属于取消后的越界读取。 */
  stream(): AsyncIterable<ModelStreamEvent> {
    const owner = this;
    const events: ModelStreamEvent[] = [
      { type: 'block-start', index: 0, block: { type: 'text' } },
      { type: 'block-delta', index: 0, delta: { type: 'text', text: '触发取消' } },
    ];
    return {
      /** 创建可记录每次读取的单一迭代器。 */
      [Symbol.asyncIterator](): AsyncIterator<ModelStreamEvent> {
        return {
          /** 返回下一条脚本事件；脚本耗尽后保持挂起以暴露多余读取。 */
          next: () => {
            const index = owner.nextCalls++;
            const event = events[index];
            return event == null
              ? new Promise<IteratorResult<ModelStreamEvent>>(() => undefined)
              : Promise.resolve({ done: false, value: event });
          },
          /** 记录 Runtime 在逻辑终态后的非阻塞清理。 */
          return: async () => {
            owner.returnCalls += 1;
            return { done: true, value: undefined };
          },
        };
      },
    };
  }
}

/** 产生大量空增量并在每次新读取前检查上一活动 waiter 已注销。 */
class EmptyDeltaBurstModel implements Model {
  /** 读取前执行的白盒断言；首个同步 next 发生在 Execution 赋值前。 */
  inspectBeforeNext: (() => void) | null = null;
  /** 实际 next 调用次数。 */
  nextCalls = 0;

  /** 创建有限空增量脚本；空增量不会消耗模型字符预算。 */
  stream(): AsyncIterable<ModelStreamEvent> {
    const events: ModelStreamEvent[] = [
      { type: 'block-start', index: 0, block: { type: 'text' } },
      ...Array.from({ length: 128 }, (): ModelStreamEvent => ({
        type: 'block-delta',
        index: 0,
        delta: { type: 'text', text: '' },
      })),
      { type: 'block-delta', index: 0, delta: { type: 'text', text: '完成' } },
      { type: 'block-stop', index: 0 },
      { type: 'message-stop', stopReason: 'end-turn', usage: TEST_MODEL_USAGE, modelState: null },
    ];
    const owner = this;
    return {
      /** 创建按脚本顺序完成的迭代器。 */
      [Symbol.asyncIterator](): AsyncIterator<ModelStreamEvent> {
        return {
          /** 每次新读取前验证上一 wait 已从 Execution 注销。 */
          next: async () => {
            owner.inspectBeforeNext?.();
            const event = events[owner.nextCalls++];
            return event == null
              ? { done: true, value: undefined }
              : { done: false, value: event };
          },
        };
      },
    };
  }
}

/** 合并单项测试预算，同时保持完整 limits 契约。 */
function limits(overrides: Partial<AgentExecutionLimits> = {}): AgentExecutionLimits {
  return { ...TEST_LIMITS, ...overrides };
}

/** 创建具有可预测 ID 和可选 Hook 的 Runtime。 */
function runtime(
  model: Model,
  options: {
    /** 单项预算覆盖。 */
    readonly limits?: Partial<AgentExecutionLimits>;
    /** 生命周期观察者。 */
    readonly hooks?: readonly { onEvent(event: AgentLifecycleEvent): void }[];
    /** 单调时钟；Deadline 边界测试显式注入。 */
    readonly now?: () => number;
    /** 终态 Hook 失败的独立诊断收集器。 */
    readonly onHookError?: (failure: AgentHookFailure) => void;
  } = {},
): DefaultAgentRuntime {
  let id = 0;
  return new DefaultAgentRuntime(model, {
    limits: limits(options.limits),
    contextManager: testContextManager(),
    hooks: options.hooks,
    now: options.now,
    onHookError: options.onHookError,
    createId: kind => `${kind}-${++id}`,
  });
}

/** 创建可执行 Tool 定义；确认开关只影响 HITL 用例。 */
function tool(name: string, requireConfirmation = false): ToolDefinition {
  return Object.freeze({
    name,
    title: name,
    description: 'Runtime 守卫测试 Tool',
    inputSchema: Object.freeze({ type: 'object' }),
    annotations: Object.freeze({
      readOnlyHint: !requireConfirmation,
      destructiveHint: requireConfirmation,
      idempotentHint: false,
      requireConfirmation,
    }),
    source: 'LOCAL',
    permissions: Object.freeze([]),
  });
}

/** 创建定义与执行端口属于同一 revision 的 Tool 快照。 */
function toolSnapshot(
  names: readonly string[],
  invoke: ToolRegistrySnapshot['invoke'] = async (
    _name,
    _arguments,
    context,
  ) => ({
    toolCallId: context.toolCallId,
    content: 'ok',
    isError: false,
  }),
  confirmationNames: readonly string[] = [],
): ToolRegistrySnapshot {
  const confirmation = new Set(confirmationNames);
  return Object.freeze({
    revision: 1,
    tools: Object.freeze(names.map(name => tool(name, confirmation.has(name)))),
    invoke,
  });
}

/** 创建最小 Execution 输入，可显式注入带 Tool ID 的历史上下文。 */
function input(
  snapshot: ToolRegistrySnapshot,
  messages: readonly AgentMessage[] = [userMessage()],
): AgentRunInput {
  return {
    conversation: { messages, modelContext: testModelContext() },
    toolSnapshot: snapshot,
    conversationId: 'conversation-guards',
    traceId: 'trace-guards',
  };
}

/** 创建稳定用户消息。 */
function userMessage(): AgentMessage {
  return {
    id: 'user-1',
    role: 'user',
    blocks: [{ type: 'text', text: '执行测试' }],
  };
}

/** 创建一条没有 Tool 的完整文本响应。 */
function textResponse(
  text: string,
  stopReason: ModelStopReason = 'end-turn',
): ModelStreamEvent[] {
  return [
    { type: 'block-start', index: 0, block: { type: 'text' } },
    { type: 'block-delta', index: 0, delta: { type: 'text', text } },
    { type: 'block-stop', index: 0 },
    { type: 'message-stop', stopReason, usage: TEST_MODEL_USAGE, modelState: null },
  ];
}

/** 创建包含指定 Tool Call 批次的合法 tool-use 响应。 */
function toolResponse(
  calls: readonly { readonly callId: string; readonly name: string }[],
): ModelStreamEvent[] {
  const events: ModelStreamEvent[] = [];
  calls.forEach((call, index) => {
    events.push({
      type: 'block-start',
      index,
      block: { type: 'tool-call', callId: call.callId, name: call.name },
    });
    events.push({
      type: 'block-delta',
      index,
      delta: { type: 'tool-call', argumentsDelta: '{}' },
    });
    events.push({ type: 'block-stop', index });
  });
  events.push({
    type: 'message-stop',
    stopReason: 'tool-use',
    usage: TEST_MODEL_USAGE,
    modelState: null,
  });
  return events;
}

/** 等待一个宏任务，让迟到 Promise 有机会尝试发布事件。 */
async function nextTask(): Promise<void> {
  await new Promise<void>(resolve => setTimeout(resolve, 0));
}

describe('DefaultAgentRuntime 生产级执行守卫', () => {
  it('便捷工厂默认预算是公开、冻结且完整的安全边界', () => {
    expect(DEFAULT_AGENT_EXECUTION_LIMITS).toEqual({
      maxModelCalls: 16,
      maxToolCalls: 32,
      maxDurationMs: 300_000,
      maxModelOutputCharacters: 100_000,
      maxToolResultCharacters: 100_000,
    });
    expect(Object.isFrozen(DEFAULT_AGENT_EXECUTION_LIMITS)).toBe(true);
  });

  it.each([
    ['maxModelCalls', 0],
    ['maxToolCalls', -1],
    ['maxDurationMs', 1.5],
    ['maxModelOutputCharacters', Number.NaN],
    ['maxToolResultCharacters', Number.POSITIVE_INFINITY],
  ] as const)('直接构造 Runtime 时拒绝非法完整预算 %s=%s', (name, value) => {
    expect(() => new DefaultAgentRuntime(new QueueModel([]), {
      limits: { ...TEST_LIMITS, [name]: value },
      contextManager: testContextManager(),
    })).toThrow(`${name} 必须是安全正整数`);
  });

  it('拒绝浏览器 setTimeout 无法准确表达的 Execution 时长', () => {
    expect(() => new DefaultAgentRuntime(new QueueModel([]), {
      limits: { ...TEST_LIMITS, maxDurationMs: 2_147_483_648 },
      contextManager: testContextManager(),
    })).toThrow('maxDurationMs 超出浏览器定时器支持范围');
  });

  it('max-tokens 保留稳定消息，并向结果与 Hook 发布同一 Outcome', async () => {
    const model = new QueueModel([textResponse('回答可能未完成', 'max-tokens')]);
    const hookEvents: AgentLifecycleEvent[] = [];
    const execution = runtime(model, {
      hooks: [{ onEvent: event => hookEvents.push(event) }],
    }).start(input(toolSnapshot([])), () => undefined);

    await expect(execution.result).resolves.toMatchObject({
      messages: [{ blocks: [{ type: 'text', text: '回答可能未完成' }] }],
      outcome: { type: 'max-tokens' },
    });
    expect(hookEvents.at(-1)).toEqual({
      type: 'execution-completed',
      outcome: { type: 'max-tokens' },
      addedMessageCount: 1,
    });
  });

  it('整批存在未知 Tool 时不发布稳定消息且整批零副作用', async () => {
    const model = new QueueModel([toolResponse([
      { callId: 'call-known', name: 'local.known' },
      { callId: 'call-unknown', name: 'local.unknown' },
    ])]);
    const invoke = vi.fn<ToolRegistrySnapshot['invoke']>();
    const events: AgentExecutionEvent[] = [];
    const hooks: AgentLifecycleEvent[] = [];
    const execution = runtime(model, {
      hooks: [{ onEvent: event => hooks.push(event) }],
    }).start(
      input(toolSnapshot(['local.known'], invoke)),
      event => events.push(event),
    );

    await expect(execution.result).rejects.toMatchObject({
      code: 'MODEL_PROTOCOL_ERROR',
      retryable: false,
    });
    expect(invoke).not.toHaveBeenCalled();
    expect(events.some(event => event.type === 'tool-call')).toBe(false);
    expect(events.some(event => event.type === 'messages')).toBe(false);
    expect(hooks.some(event => event.type === 'model-call-completed')).toBe(false);
  });

  it('模型复用历史 Tool Call ID 时当前批次零副作用', async () => {
    const history: AgentMessage[] = [
      userMessage(),
      {
        id: 'assistant-history',
        role: 'assistant',
        blocks: [{
          type: 'tool-call',
          callId: 'call-history',
          name: 'local.known',
          input: {},
        }],
      },
      {
        id: 'tool-history',
        role: 'tool',
        blocks: [{
          type: 'tool-result',
          callId: 'call-history',
          name: 'local.known',
          status: 'success',
          content: [{ type: 'text', text: '历史结果' }],
        }],
      },
    ];
    const model = new QueueModel([toolResponse([
      { callId: 'call-history', name: 'local.known' },
    ])]);
    const invoke = vi.fn<ToolRegistrySnapshot['invoke']>();

    await expect(runtime(model).start(
      input(toolSnapshot(['local.known'], invoke), history),
      () => undefined,
    ).result).rejects.toMatchObject({ code: 'MODEL_PROTOCOL_ERROR' });
    expect(invoke).not.toHaveBeenCalled();
  });

  it('后续响应复用本 Execution 的 Tool Call ID 时不重复执行副作用', async () => {
    const repeated = { callId: 'call-1', name: 'local.known' } as const;
    const model = new QueueModel([
      toolResponse([repeated]),
      toolResponse([repeated]),
    ]);
    const invoke = vi.fn<ToolRegistrySnapshot['invoke']>(async (
      _name,
      _arguments,
      context,
    ) => ({
      toolCallId: context.toolCallId,
      content: '第一次执行完成',
      isError: false,
    }));

    await expect(runtime(model).start(
      input(toolSnapshot(['local.known'], invoke)),
      () => undefined,
    ).result).rejects.toMatchObject({ code: 'MODEL_PROTOCOL_ERROR' });
    expect(invoke).toHaveBeenCalledTimes(1);
    expect(model.calls).toHaveLength(2);
  });

  it('Tool 批次将超过 maxToolCalls 时整批零副作用', async () => {
    const model = new QueueModel([toolResponse([
      { callId: 'call-1', name: 'local.one' },
      { callId: 'call-2', name: 'local.two' },
    ])]);
    const invoke = vi.fn<ToolRegistrySnapshot['invoke']>();

    await expect(runtime(model, {
      limits: { maxToolCalls: 1 },
    }).start(
      input(toolSnapshot(['local.one', 'local.two'], invoke)),
      () => undefined,
    ).result).rejects.toMatchObject({
      code: 'AGENT_MAX_TOOL_CALLS',
      retryable: false,
    });
    expect(invoke).not.toHaveBeenCalled();
  });

  it('模型输出超限时不形成稳定消息且不重试', async () => {
    const model = new QueueModel([textResponse('1234')]);
    const events: AgentExecutionEvent[] = [];

    await expect(runtime(model, {
      limits: { maxModelOutputCharacters: 3 },
    }).start(input(toolSnapshot([])), event => events.push(event)).result)
      .rejects.toMatchObject({
        code: 'MODEL_OUTPUT_LIMIT_EXCEEDED',
        retryable: false,
      });
    expect(model.calls).toHaveLength(1);
    expect(events.some(event => event.type === 'messages')).toBe(false);
  });

  it('Tool 结果超限后不发布结果、不形成 Tool 消息也不调用下一次模型', async () => {
    const model = new QueueModel([
      toolResponse([{ callId: 'call-1', name: 'local.large' }]),
      textResponse('不应调用'),
    ]);
    const invoke = vi.fn<ToolRegistrySnapshot['invoke']>(async (
      _name,
      _arguments,
      context,
    ) => ({
      toolCallId: context.toolCallId,
      content: '1234',
      isError: false,
    }));
    const events: AgentExecutionEvent[] = [];

    await expect(runtime(model, {
      limits: { maxToolResultCharacters: 3 },
    }).start(
      input(toolSnapshot(['local.large'], invoke)),
      event => events.push(event),
    ).result).rejects.toMatchObject({
      code: 'TOOL_RESULT_LIMIT_EXCEEDED',
      retryable: false,
    });
    expect(invoke).toHaveBeenCalledTimes(1);
    expect(model.calls).toHaveLength(1);
    expect(events.some(event => event.type === 'tool-result')).toBe(false);
    // 超限内容绝不进入对话；但历史不能留下未配对 Tool Call，
    // 终态记录如实说明“已执行、结果超限未回填”。
    const committed = events.filter(event => event.type === 'messages');
    expect(committed).toHaveLength(2);
    expect(committed[0]).toMatchObject({
      messages: [{ role: 'assistant', blocks: [{ type: 'tool-call' }] }],
    });
    expect(committed[1]?.messages).toHaveLength(2);
    expect(JSON.stringify(committed[1]?.messages)).not.toContain('1234');
    expect(committed[1]?.messages[1]).toMatchObject({
      role: 'tool',
      blocks: [{
        type: 'tool-result',
        callId: 'call-1',
        status: 'error',
        content: [{ text: expect.stringContaining('结果超过') }],
      }],
    });
  });

  it('Tool isError 作为模型可见业务失败继续循环', async () => {
    const model = new QueueModel([
      toolResponse([{ callId: 'call-1', name: 'local.business' }]),
      textResponse('已解释业务失败'),
    ]);
    const invoke = vi.fn<ToolRegistrySnapshot['invoke']>(async (
      _name,
      _arguments,
      context,
    ) => ({
      toolCallId: context.toolCallId,
      content: '设备当前不可用',
      isError: true,
    }));

    const result = await runtime(model).start(
      input(toolSnapshot(['local.business'], invoke)),
      () => undefined,
    ).result;

    expect(result.outcome).toEqual({ type: 'completed', stopReason: 'end-turn' });
    expect(result.messages[1]).toMatchObject({
      role: 'tool',
      blocks: [{ status: 'error', content: [{ text: '设备当前不可用' }] }],
    });
    expect(model.calls).toHaveLength(2);
    expect(model.calls[1]?.request.messages.at(-1)).toEqual(result.messages[1]);
  });

  it('Tool Promise rejection 保留原异常并终止 Execution', async () => {
    const model = new QueueModel([
      toolResponse([{ callId: 'call-1', name: 'local.failure' }]),
      textResponse('不应调用'),
    ]);
    const adapterFailure = new Error('Tool Adapter 连接失败');
    const invoke = vi.fn<ToolRegistrySnapshot['invoke']>(async () => {
      throw adapterFailure;
    });
    const events: AgentExecutionEvent[] = [];

    await expect(runtime(model).start(
      input(toolSnapshot(['local.failure'], invoke)),
      event => events.push(event),
    ).result).rejects.toBe(adapterFailure);
    expect(model.calls).toHaveLength(1);
    expect(events.some(event => event.type === 'tool-result')).toBe(false);
  });

  it('取消不合作 Model 时立即收敛，迟到 next 与 return 不再发布', async () => {
    const model = new HangingModel();
    const events: AgentExecutionEvent[] = [];
    const hooks: AgentLifecycleEvent[] = [];
    const execution = runtime(model, {
      hooks: [{ onEvent: event => hooks.push(event) }],
    }).start(input(toolSnapshot([])), event => events.push(event));

    // 每次模型调用前都会经过统一的输入准备边界（异步），先等待其进入模型流。
    await nextTask();
    expect(model.calls).toBe(1);
    execution.cancel();
    await expect(execution.result).resolves.toMatchObject({
      outcome: { type: 'cancelled' },
    });
    expect(model.signal?.aborted).toBe(true);
    const eventCountAtTerminal = events.length;
    model.nextResult.resolve({
      done: false,
      value: { type: 'block-start', index: 0, block: { type: 'text' } },
    });
    await nextTask();

    expect(model.returnCalls).toBe(1);
    expect(events).toHaveLength(eventCountAtTerminal);
    expect(hooks.filter(event =>
      event.type === 'execution-completed' || event.type === 'execution-failed'))
      .toEqual([expect.objectContaining({
        type: 'execution-completed',
        outcome: { type: 'cancelled' },
      })]);
  });

  it('Deadline 能关闭不合作 Tool，并隔离迟到结果', async () => {
    const model = new QueueModel([
      toolResponse([{ callId: 'call-1', name: 'local.hanging' }]),
      textResponse('不应调用'),
    ]);
    const lateResult = deferred<ToolCallResult>();
    let toolSignal: AbortSignal | undefined;
    const invoke = vi.fn<ToolRegistrySnapshot['invoke']>((
      _name,
      _arguments,
      _context,
      signal,
    ) => {
      toolSignal = signal;
      return lateResult.promise;
    });
    const events: AgentExecutionEvent[] = [];
    const hooks: AgentLifecycleEvent[] = [];
    const execution = runtime(model, {
      limits: { maxDurationMs: 40 },
      hooks: [{ onEvent: event => hooks.push(event) }],
    }).start(
      input(toolSnapshot(['local.hanging'], invoke)),
      event => events.push(event),
    );

    await expect(execution.result).rejects.toMatchObject({
      code: 'AGENT_EXECUTION_TIMEOUT',
      retryable: false,
    });
    expect(toolSignal?.aborted).toBe(true);
    const eventCountAtTerminal = events.length;
    lateResult.resolve({ toolCallId: 'call-1', content: '迟到结果', isError: false });
    await nextTask();

    expect(events).toHaveLength(eventCountAtTerminal);
    expect(events.some(event => event.type === 'tool-result')).toBe(false);
    expect(model.calls).toHaveLength(1);
    expect(hooks.filter(event =>
      event.type === 'execution-completed' || event.type === 'execution-failed'))
      .toEqual([expect.objectContaining({
        type: 'execution-failed',
        errorCode: 'AGENT_EXECUTION_TIMEOUT',
      })]);
  });

  it('Deadline 能关闭不合作的人工确认等待且绝不调用 Tool', async () => {
    const model = new QueueModel([
      toolResponse([{ callId: 'call-1', name: 'local.confirm' }]),
    ]);
    const invoke = vi.fn<ToolRegistrySnapshot['invoke']>();
    const events: AgentExecutionEvent[] = [];
    const execution = runtime(model, {
      limits: { maxDurationMs: 40 },
    }).start(
      input(toolSnapshot(['local.confirm'], invoke, ['local.confirm'])),
      event => events.push(event),
    );
    // 先安装 rejection 处理器，再等待中断事件，避免短 Deadline 先于测试断言触发。
    const terminal = execution.result.then(
      () => null,
      cause => cause,
    );

    await vi.waitFor(() => {
      expect(events.some(event => event.type === 'interrupt')).toBe(true);
    });
    await expect(terminal).resolves.toMatchObject({
      code: 'AGENT_EXECUTION_TIMEOUT',
    });
    expect(invoke).not.toHaveBeenCalled();
    expect(() => execution.respond({ interruptId: 'interrupt-1', value: true }))
      .toThrow('已进入终态');
  });

  it('单调时钟边界在原生定时器执行前拒绝持续就绪的微任务流', async () => {
    const model = new ImmediateModel();
    const samples = [0, 0, 0, 0, 0, 0, 0, 1_000];
    let sampleIndex = 0;
    const execution = runtime(model, {
      limits: { maxDurationMs: 1_000 },
      now: () => samples[sampleIndex++] ?? 1_000,
    }).start(input(toolSnapshot([])), () => undefined);

    await expect(execution.result).rejects.toMatchObject({
      code: 'AGENT_EXECUTION_TIMEOUT',
    });
    expect(model.nextCalls).toBe(1);
    expect(model.returnCalls).toBe(1);
  });

  it('完成终态 Hook 失败只进入 diagnostics，剩余 Hook 仍按顺序观察同一终态', async () => {
    const hookFailure = new Error('完成终态 Hook 失败');
    const observed: string[] = [];
    const diagnostics: AgentHookFailure[] = [];
    const execution = runtime(new QueueModel([textResponse('正常完成')]), {
      hooks: [
        { onEvent: event => {
          if (event.type === 'execution-completed' || event.type === 'execution-failed') {
            observed.push(`first:${event.type}`);
          }
        } },
        { onEvent: event => {
          if (event.type === 'execution-completed') {
            observed.push('throwing:execution-completed');
            throw hookFailure;
          }
        } },
        { onEvent: event => {
          if (event.type === 'execution-completed' || event.type === 'execution-failed') {
            observed.push(`last:${event.type}`);
          }
        } },
      ],
      onHookError: failure => {
        observed.push('diagnostics');
        diagnostics.push(failure);
      },
    }).start(input(toolSnapshot([])), () => undefined);

    await expect(execution.result).resolves.toMatchObject({
      outcome: { type: 'completed', stopReason: 'end-turn' },
    });
    expect(observed).toEqual([
      'first:execution-completed',
      'throwing:execution-completed',
      'diagnostics',
      'last:execution-completed',
    ]);
    expect(diagnostics).toHaveLength(1);
    expect(diagnostics[0]).toMatchObject({
      hookIndex: 1,
      event: { type: 'execution-completed' },
      context: { traceId: 'trace-guards', conversationId: 'conversation-guards' },
      cause: hookFailure,
    });
    expect(Object.isFrozen(diagnostics[0])).toBe(true);
    expect(Object.isFrozen(diagnostics[0]?.event)).toBe(true);
    expect(Object.isFrozen(diagnostics[0]?.context)).toBe(true);
  });

  it('取消终态 Hook 失败不改判 cancelled，且所有 Hook 只收到一个终态', async () => {
    const model = new HangingModel();
    const terminalEvents: string[] = [];
    const diagnostics: AgentHookFailure[] = [];
    const execution = runtime(model, {
      hooks: [
        { onEvent: event => {
          if (event.type === 'execution-completed') {
            terminalEvents.push(`first:${event.outcome.type}`);
          }
        } },
        { onEvent: event => {
          if (event.type === 'execution-completed' && event.outcome.type === 'cancelled') {
            terminalEvents.push('throwing:cancelled');
            throw new Error('取消观察失败');
          }
        } },
        { onEvent: event => {
          if (event.type === 'execution-completed') {
            terminalEvents.push(`last:${event.outcome.type}`);
          }
        } },
      ],
      onHookError: failure => diagnostics.push(failure),
    }).start(input(toolSnapshot([])), () => undefined);

    execution.cancel();

    await expect(execution.result).resolves.toMatchObject({ outcome: { type: 'cancelled' } });
    expect(terminalEvents).toEqual(['first:cancelled', 'throwing:cancelled', 'last:cancelled']);
    expect(diagnostics).toHaveLength(1);
    expect(diagnostics[0]?.event).toMatchObject({
      type: 'execution-completed',
      outcome: { type: 'cancelled' },
    });
  });

  it('超时终态 Hook 失败不覆盖 AGENT_EXECUTION_TIMEOUT，后续 Hook 仍收到原失败', async () => {
    const model = new HangingModel();
    const terminalCodes: string[] = [];
    const diagnostics: AgentHookFailure[] = [];
    const execution = runtime(model, {
      limits: { maxDurationMs: 30 },
      hooks: [
        { onEvent: event => {
          if (event.type === 'execution-failed') {
            terminalCodes.push(`first:${event.errorCode}`);
          }
        } },
        { onEvent: event => {
          if (event.type === 'execution-failed') {
            terminalCodes.push(`throwing:${event.errorCode}`);
            throw new Error('超时观察失败');
          }
        } },
        { onEvent: event => {
          if (event.type === 'execution-failed') {
            terminalCodes.push(`last:${event.errorCode}`);
          }
        } },
      ],
      onHookError: failure => diagnostics.push(failure),
    }).start(input(toolSnapshot([])), () => undefined);

    await expect(execution.result).rejects.toMatchObject({
      code: 'AGENT_EXECUTION_TIMEOUT',
      retryable: false,
    });
    expect(terminalCodes).toEqual([
      'first:AGENT_EXECUTION_TIMEOUT',
      'throwing:AGENT_EXECUTION_TIMEOUT',
      'last:AGENT_EXECUTION_TIMEOUT',
    ]);
    expect(diagnostics).toHaveLength(1);
    expect(diagnostics[0]?.event).toMatchObject({
      type: 'execution-failed',
      errorCode: 'AGENT_EXECUTION_TIMEOUT',
    });
  });

  it('自定义 Hook diagnostics 自身抛错时显式报告且不阻断原终态', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const diagnosticsFailure = new Error('diagnostics 失败');
    try {
      const execution = runtime(new QueueModel([textResponse('仍应完成')]), {
        hooks: [{
          onEvent: event => {
            if (event.type === 'execution-completed') {
              throw new Error('终态 Hook 失败');
            }
          },
        }],
        onHookError: () => {
          throw diagnosticsFailure;
        },
      }).start(input(toolSnapshot([])), () => undefined);

      await expect(execution.result).resolves.toMatchObject({
        outcome: { type: 'completed' },
      });
      expect(consoleError).toHaveBeenCalledWith(
        expect.stringContaining('diagnostics 执行失败'),
        expect.objectContaining({ diagnosticsCause: diagnosticsFailure }),
      );
    } finally {
      consoleError.mockRestore();
    }
  });

  it('未配置 diagnostics 时终态 Hook 失败默认显式报告且不改写 Outcome', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const terminalFailure = new Error('默认诊断路径');
    try {
      const execution = runtime(new QueueModel([textResponse('仍应完成')]), {
        hooks: [{
          onEvent: event => {
            if (event.type === 'execution-completed') {
              throw terminalFailure;
            }
          },
        }],
      }).start(input(toolSnapshot([])), () => undefined);

      await expect(execution.result).resolves.toMatchObject({
        outcome: { type: 'completed', stopReason: 'end-turn' },
      });
      expect(consoleError).toHaveBeenCalledTimes(1);
      expect(consoleError).toHaveBeenCalledWith(
        expect.stringContaining('已保留原终态'),
        expect.objectContaining({ cause: terminalFailure }),
      );
    } finally {
      consoleError.mockRestore();
    }
  });

  it('model-call-completed Hook 推进时钟到 Deadline 后不得发布消息或成功', async () => {
    let currentTime = 0;
    const events: AgentExecutionEvent[] = [];
    const execution = runtime(new QueueModel([textResponse('已经封闭')]), {
      limits: { maxDurationMs: 1_000 },
      now: () => currentTime,
      hooks: [{
        onEvent: event => {
          if (event.type === 'model-call-completed') {
            currentTime = 1_000;
          }
        },
      }],
    }).start(input(toolSnapshot([])), event => events.push(event));

    await expect(execution.result).rejects.toMatchObject({ code: 'AGENT_EXECUTION_TIMEOUT' });
    expect(events.some(event => event.type === 'messages')).toBe(false);
  });

  it('稳定 messages listener 推进时钟到 Deadline 后不得把 Execution 判为成功', async () => {
    let currentTime = 0;
    const events: AgentExecutionEvent[] = [];
    const execution = runtime(new QueueModel([textResponse('已经封闭')]), {
      limits: { maxDurationMs: 1_000 },
      now: () => currentTime,
    }).start(input(toolSnapshot([])), event => {
      events.push(event);
      if (event.type === 'messages') {
        currentTime = 1_000;
      }
    });

    await expect(execution.result).rejects.toMatchObject({ code: 'AGENT_EXECUTION_TIMEOUT' });
    expect(events.filter(event => event.type === 'messages')).toHaveLength(1);
  });

  it('正文 delta listener 重入取消后不再发起额外 iterator.next', async () => {
    const model = new ReentrantCancellationModel();
    let engine!: DefaultAgentRuntime;
    engine = runtime(model);
    const execution = engine.start(input(toolSnapshot([])), event => {
      if (event.type === 'text-delta') {
        engine.dispose();
      }
    });

    await expect(execution.result).resolves.toMatchObject({ outcome: { type: 'cancelled' } });
    expect(model.nextCalls).toBe(2);
    expect(model.returnCalls).toBe(1);
  });

  it('连续空增量之间注销前一活动 waiter，不按事件数保留停止订阅', async () => {
    const model = new EmptyDeltaBurstModel();
    let execution: AgentExecution | null = null;
    let inspectedReads = 0;
    model.inspectBeforeNext = () => {
      if (execution == null) {
        return;
      }
      inspectedReads += 1;
      expect((execution as unknown as { activeOperationStop: unknown }).activeOperationStop)
        .toBeNull();
    };
    execution = runtime(model).start(input(toolSnapshot([])), () => undefined);

    await expect(execution.result).resolves.toMatchObject({ outcome: { type: 'completed' } });
    expect(inspectedReads).toBeGreaterThan(128);
    expect((execution as unknown as { activeOperationStop: unknown }).activeOperationStop)
      .toBeNull();
  });

  it.each([
    ['非对象', null],
    ['数组', []],
    ['错误 content 类型', { toolCallId: 'call-1', content: 1, isError: false }],
    ['错误 isError 类型', { toolCallId: 'call-1', content: 'ok', isError: 'false' }],
    ['空关联 ID', { toolCallId: null, content: 'ok', isError: false }],
    ['错误关联 ID', { toolCallId: 'call-other', content: 'ok', isError: false }],
    ['未知字段', { toolCallId: 'call-1', content: 'ok', isError: false, extra: true }],
  ] as const)('Tool Adapter 返回%s时按协议失败且不发布结果', async (_label, malformed) => {
    const model = new QueueModel([
      toolResponse([{ callId: 'call-1', name: 'local.strict' }]),
      textResponse('不应调用'),
    ]);
    const invoke = vi.fn<ToolRegistrySnapshot['invoke']>(async () =>
      malformed as unknown as ToolCallResult);
    const events: AgentExecutionEvent[] = [];
    const execution = runtime(model).start(
      input(toolSnapshot(['local.strict'], invoke)),
      event => events.push(event),
    );

    await expect(execution.result).rejects.toMatchObject({
      code: 'MODEL_PROTOCOL_ERROR',
      retryable: false,
    });
    expect(invoke).toHaveBeenCalledTimes(1);
    expect(model.calls).toHaveLength(1);
    expect(events.some(event => event.type === 'tool-result')).toBe(false);
  });

  it('Hook 重入 dispose 时当前 Execution 已被纳管且不会调用 Model', async () => {
    const model = new QueueModel([textResponse('不应调用')]);
    const hookEvents: AgentLifecycleEvent[] = [];
    let engine!: DefaultAgentRuntime;
    engine = runtime(model, {
      hooks: [{
        onEvent: event => {
          hookEvents.push(event);
          if (event.type === 'model-call-started') {
            engine.dispose();
          }
        },
      }],
    });

    const execution = engine.start(input(toolSnapshot([])), () => undefined);

    await expect(execution.result).resolves.toMatchObject({
      outcome: { type: 'cancelled' },
    });
    expect(model.calls).toHaveLength(0);
    expect(hookEvents.map(event => event.type)).toEqual([
      'execution-started',
      'model-call-started',
      'execution-completed',
    ]);
  });
});
