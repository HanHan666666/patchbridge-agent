/**
 * 调用轨迹存储测试：验证生命周期配对、稳定内容补全与 localStorage 保留边界。
 *
 * <p>测试使用可控时钟和内存 Storage，不依赖浏览器；持久化不是服务端真相源，
 * 因此重点覆盖损坏缓存整体拒绝、写入失败显式暴露，以及草稿会话保存后的迁移。
 */
import { describe, expect, it } from 'vitest';
import {
  CALL_TRACE_MAX_TRACES_PER_CONVERSATION,
  CallTraceStore,
  type CallTraceRecord,
  type CallTraceStorage,
  type ConfirmationTraceRecord,
  type ModelCallTraceRecord,
  type ToolCallTraceRecord,
} from '../src/callTrace';
import type { AgentHookContext } from '../src/extensions';
import type {
  AgentMessage,
  AgentRunOutcome,
  ToolDefinition,
} from '../src/types';

/** 自然结束轨迹复用的统一完成态。 */
const COMPLETED_OUTCOME = {
  type: 'completed',
  stopReason: 'end-turn',
} as const;

/** 可注入失败的内存 Storage；data 便于断言真实序列化结果。 */
class MemoryStorage implements CallTraceStorage {
  /** localStorage 键值映射。 */
  readonly data = new Map<string, string>();
  /** 为 true 时 setItem 模拟配额溢出。 */
  failSet = false;

  /** 读取指定键。 */
  getItem(key: string): string | null {
    return this.data.get(key) ?? null;
  }

  /** 写入指定键；测试可显式触发失败。 */
  setItem(key: string, value: string): void {
    if (this.failSet) {
      throw new Error('localStorage quota exceeded');
    }
    this.data.set(key, value);
  }

  /** 删除指定键。 */
  removeItem(key: string): void {
    this.data.delete(key);
  }
}

/** 构造单调递增的 epoch 毫秒时间源。 */
function controllableClock(start = 1000): () => number {
  let current = start;
  return () => current++;
}

/** 一次执行的最小 Hook 上下文。 */
function context(traceId: string, conversationId: string | null): AgentHookContext {
  return Object.freeze({ traceId, conversationId });
}

/** HITL 测试使用的 Tool 定义。 */
function toolDefinition(): ToolDefinition {
  return Object.freeze({
    name: 'local.device_restart',
    title: '重启设备',
    description: '重启指定设备',
    inputSchema: { type: 'object' },
    annotations: {
      readOnlyHint: false,
      destructiveHint: true,
      idempotentHint: true,
      requireConfirmation: true,
    },
    source: 'LOCAL',
    permissions: Object.freeze(['device:restart']),
  });
}

/** 完成本轮执行，供保留上限与清理测试复用。 */
function completeExecution(
  store: CallTraceStore,
  traceId: string,
  conversationId: string | null,
): void {
  const ctx = context(traceId, conversationId);
  store.onEvent({
    type: 'execution-started',
    initialMessageCount: 0,
    toolRevision: 1,
  }, ctx);
  completeNaturalModelCall(store, ctx, `${traceId}-message-1`);
  store.onEvent({
    type: 'execution-completed',
    outcome: COMPLETED_OUTCOME,
    addedMessageCount: 1,
  }, ctx);
}

/** 发布一条完整自然结束模型记录，保证完成态轨迹具有直接模型证据。 */
function completeNaturalModelCall(
  store: CallTraceStore,
  ctx: AgentHookContext,
  responseMessageId: string,
  callIndex = 1,
): void {
  store.onEvent({
    type: 'model-call-started',
    callIndex,
    responseMessageId,
  }, ctx);
  store.onEvent({
    type: 'model-call-completed',
    callIndex,
    responseMessageId,
    stopReason: 'end-turn',
    usage: null,
    firstTokenLatencyMs: 0,
    outputDurationMs: 0,
  }, ctx);
}

/** 创建已经发布 execution-started 的实时 Store 与 Hook 上下文。 */
function startedTraceStore(traceId: string): {
  readonly store: CallTraceStore;
  readonly ctx: AgentHookContext;
} {
  const store = new CallTraceStore({ storage: new MemoryStorage(), now: controllableClock() });
  store.setActiveConversation('c1');
  const ctx = context(traceId, 'c1');
  store.onEvent({ type: 'execution-started', initialMessageCount: 1, toolRevision: 1 }, ctx);
  return { store, ctx };
}

/** 把已开始的模型记录封闭为合法 tool-use，供实时 Tool 引用测试复用。 */
function completeToolUseModelCall(
  store: CallTraceStore,
  ctx: AgentHookContext,
  callIndex: number,
  responseMessageId: string,
): void {
  store.onEvent({
    type: 'model-call-completed',
    callIndex,
    responseMessageId,
    stopReason: 'tool-use',
    usage: null,
    firstTokenLatencyMs: 10,
    outputDurationMs: 20,
  }, ctx);
}

/** 构造可持久化的已完成模型记录，关系测试只覆盖显式覆盖的字段。 */
function persistedModelRecord(
  overrides: Partial<ModelCallTraceRecord> = {},
): ModelCallTraceRecord {
  return {
    type: 'model-call',
    callIndex: 1,
    responseMessageId: 'message-1',
    startedAt: 110,
    endedAt: 180,
    stopReason: 'end-turn',
    usage: { inputTokens: 20, outputTokens: 10, totalTokens: 30 },
    firstTokenLatencyMs: 40,
    outputDurationMs: 30,
    text: '完成',
    reasoning: '',
    toolCallIds: [],
    ...overrides,
  };
}

/** 构造可持久化的已完成 Tool 记录。 */
function persistedToolRecord(
  overrides: Partial<ToolCallTraceRecord> = {},
): ToolCallTraceRecord {
  return {
    type: 'tool-call',
    callId: 'call-1',
    toolName: 'local.device_get',
    startedAt: 190,
    endedAt: 250,
    argumentsText: '{}',
    resultText: '完成',
    isError: false,
    ...overrides,
  };
}

/** 构造可持久化的已响应确认记录。 */
function persistedConfirmationRecord(
  overrides: Partial<ConfirmationTraceRecord> = {},
): ConfirmationTraceRecord {
  return {
    type: 'confirmation',
    callId: 'call-1',
    toolName: 'local.device_get',
    requestedAt: 200,
    resolvedAt: 220,
    approved: true,
    ...overrides,
  };
}

/** 写入一条 schema v3 终态轨迹，供跨记录关系恢复测试复用。 */
function writePersistedTrace(
  storage: MemoryStorage,
  traceId: string,
  records: readonly CallTraceRecord[],
  terminal: {
    readonly outcome?: AgentRunOutcome | null;
    readonly failed?: { readonly code: string; readonly message: string } | null;
  } = {},
): void {
  const failed = terminal.failed ?? null;
  const outcome = terminal.outcome === undefined
    ? (failed == null ? COMPLETED_OUTCOME : null)
    : terminal.outcome;
  storage.data.set('patchbridge-agent:call-trace:c1', JSON.stringify({
    version: 3,
    traces: [{
      traceId,
      conversationId: 'c1',
      startedAt: 100,
      endedAt: 400,
      outcome,
      failed,
      records,
    }],
  }));
}

/** 构造包含正文、思考和 Tool Call 的稳定 Assistant 消息。 */
function assistantMessage(): AgentMessage {
  return Object.freeze({
    id: 'message-1',
    role: 'assistant',
    blocks: Object.freeze([
      Object.freeze({ type: 'reasoning', text: '需要先查询设备状态' }),
      Object.freeze({ type: 'text', text: '我将重启目标设备。' }),
      Object.freeze({
        type: 'tool-call',
        callId: 'call-1',
        name: 'local.device_restart',
        input: Object.freeze({ serial: 'DEV-1' }),
      }),
    ]),
  });
}

/** 构造回填给模型的稳定 Tool 结果消息。 */
function toolResultMessage(): AgentMessage {
  return Object.freeze({
    id: 'message-tool-1',
    role: 'tool',
    blocks: Object.freeze([Object.freeze({
      type: 'tool-result',
      callId: 'call-1',
      name: 'local.device_restart',
      status: 'success',
      content: Object.freeze([Object.freeze({ type: 'text', text: '设备已重启' })]),
    })]),
  });
}

describe('CallTraceStore', () => {
  it('启动 Hook 失败后的用户输入补录仍可持久化并完整恢复', () => {
    const storage = new MemoryStorage();
    const store = new CallTraceStore({ storage, now: controllableClock(), persist: true });
    store.setActiveConversation('c1');
    const ctx = context('trace-started-failure', 'c1');

    store.onEvent({
      type: 'execution-started',
      initialMessageCount: 1,
      toolRevision: 1,
    }, ctx);
    store.onEvent({
      type: 'execution-failed',
      errorCode: 'HOST_HOOK_FAILED',
      errorMessage: '宿主 execution-started Hook 失败',
    }, ctx);
    store.noteUserInput('trace-started-failure', '触发宿主 Hook 失败', 0);

    const live = store.snapshot().traces[0];
    expect(live?.records).toEqual([
      expect.objectContaining({
        type: 'user-input',
        at: live?.startedAt,
        text: '触发宿主 Hook 失败',
      }),
    ]);

    const restored = new CallTraceStore({
      storage,
      now: controllableClock(2000),
      persist: true,
    });
    restored.setActiveConversation('c1');
    expect(restored.snapshot().persistenceError).toBeNull();
    expect(restored.snapshot().traces).toEqual([live]);
  });

  it('配对完整模型/Tool/HITL 生命周期并补全稳定消息内容', () => {
    const storage = new MemoryStorage();
    const store = new CallTraceStore({ storage, now: controllableClock(), persist: true });
    store.setActiveConversation('c1');
    const ctx = context('trace-1', 'c1');

    store.onEvent({ type: 'execution-started', initialMessageCount: 1, toolRevision: 3 }, ctx);
    store.noteUserInput('trace-1', '重启 DEV-1', 1);
    store.onEvent({ type: 'model-call-started', callIndex: 1, responseMessageId: 'message-1' }, ctx);
    store.onEvent({
      type: 'model-call-completed',
      callIndex: 1,
      responseMessageId: 'message-1',
      stopReason: 'tool-use',
      usage: { inputTokens: 120, outputTokens: 20, totalTokens: 140 },
      firstTokenLatencyMs: 0.4,
      outputDurationMs: 0.5,
    }, ctx);
    store.noteCommittedMessages([assistantMessage()]);
    store.onEvent({
      type: 'tool-call-started',
      callId: 'call-1',
      toolName: 'local.device_restart',
      arguments: { serial: 'DEV-1' },
    }, ctx);
    store.onEvent({
      type: 'interrupt-requested',
      interrupt: {
        id: 'interrupt-1',
        type: 'tool-confirmation',
        tool: toolDefinition(),
        arguments: { serial: 'DEV-1' },
      },
    }, ctx);
    store.onEvent({ type: 'interrupt-resolved', interruptId: 'interrupt-1', value: true }, ctx);
    store.onEvent({
      type: 'tool-call-completed',
      callId: 'call-1',
      toolName: 'local.device_restart',
      isError: false,
    }, ctx);
    store.noteCommittedMessages([toolResultMessage()]);
    store.onEvent({
      type: 'model-call-started',
      callIndex: 2,
      responseMessageId: 'message-2',
    }, ctx);
    store.onEvent({
      type: 'model-call-completed',
      callIndex: 2,
      responseMessageId: 'message-2',
      stopReason: 'end-turn',
      usage: null,
      firstTokenLatencyMs: 0.3,
      outputDurationMs: 0.4,
    }, ctx);
    store.onEvent({
      type: 'execution-completed',
      outcome: COMPLETED_OUTCOME,
      addedMessageCount: 3,
    }, ctx);

    const trace = store.snapshot().traces[0];
    expect(trace?.records.map(record => record.type)).toEqual([
      'user-input',
      'model-call',
      'tool-call',
      'confirmation',
      'model-call',
    ]);
    const model = trace?.records[1] as ModelCallTraceRecord;
    expect(model).toMatchObject({
      text: '我将重启目标设备。',
      reasoning: '需要先查询设备状态',
      toolCallIds: ['call-1'],
      usage: { inputTokens: 120, outputTokens: 20, totalTokens: 140 },
      firstTokenLatencyMs: 0.4,
      outputDurationMs: 0.5,
    });
    const tool = trace?.records[2] as ToolCallTraceRecord;
    expect(tool.argumentsText).toContain('DEV-1');
    expect(tool.resultText).toBe('设备已重启');
    const confirmation = trace?.records[3] as ConfirmationTraceRecord;
    expect(confirmation).toMatchObject({ callId: 'call-1', approved: true });
    expect(storage.data.has('patchbridge-agent:call-trace:c1')).toBe(true);

    const restored = new CallTraceStore({ storage, now: controllableClock(5000), persist: true });
    restored.setActiveConversation('c1');
    const restoredModel = restored.snapshot().traces[0]?.records[1];
    expect(restoredModel).toMatchObject({
      type: 'model-call',
      firstTokenLatencyMs: 0.4,
      outputDurationMs: 0.5,
    });
  });

  it('实时采集立即拒绝重复身份与断裂 Tool 引用', () => {
    {
      const { store, ctx } = startedTraceStore('trace-duplicate-index');
      store.onEvent({ type: 'model-call-started', callIndex: 1, responseMessageId: 'assistant-1' }, ctx);
      expect(() => store.onEvent({
        type: 'model-call-started',
        callIndex: 1,
        responseMessageId: 'assistant-2',
      }, ctx)).toThrow('模型调用序号 1 已存在');
    }
    {
      const { store, ctx } = startedTraceStore('trace-duplicate-response');
      store.onEvent({ type: 'model-call-started', callIndex: 1, responseMessageId: 'assistant-1' }, ctx);
      expect(() => store.onEvent({
        type: 'model-call-started',
        callIndex: 2,
        responseMessageId: 'assistant-1',
      }, ctx)).toThrow('响应消息 assistant-1 已存在');
    }
    {
      const { store, ctx } = startedTraceStore('trace-undeclared-tool');
      expect(() => store.onEvent({
        type: 'tool-call-started',
        callId: 'call-missing',
        toolName: 'local.device_restart',
        arguments: {},
      }, ctx)).toThrow('没有对应的模型声明');
    }
    {
      const { store, ctx } = startedTraceStore('trace-duplicate-message-tool');
      store.onEvent({ type: 'model-call-started', callIndex: 1, responseMessageId: 'assistant-duplicate' }, ctx);
      const duplicateMessage: AgentMessage = Object.freeze({
        id: 'assistant-duplicate',
        role: 'assistant',
        blocks: Object.freeze([
          Object.freeze({
            type: 'tool-call',
            callId: 'call-1',
            name: 'local.device_restart',
            input: Object.freeze({ serial: 'DEV-1' }),
          }),
          Object.freeze({
            type: 'tool-call',
            callId: 'call-1',
            name: 'local.device_restart',
            input: Object.freeze({ serial: 'DEV-2' }),
          }),
        ]),
      });
      expect(() => store.noteCommittedMessages([duplicateMessage]))
        .toThrow('包含空或重复的 Tool Call ID');
    }
    {
      const { store, ctx } = startedTraceStore('trace-cross-model-tool');
      store.onEvent({ type: 'model-call-started', callIndex: 1, responseMessageId: 'message-1' }, ctx);
      completeToolUseModelCall(store, ctx, 1, 'message-1');
      store.noteCommittedMessages([assistantMessage()]);
      store.onEvent({ type: 'model-call-started', callIndex: 2, responseMessageId: 'message-2' }, ctx);
      completeToolUseModelCall(store, ctx, 2, 'message-2');
      const secondMessage: AgentMessage = Object.freeze({
        ...assistantMessage(),
        id: 'message-2',
      });
      expect(() => store.noteCommittedMessages([secondMessage]))
        .toThrow('模型记录重复声明 Tool Call ID');
    }
    {
      const { store, ctx } = startedTraceStore('trace-duplicate-tool');
      store.onEvent({ type: 'model-call-started', callIndex: 1, responseMessageId: 'message-1' }, ctx);
      completeToolUseModelCall(store, ctx, 1, 'message-1');
      store.noteCommittedMessages([assistantMessage()]);
      expect(() => store.noteCommittedMessages([assistantMessage()])).not.toThrow();
      const toolStarted = {
        type: 'tool-call-started' as const,
        callId: 'call-1',
        toolName: 'local.device_restart',
        arguments: { serial: 'DEV-1' },
      };
      store.onEvent(toolStarted, ctx);
      expect(() => store.onEvent(toolStarted, ctx)).toThrow('Tool 调用 call-1 已存在');
      const orphaningMessage: AgentMessage = Object.freeze({
        ...assistantMessage(),
        blocks: Object.freeze([]),
      });
      expect(() => store.noteCommittedMessages([orphaningMessage]))
        .toThrow('回填破坏了 Tool 引用关系');
      const confirmationRequested = {
        type: 'interrupt-requested' as const,
        interrupt: {
          id: 'interrupt-1',
          type: 'tool-confirmation' as const,
          tool: toolDefinition(),
          arguments: { serial: 'DEV-1' },
        },
      };
      store.onEvent(confirmationRequested, ctx);
      expect(() => store.onEvent(confirmationRequested, ctx)).toThrow('已存在确认记录');
    }
    {
      const { store, ctx } = startedTraceStore('trace-unstarted-tool-terminal');
      store.onEvent({ type: 'model-call-started', callIndex: 1, responseMessageId: 'message-1' }, ctx);
      completeToolUseModelCall(store, ctx, 1, 'message-1');
      store.noteCommittedMessages([assistantMessage()]);
      expect(() => store.onEvent({
        type: 'execution-completed',
        outcome: COMPLETED_OUTCOME,
        addedMessageCount: 1,
      }, ctx)).toThrow('不能进入执行终态');
      expect(() => store.onEvent({
        type: 'execution-completed',
        outcome: { type: 'cancelled' },
        addedMessageCount: 1,
      }, ctx)).not.toThrow();
    }
    {
      const { store, ctx } = startedTraceStore('trace-outcome-mismatch');
      store.onEvent({ type: 'model-call-started', callIndex: 1, responseMessageId: 'message-1' }, ctx);
      store.onEvent({
        type: 'model-call-completed',
        callIndex: 1,
        responseMessageId: 'message-1',
        stopReason: 'end-turn',
        usage: null,
        firstTokenLatencyMs: 10,
        outputDurationMs: 20,
      }, ctx);
      expect(() => store.onEvent({
        type: 'execution-completed',
        outcome: { type: 'max-tokens' },
        addedMessageCount: 1,
      }, ctx)).toThrow('模型停止原因与执行终态不一致');
    }
  });

  it('从 localStorage 深度恢复同会话终态轨迹', () => {
    const storage = new MemoryStorage();
    const writer = new CallTraceStore({ storage, now: controllableClock(), persist: true });
    writer.setActiveConversation('c1');
    completeExecution(writer, 'trace-1', 'c1');

    const reader = new CallTraceStore({ storage, now: controllableClock(2000), persist: true });
    reader.setActiveConversation('c1');

    expect(reader.snapshot().persistenceError).toBeNull();
    expect(reader.snapshot().traces).toHaveLength(1);
    expect(reader.snapshot().traces[0]).toMatchObject({
      traceId: 'trace-1',
      conversationId: 'c1',
      outcome: COMPLETED_OUTCOME,
    });
    expect(Object.isFrozen(reader.snapshot().traces[0])).toBe(true);
  });

  it('未显式激活会话时开始新执行也会先恢复已有本地历史', () => {
    const storage = new MemoryStorage();
    const writer = new CallTraceStore({ storage, now: controllableClock(), persist: true });
    writer.setActiveConversation('c1');
    completeExecution(writer, 'trace-old', 'c1');

    const nextPage = new CallTraceStore({ storage, now: controllableClock(2000), persist: true });
    completeExecution(nextPage, 'trace-new', 'c1');
    const verifier = new CallTraceStore({ storage, now: controllableClock(3000), persist: true });
    verifier.setActiveConversation('c1');

    expect(verifier.snapshot().traces.map(trace => trace.traceId)).toEqual([
      'trace-old',
      'trace-new',
    ]);
  });

  it(`每会话只保留最近 ${CALL_TRACE_MAX_TRACES_PER_CONVERSATION} 条终态轨迹`, () => {
    const storage = new MemoryStorage();
    const store = new CallTraceStore({ storage, now: controllableClock(), persist: true });
    store.setActiveConversation('c1');

    for (let index = 1; index <= CALL_TRACE_MAX_TRACES_PER_CONVERSATION + 1; index += 1) {
      completeExecution(store, `trace-${index}`, 'c1');
    }

    expect(store.snapshot().traces).toHaveLength(CALL_TRACE_MAX_TRACES_PER_CONVERSATION);
    expect(store.snapshot().traces[0]?.traceId).toBe('trace-2');
    const persisted = JSON.parse(storage.data.get('patchbridge-agent:call-trace:c1') ?? '{}');
    expect(persisted.traces).toHaveLength(CALL_TRACE_MAX_TRACES_PER_CONVERSATION);
    // trace-1 已被淘汰，索引也必须释放；重复 ID 用于明确验证没有内存泄漏残留。
    expect(() => completeExecution(store, 'trace-1', 'c1')).not.toThrow();
  });

  it('持久化终态轨迹时不删除同会话仍在执行的轨迹', () => {
    const storage = new MemoryStorage();
    const store = new CallTraceStore({ storage, now: controllableClock(), persist: true });
    store.setActiveConversation('c1');
    const running = context('trace-running', 'c1');
    store.onEvent({ type: 'execution-started', initialMessageCount: 0, toolRevision: 1 }, running);

    completeExecution(store, 'trace-completed', 'c1');

    expect(store.snapshot().traces.map(trace => trace.traceId)).toEqual([
      'trace-running',
      'trace-completed',
    ]);
    expect(store.snapshot().traces[0]?.endedAt).toBeNull();
  });

  it('首轮草稿轨迹在会话保存后迁移并持久化', () => {
    const storage = new MemoryStorage();
    const store = new CallTraceStore({ storage, now: controllableClock(), persist: true });
    completeExecution(store, 'trace-draft', null);

    expect(storage.data.size).toBe(0);
    store.attachConversation('trace-draft', 'c1');
    store.setActiveConversation('c1');

    expect(store.snapshot().traces[0]).toMatchObject({
      traceId: 'trace-draft',
      conversationId: 'c1',
    });
    expect(storage.data.has('patchbridge-agent:call-trace:c1')).toBe(true);
  });

  it('草稿迁移到已有本地历史的会话时先恢复再追加', () => {
    const storage = new MemoryStorage();
    const writer = new CallTraceStore({ storage, now: controllableClock(), persist: true });
    writer.setActiveConversation('c1');
    completeExecution(writer, 'trace-old', 'c1');

    const nextPage = new CallTraceStore({ storage, now: controllableClock(2000), persist: true });
    completeExecution(nextPage, 'trace-draft', null);
    nextPage.attachConversation('trace-draft', 'c1');
    const verifier = new CallTraceStore({ storage, now: controllableClock(3000), persist: true });
    verifier.setActiveConversation('c1');

    expect(verifier.snapshot().traces.map(trace => trace.traceId)).toEqual([
      'trace-old',
      'trace-draft',
    ]);
  });

  it('本地 schema v2 不作为 v3 Outcome 轨迹恢复', () => {
    const storage = new MemoryStorage();
    storage.data.set('patchbridge-agent:call-trace:c1', JSON.stringify({
      version: 2,
      traces: [],
    }));
    const store = new CallTraceStore({ storage, now: controllableClock(), persist: true });

    store.setActiveConversation('c1');

    expect(store.snapshot().traces).toEqual([]);
    expect(store.snapshot().persistenceError).toContain('轨迹数据结构不符合当前版本');
  });

  it('v3 严格恢复 max-tokens 终态并冻结 Outcome', () => {
    const storage = new MemoryStorage();
    writePersistedTrace(
      storage,
      'trace-max-tokens',
      [persistedModelRecord({ stopReason: 'max-tokens' })],
      { outcome: { type: 'max-tokens' } },
    );
    const store = new CallTraceStore({ storage, now: controllableClock(), persist: true });

    store.setActiveConversation('c1');

    const trace = store.snapshot().traces[0];
    expect(store.snapshot().persistenceError).toBeNull();
    expect(trace?.outcome).toEqual({ type: 'max-tokens' });
    expect(Object.isFrozen(trace?.outcome)).toBe(true);
  });

  it('v3 拒绝非法 Outcome、终态冲突与未知字段', () => {
    const invalidCases: readonly (readonly [
      string,
      (trace: Record<string, unknown>) => void,
    ])[] = [
      ['completed 使用 tool-use', trace => {
        trace['outcome'] = { type: 'completed', stopReason: 'tool-use' };
      }],
      ['Outcome 含未知字段', trace => {
        trace['outcome'] = { type: 'max-tokens', legacy: false };
      }],
      ['Outcome 与失败同时存在', trace => {
        trace['failed'] = { code: 'MODEL_FAILED', message: '失败' };
      }],
      ['Outcome 与失败同时缺失', trace => {
        trace['outcome'] = null;
      }],
      ['completed 与最后模型停止原因不一致', trace => {
        trace['outcome'] = { type: 'completed', stopReason: 'other' };
      }],
      ['max-tokens 与最后模型停止原因不一致', trace => {
        trace['outcome'] = { type: 'max-tokens' };
      }],
      ['自然完成缺少模型调用记录', trace => {
        trace['records'] = [];
      }],
      ['Execution 含未知字段', trace => {
        trace['legacy'] = false;
      }],
      ['记录含未知字段', trace => {
        trace['records'] = [{ ...persistedModelRecord(), legacy: false }];
      }],
      ['未结束模型记录提前携带 usage', trace => {
        trace['outcome'] = { type: 'cancelled' };
        trace['records'] = [persistedModelRecord({
          endedAt: null,
          stopReason: null,
          firstTokenLatencyMs: null,
          outputDurationMs: null,
        })];
      }],
      ['未结束 Tool 记录提前携带结果', trace => {
        trace['outcome'] = { type: 'cancelled' };
        trace['records'] = [
          persistedModelRecord({
            stopReason: 'tool-use',
            toolCallIds: ['call-1'],
          }),
          persistedToolRecord({
            endedAt: null,
            isError: null,
          }),
        ];
      }],
    ];

    for (const [caseName, mutate] of invalidCases) {
      const storage = new MemoryStorage();
      writePersistedTrace(storage, `trace-strict-${caseName}`, [persistedModelRecord()]);
      const raw = storage.data.get('patchbridge-agent:call-trace:c1');
      if (raw == null) {
        throw new Error('测试轨迹没有写入内存 Storage');
      }
      const payload = JSON.parse(raw) as { traces: Array<Record<string, unknown>> };
      const trace = payload.traces[0];
      if (trace == null) {
        throw new Error('测试轨迹载荷为空');
      }
      mutate(trace);
      storage.data.set('patchbridge-agent:call-trace:c1', JSON.stringify(payload));
      const store = new CallTraceStore({ storage, now: controllableClock(), persist: true });

      store.setActiveConversation('c1');

      expect(store.snapshot().traces, caseName).toEqual([]);
      expect(store.snapshot().persistenceError, caseName)
        .toContain('轨迹数据结构不符合当前版本');
    }
  });

  it('损坏或跨会话的本地数据整体拒绝并显式暴露错误', () => {
    const storage = new MemoryStorage();
    storage.data.set('patchbridge-agent:call-trace:c1', JSON.stringify({
      version: 3,
      traces: [{
        traceId: 'trace-bad',
        conversationId: 'another-conversation',
        startedAt: 100,
        endedAt: 200,
        outcome: COMPLETED_OUTCOME,
        failed: null,
        records: [{ type: 'model-call', usage: { inputTokens: 'bad' } }],
      }],
    }));
    const store = new CallTraceStore({ storage, now: controllableClock(), persist: true });

    store.setActiveConversation('c1');

    expect(store.snapshot().traces).toEqual([]);
    expect(store.snapshot().persistenceError).toContain('轨迹数据结构不符合当前版本');
  });

  it('恢复拒绝超长字段与 Execution 时间区间外的记录', () => {
    const storage = new MemoryStorage();
    storage.data.set('patchbridge-agent:call-trace:c1', JSON.stringify({
      version: 3,
      traces: [{
        traceId: 'trace-long',
        conversationId: 'c1',
        startedAt: 100,
        endedAt: 200,
        outcome: COMPLETED_OUTCOME,
        failed: null,
        records: [{
          type: 'user-input',
          at: 99,
          text: 'x'.repeat(4001),
          imageCount: 0,
        }],
      }],
    }));
    const store = new CallTraceStore({ storage, now: controllableClock(), persist: true });

    store.setActiveConversation('c1');

    expect(store.snapshot().traces).toEqual([]);
    expect(store.snapshot().persistenceError).toContain('轨迹数据结构不符合当前版本');
  });

  it('恢复拒绝负数模型性能计时', () => {
    const storage = new MemoryStorage();
    storage.data.set('patchbridge-agent:call-trace:c1', JSON.stringify({
      version: 3,
      traces: [{
        traceId: 'trace-metrics',
        conversationId: 'c1',
        startedAt: 100,
        endedAt: 300,
        outcome: COMPLETED_OUTCOME,
        failed: null,
        records: [{
          type: 'model-call',
          callIndex: 1,
          responseMessageId: 'message-1',
          startedAt: 110,
          endedAt: 290,
          stopReason: 'end-turn',
          usage: { inputTokens: 20, outputTokens: 10, totalTokens: 30 },
          firstTokenLatencyMs: -1,
          outputDurationMs: 100,
          text: '完成',
          reasoning: '',
          toolCallIds: [],
        }],
      }],
    }));
    const store = new CallTraceStore({ storage, now: controllableClock(), persist: true });

    store.setActiveConversation('c1');

    expect(store.snapshot().traces).toEqual([]);
    expect(store.snapshot().persistenceError).toContain('轨迹数据结构不符合当前版本');
  });

  it('性能时钟与 epoch 时钟独立时仍恢复合法模型计时', () => {
    const storage = new MemoryStorage();
    const writer = new CallTraceStore({ storage, now: controllableClock(100), persist: true });
    writer.setActiveConversation('c1');
    const ctx = context('trace-independent-clocks', 'c1');
    writer.onEvent({ type: 'execution-started', initialMessageCount: 1, toolRevision: 1 }, ctx);
    writer.onEvent({ type: 'model-call-started', callIndex: 1, responseMessageId: 'message-1' }, ctx);
    writer.onEvent({
      type: 'model-call-completed',
      callIndex: 1,
      responseMessageId: 'message-1',
      stopReason: 'end-turn',
      usage: { inputTokens: 20, outputTokens: 10, totalTokens: 30 },
      firstTokenLatencyMs: 40_600,
      outputDurationMs: 1000,
    }, ctx);
    writer.onEvent({
      type: 'execution-completed',
      outcome: COMPLETED_OUTCOME,
      addedMessageCount: 1,
    }, ctx);

    const reader = new CallTraceStore({ storage, now: controllableClock(500), persist: true });
    reader.setActiveConversation('c1');

    expect(reader.snapshot().persistenceError).toBeNull();
    expect(reader.snapshot().traces[0]?.records[0]).toMatchObject({
      type: 'model-call',
      firstTokenLatencyMs: 40_600,
      outputDurationMs: 1000,
    });
  });

  it('恢复拒绝重复记录身份与断裂 Tool 引用', () => {
    const invalidCases: readonly (readonly [string, readonly CallTraceRecord[]])[] = [
      ['重复 callIndex', [
        persistedModelRecord(),
        persistedModelRecord({ responseMessageId: 'message-2' }),
      ]],
      ['重复 responseMessageId', [
        persistedModelRecord(),
        persistedModelRecord({ callIndex: 2 }),
      ]],
      ['重复 Tool callId', [
        persistedModelRecord({ stopReason: 'tool-use', toolCallIds: ['call-1'] }),
        persistedToolRecord(),
        persistedToolRecord({ startedAt: 260, endedAt: 300 }),
      ]],
      ['模型内重复 Tool callId', [
        persistedModelRecord({
          stopReason: 'tool-use',
          toolCallIds: ['call-1', 'call-1'],
        }),
        persistedToolRecord(),
      ]],
      ['模型引用未启动 Tool', [
        persistedModelRecord({ stopReason: 'tool-use', toolCallIds: ['call-missing'] }),
      ]],
      ['Tool 缺少模型声明', [
        persistedModelRecord(),
        persistedToolRecord(),
      ]],
      ['确认引用不存在 Tool', [
        persistedModelRecord({ stopReason: 'tool-use', toolCallIds: ['call-1'] }),
        persistedToolRecord(),
        persistedConfirmationRecord({ callId: 'call-missing' }),
      ]],
      ['确认 Tool 名与调用不一致', [
        persistedModelRecord({ stopReason: 'tool-use', toolCallIds: ['call-1'] }),
        persistedToolRecord(),
        persistedConfirmationRecord({ toolName: 'local.other_tool' }),
      ]],
    ];

    for (const [caseName, records] of invalidCases) {
      const storage = new MemoryStorage();
      writePersistedTrace(storage, `trace-invalid-${caseName}`, records);
      const store = new CallTraceStore({ storage, now: controllableClock(), persist: true });

      store.setActiveConversation('c1');

      expect(store.snapshot().traces, caseName).toEqual([]);
      expect(store.snapshot().persistenceError, caseName)
        .toContain('轨迹数据结构不符合当前版本');
    }
  });

  it('取消执行允许保留模型已声明但尚未启动的 Tool', () => {
    const storage = new MemoryStorage();
    writePersistedTrace(
      storage,
      'trace-cancelled-before-tool',
      [persistedModelRecord({
        stopReason: 'tool-use',
        toolCallIds: ['call-not-started'],
      })],
      { outcome: { type: 'cancelled' } },
    );
    const store = new CallTraceStore({ storage, now: controllableClock(), persist: true });

    store.setActiveConversation('c1');

    expect(store.snapshot().persistenceError).toBeNull();
    expect(store.snapshot().traces[0]).toMatchObject({
      traceId: 'trace-cancelled-before-tool',
      outcome: { type: 'cancelled' },
    });
  });

  it('max-tokens 终态不允许保留尚未启动的 Tool', () => {
    const storage = new MemoryStorage();
    writePersistedTrace(
      storage,
      'trace-max-tokens-before-tool',
      [persistedModelRecord({
        stopReason: 'max-tokens',
        toolCallIds: ['call-not-started'],
      })],
      { outcome: { type: 'max-tokens' } },
    );
    const store = new CallTraceStore({ storage, now: controllableClock(), persist: true });

    store.setActiveConversation('c1');

    expect(store.snapshot().traces).toEqual([]);
    expect(store.snapshot().persistenceError)
      .toContain('轨迹数据结构不符合当前版本');
  });

  it('新采集超长内容连同截断标记不超过 4000 字符', () => {
    const storage = new MemoryStorage();
    const store = new CallTraceStore({ storage, now: controllableClock() });
    store.setActiveConversation('c1');
    const ctx = context('trace-long', 'c1');
    store.onEvent({ type: 'execution-started', initialMessageCount: 1, toolRevision: 1 }, ctx);
    store.noteUserInput('trace-long', 'x'.repeat(5000), 0);
    completeNaturalModelCall(store, ctx, 'trace-long-message-1');
    store.onEvent({
      type: 'execution-completed',
      outcome: COMPLETED_OUTCOME,
      addedMessageCount: 1,
    }, ctx);

    const record = store.snapshot().traces[0]?.records[0];
    expect(record?.type).toBe('user-input');
    if (record?.type !== 'user-input') {
      throw new Error('测试数据构造错误');
    }
    expect(record.text).toContain('已截断');
    expect(record.text.length).toBeLessThanOrEqual(4000);
  });

  it('localStorage 写入失败不打断内存轨迹并显式暴露错误', () => {
    const storage = new MemoryStorage();
    storage.failSet = true;
    const store = new CallTraceStore({ storage, now: controllableClock(), persist: true });
    store.setActiveConversation('c1');

    expect(() => completeExecution(store, 'trace-1', 'c1')).not.toThrow();
    expect(store.snapshot().traces).toHaveLength(1);
    expect(store.snapshot().persistenceError).toBe('localStorage quota exceeded');

    storage.failSet = false;
    store.clear();
    expect(store.snapshot().persistenceError).toBeNull();
  });

  it('clear 与 removeConversation 同步清理内存和当前会话本地键', () => {
    const storage = new MemoryStorage();
    const store = new CallTraceStore({ storage, now: controllableClock(), persist: true });
    store.setActiveConversation('c1');
    completeExecution(store, 'trace-1', 'c1');

    store.clear();
    expect(store.snapshot().traces).toEqual([]);
    expect(storage.data.has('patchbridge-agent:call-trace:c1')).toBe(false);

    completeExecution(store, 'trace-2', 'c1');
    store.removeConversation('c1');
    expect(store.snapshot().conversationId).toBeNull();
    expect(storage.data.has('patchbridge-agent:call-trace:c1')).toBe(false);
  });

  it('未开启持久化时全生命周期不访问存储端口', () => {
    for (const persist of [undefined, false]) {
      const accesses: string[] = [];
      const storage: CallTraceStorage = {
        getItem: key => {
          accesses.push(`get:${key}`);
          return null;
        },
        setItem: (key, value) => {
          accesses.push(`set:${key}:${value.length}`);
        },
        removeItem: key => {
          accesses.push(`remove:${key}`);
        },
      };
      const store = new CallTraceStore({ storage, now: controllableClock(), persist });
      const seen: number[] = [];
      store.subscribe(snapshot => seen.push(snapshot.traces.length));
      store.setActiveConversation('c1');
      const ctx = context('trace-memory-only', 'c1');
      store.onEvent({ type: 'execution-started', initialMessageCount: 1, toolRevision: 1 }, ctx);
      store.noteUserInput('trace-memory-only', '重启设备', 0);
      completeNaturalModelCall(store, ctx, 'message-1');
      store.onEvent({
        type: 'execution-completed',
        outcome: COMPLETED_OUTCOME,
        addedMessageCount: 1,
      }, ctx);
      store.attachConversation('trace-memory-only', 'c1');
      store.clear();
      store.removeConversation('c1');
      store.dispose();

      expect(accesses, `persist=${String(persist)}`).toEqual([]);
      expect(store.snapshot().traces).toEqual([]);
      expect(store.snapshot().persistenceError).toBeNull();
      expect(seen.at(-1)).toBe(0);
    }
  });

  it('取消和异常分别保留明确终态，进行中的模型记录不伪造耗时', () => {
    const storage = new MemoryStorage();
    const store = new CallTraceStore({ storage, now: controllableClock() });
    store.setActiveConversation('c1');
    const cancelled = context('trace-cancelled', 'c1');
    store.onEvent({ type: 'execution-started', initialMessageCount: 1, toolRevision: 1 }, cancelled);
    store.onEvent({ type: 'model-call-started', callIndex: 1, responseMessageId: 'message-1' }, cancelled);
    store.onEvent({
      type: 'execution-completed',
      outcome: { type: 'cancelled' },
      addedMessageCount: 0,
    }, cancelled);
    const failed = context('trace-failed', 'c1');
    store.onEvent({ type: 'execution-started', initialMessageCount: 1, toolRevision: 1 }, failed);
    store.onEvent({
      type: 'execution-failed',
      errorCode: 'MODEL_PROTOCOL_ERROR',
      errorMessage: '模型流协议错误',
    }, failed);

    expect(store.snapshot().traces[0]).toMatchObject({
      outcome: { type: 'cancelled' },
      failed: null,
    });
    expect((store.snapshot().traces[0]?.records[0] as ModelCallTraceRecord).endedAt).toBeNull();
    expect(store.snapshot().traces[1]?.failed).toEqual({
      code: 'MODEL_PROTOCOL_ERROR',
      message: '模型流协议错误',
    });
  });
});
