/**
 * 模型工作上下文管理器的核心业务测试。
 *
 * <p>这里验证完整历史与模型输入的分离、80% 自动阈值、Tool 原子边界、重复摘要、
 * Provider usage 强约束和失败原子性；HTTP 形状由独立 Adapter 测试覆盖。
 */
import { describe, expect, it, vi } from 'vitest';
import type {
  ContextCompactionGateway,
  ContextCompactionRequest,
} from '../src/clients/contextCompactionClient';
import {
  DefaultContextManager,
  EMPTY_MODEL_CONTEXT,
} from '../src/contextManager';
import type {
  AgentMessage,
  ContextCompactionConfiguration,
  ConversationContext,
  ModelContext,
} from '../src/types';

/** 使用小窗口让测试文本可以稳定跨过保留预算。 */
const CONFIGURATION: ContextCompactionConfiguration = Object.freeze({
  contextWindowTokens: 1_000,
  automaticThresholdTokens: 800,
  keepRecentTokens: 400,
  reservedOutputTokens: 100,
});

/** 创建单文本消息。 */
function textMessage(id: string, role: AgentMessage['role'], text: string): AgentMessage {
  return Object.freeze({
    id,
    role,
    blocks: Object.freeze([Object.freeze({ type: 'text' as const, text })]),
  });
}

/** 创建带可控用量的完整会话。 */
function conversation(
  messages: readonly AgentMessage[],
  totalTokens: number,
  modelContext: Partial<ModelContext> = {},
): ConversationContext {
  return Object.freeze({
    messages: Object.freeze([...messages]),
    modelContext: Object.freeze({
      ...EMPTY_MODEL_CONTEXT,
      ...modelContext,
      usage: modelContext.usage ?? Object.freeze({
        totalTokens,
        source: 'provider' as const,
        toolDefinitionTokens: 0,
        measuredThroughMessageId: messages[messages.length - 1]?.id ?? null,
      }),
    }),
  });
}

/** 创建记录压缩请求并返回固定摘要的测试 Gateway。 */
function gateway(
  requests: ContextCompactionRequest[],
  summary = '已确认目标与剩余工作。',
): ContextCompactionGateway {
  return {
    configuration: vi.fn(async () => CONFIGURATION),
    compact: vi.fn(async request => {
      requests.push(request);
      return Object.freeze({
        summary,
        usage: Object.freeze({ inputTokens: 80, outputTokens: 20, totalTokens: 100 }),
        modelState: null,
      });
    }),
  };
}

/** 创建固定 ID 与时间的待测管理器。 */
async function managerFor(
  compactionGateway: ContextCompactionGateway,
): Promise<DefaultContextManager> {
  let sequence = 0;
  const manager = new DefaultContextManager(compactionGateway, {
    createId: kind => `${kind}-${++sequence}`,
    now: () => Date.parse('2026-08-27T08:00:00.000Z'),
  });
  await manager.loadConfiguration();
  return manager;
}

describe('DefaultContextManager', () => {
  it('达到 80% 时自动压缩，但完整聊天历史保持原数组且模型输入改用摘要与近期尾部', async () => {
    const requests: ContextCompactionRequest[] = [];
    const manager = await managerFor(gateway(requests));
    const messages = Object.freeze([
      textMessage('system-1', 'system', '系统规则'),
      textMessage('user-old', 'user', '旧问题'.repeat(180)),
      textMessage('assistant-old', 'assistant', '旧答案'.repeat(180)),
      textMessage('user-recent', 'user', '近期问题'),
      textMessage('assistant-recent', 'assistant', '近期答案'),
    ]);
    const original = conversation(messages, 800);

    const prepared = await manager.prepareForModelCall({
      conversation: original,
      tools: [],
      callContext: { traceId: 'trace-1', conversationId: 'conversation-1' },
      signal: new AbortController().signal,
    });

    expect(requests).toHaveLength(1);
    expect(requests[0]?.trigger).toBe('automatic');
    expect(prepared.conversation.messages).toBe(original.messages);
    expect(prepared.conversation.modelContext.checkpoint).toMatchObject({
      summary: '已确认目标与剩余工作。',
      trigger: 'automatic',
      tokensBefore: 800,
      compactionCount: 1,
    });
    expect(prepared.modelMessages.map(message => message.id)).toEqual([
      'system-1',
      'context-summary-checkpoint-1',
      'user-recent',
      'assistant-recent',
    ]);
    expect(prepared.conversation.modelContext.usage?.source).toBe('estimated');
  });

  it('低于阈值时不调用摘要 Gateway，并直接构造未压缩模型消息', async () => {
    const requests: ContextCompactionRequest[] = [];
    const manager = await managerFor(gateway(requests));
    const original = conversation([
      textMessage('user-1', 'user', '问题'),
      textMessage('assistant-1', 'assistant', '答案'),
    ], 799);

    const prepared = await manager.prepareForModelCall({
      conversation: original,
      tools: [],
      callContext: { traceId: 'trace-2' },
      signal: new AbortController().signal,
    });

    expect(requests).toHaveLength(0);
    expect(prepared.conversation).toBe(original);
    expect(prepared.modelMessages.map(message => message.id)).toEqual([
      'user-1',
      'assistant-1',
    ]);
  });

  it('手动压缩不会切开 Assistant Tool Call 与连续 Tool Result', async () => {
    const requests: ContextCompactionRequest[] = [];
    const manager = await managerFor(gateway(requests));
    const toolAssistant: AgentMessage = Object.freeze({
      id: 'assistant-tool',
      role: 'assistant',
      blocks: Object.freeze([Object.freeze({
        type: 'tool-call' as const,
        callId: 'call-1',
        name: 'local.lookup',
        input: Object.freeze({ serial: 'D-1' }),
      })]),
    });
    const toolResult: AgentMessage = Object.freeze({
      id: 'tool-1',
      role: 'tool',
      blocks: Object.freeze([Object.freeze({
        type: 'tool-result' as const,
        callId: 'call-1',
        name: 'local.lookup',
        execution: 'completed' as const,
        status: 'success' as const,
        content: Object.freeze([Object.freeze({ type: 'text' as const, text: '在线' })]),
      })]),
    });
    const original = conversation([
      textMessage('user-old', 'user', '旧问题'.repeat(180)),
      textMessage('assistant-old', 'assistant', '旧回答'.repeat(180)),
      textMessage('user-tool', 'user', '查询设备'),
      toolAssistant,
      toolResult,
      textMessage('assistant-final', 'assistant', '设备在线'),
    ], 900);

    const compacted = await manager.compact(
      original,
      'manual',
      { traceId: 'trace-3' },
      new AbortController().signal,
    );

    const retainedIds = requests[0]?.retainedMessages.map(message => message.id);
    expect(retainedIds).toEqual([
      'user-tool',
      'assistant-tool',
      'tool-1',
      'assistant-final',
    ]);
    expect(compacted.modelContext.firstRetainedMessageId).toBe('user-tool');
  });

  it('近期预算使用 UTF-8 字节上界，不低估低压缩率 ASCII 内容', async () => {
    const requests: ContextCompactionRequest[] = [];
    const manager = await managerFor(gateway(requests));
    const original = conversation([
      textMessage('user-old', 'user', '旧问题'.repeat(180)),
      textMessage('assistant-old', 'assistant', '旧答案'.repeat(180)),
      textMessage('user-ascii', 'user', 'x'.repeat(300)),
      textMessage('assistant-recent', 'assistant', '近期答案'),
    ], 900);

    const compacted = await manager.compact(
      original,
      'manual',
      { traceId: 'trace-ascii' },
      new AbortController().signal,
    );

    expect(requests[0]?.retainedMessages.map(message => message.id)).toEqual([
      'assistant-recent',
    ]);
    expect(requests[0]?.splitTurn).toBe(true);
    expect(compacted.modelContext.firstRetainedMessageId).toBe('assistant-recent');
  });

  it('重复压缩把上一份摘要交给当前模型并累计检查点次数', async () => {
    const requests: ContextCompactionRequest[] = [];
    const manager = await managerFor(gateway(requests, '第二份摘要'));
    const messages = [
      textMessage('user-1', 'user', '第一段'.repeat(180)),
      textMessage('assistant-1', 'assistant', '第一答'.repeat(180)),
      textMessage('user-2', 'user', '第二段'.repeat(180)),
      textMessage('assistant-2', 'assistant', '第二答'.repeat(180)),
      textMessage('user-3', 'user', '最近问题'),
      textMessage('assistant-3', 'assistant', '最近答案'),
    ];
    const original = conversation(messages, 900, {
      checkpoint: Object.freeze({
        id: 'checkpoint-old',
        summary: '第一份摘要',
        trigger: 'automatic',
        compactedAt: '2026-08-26T08:00:00.000Z',
        tokensBefore: 850,
        estimatedTokensAfter: 180,
        compactionCount: 1,
      }),
      firstRetainedMessageId: 'user-1',
    });

    const compacted = await manager.compact(
      original,
      'manual',
      { traceId: 'trace-4' },
      new AbortController().signal,
    );

    expect(requests[0]?.previousSummary).toBe('第一份摘要');
    expect(compacted.modelContext.checkpoint?.summary).toBe('第二份摘要');
    expect(compacted.modelContext.checkpoint?.compactionCount).toBe(2);
  });

  it('摘要 Gateway 失败时保持完整历史与旧 ModelContext 原子不变', async () => {
    const failure = new Error('摘要模型失败');
    const manager = await managerFor({
      configuration: async () => CONFIGURATION,
      compact: async () => { throw failure; },
    });
    const original = conversation([
      textMessage('user-old', 'user', '旧问题'.repeat(180)),
      textMessage('assistant-old', 'assistant', '旧答案'.repeat(180)),
      textMessage('user-new', 'user', '新问题'),
      textMessage('assistant-new', 'assistant', '新答案'),
    ], 900);

    await expect(manager.compact(
      original,
      'manual',
      { traceId: 'trace-5' },
      new AbortController().signal,
    )).rejects.toBe(failure);
    expect(original.modelContext.checkpoint).toBeNull();
    expect(original.messages.map(message => message.id)).toEqual([
      'user-old',
      'assistant-old',
      'user-new',
      'assistant-new',
    ]);
  });

  it('正常模型响应缺少 usage 时明确失败，不能用估算值代替', async () => {
    const manager = await managerFor(gateway([]));
    const response = textMessage('assistant-1', 'assistant', '答案');
    const current = conversation([
      textMessage('user-1', 'user', '问题'),
      response,
    ], 100);

    expect(() => manager.recordModelResponse(current, response, null, null, []))
      .toThrow('模型 Provider 未返回必需的 token usage');
  });

  // ---------- VA-05：最终窗口预算检查 ----------

  it('VA-05：压缩后的输入仍超过窗口预算时明确失败，检查点不被提交', async () => {
    const requests: ContextCompactionRequest[] = [];
    const manager = await managerFor(gateway(requests));
    const messages = Object.freeze([
      textMessage('system-1', 'system', '系统规则'),
      textMessage('user-old', 'user', '旧问题'.repeat(80)),
      textMessage('user-tail', 'user', '过大的最新安全段'.repeat(500)),
    ]);
    const original = conversation(messages, 900);
    const historyBefore = original.messages;
    const modelContextBefore = original.modelContext;

    // 基线 900 已越过阈值 800 触发压缩；保留段（单段 5000+ 字节）超过近期预算 400
    // 也必须整体保留，压缩后估算远超“窗口 1000 − 输出预留 100”。
    await expect(manager.prepareForModelCall({
      conversation: original,
      tools: [],
      callContext: { traceId: 'trace-over', conversationId: 'conversation-1' },
      signal: new AbortController().signal,
    })).rejects.toMatchObject({
      code: 'CONTEXT_WINDOW_EXCEEDED',
      retryable: false,
    });

    // 摘要调用发生过，但失败结果绝不作为可用检查点提交
    expect(requests).toHaveLength(1);
    expect(original.modelContext).toBe(modelContextBefore);
    expect(original.modelContext.checkpoint).toBeNull();
    expect(original.messages).toBe(historyBefore);
  });

  it('VA-05：estimated 基线只覆盖消息投影，最终检查叠加本轮 Tool 定义', async () => {
    const requests: ContextCompactionRequest[] = [];
    const manager = await managerFor(gateway(requests));
    // 压缩后的过渡快照是 estimated：估算只覆盖消息投影，不含工具目录，
    // 最终预算检查必须把本轮 Tool 定义补进窗口预算。
    const compacted = conversation([
      textMessage('user-1', 'user', '问题'),
      textMessage('assistant-1', 'assistant', '答案'),
    ], 700, {
      usage: Object.freeze({
        totalTokens: 700,
        source: 'estimated' as const,
        toolDefinitionTokens: 0,
        measuredThroughMessageId: 'assistant-1',
      }),
    });

    await expect(manager.prepareForModelCall({
      conversation: compacted,
      tools: [{
        name: 'local.huge',
        description: 'x'.repeat(3_000),
        inputSchema: { type: 'object' },
      }],
      callContext: { traceId: 'trace-estimated', conversationId: 'conversation-1' },
      signal: new AbortController().signal,
    })).rejects.toMatchObject({ code: 'CONTEXT_WINDOW_EXCEEDED' });

    // 用量 700 低于阈值 800：压缩从未发生，失败只来自最终预算检查
    expect(requests).toHaveLength(0);
  });

  it('VA-05：保存并恢复 Provider 基线后，相同目录不重复计量，目录增长必须计入预算', async () => {
    const requests: ContextCompactionRequest[] = [];
    const manager = await managerFor(gateway(requests));
    const tools = [{ name: 'local.lookup', description: 'x'.repeat(1_000),
      inputSchema: { type: 'object' } }];
    const response = textMessage('assistant-1', 'assistant', '答案');
    const original = manager.recordModelResponse(
      conversation([textMessage('user-1', 'user', '问题'), response], 650),
      response, { inputTokens: 600, outputTokens: 50, totalTokens: 650 }, null, tools,
    );
    expect(original.modelContext.usage?.toolDefinitionTokens).toBeGreaterThan(1_000);
    const restored: ConversationContext = JSON.parse(JSON.stringify(original));
    const input = { conversation: restored, tools,
      callContext: { traceId: 'trace-provider', conversationId: 'conversation-1' },
      signal: new AbortController().signal };

    // 相同目录已在真实基线 650 中，不能加上目录字节估算导致误拒。
    await expect(manager.prepareForModelCall(input)).resolves.toMatchObject({
      modelMessages: original.messages,
    });
    // 下一轮刷新得到明显增大的目录，Provider 的旧基线不再覆盖新增部分。
    await expect(manager.prepareForModelCall({ ...input,
      tools: [...tools, { name: 'local.extra', description: 'x'.repeat(2_000), inputSchema: {} }],
    })).rejects.toMatchObject({ code: 'CONTEXT_WINDOW_EXCEEDED' });
    await expect(manager.prepareForModelCall({ ...input,
      tools: [{ ...tools[0], description: 'x'.repeat(3_000) }],
    })).rejects.toMatchObject({ code: 'CONTEXT_WINDOW_EXCEEDED' });
    expect(requests).toHaveLength(0);
  });

  it('VA-05：首次调用尚无 usage 时按完整出站输入估算，超大输入明确失败', async () => {
    const requests: ContextCompactionRequest[] = [];
    const manager = await managerFor(gateway(requests));
    const firstInput = conversation([
      textMessage('user-1', 'user', '超大首次输入'.repeat(800)),
    ], 0);
    const withoutUsage = Object.freeze({
      messages: firstInput.messages,
      modelContext: Object.freeze({
        ...EMPTY_MODEL_CONTEXT,
        usage: null,
      }),
    });

    await expect(manager.prepareForModelCall({
      conversation: withoutUsage,
      tools: [],
      callContext: { traceId: 'trace-first', conversationId: null },
      signal: new AbortController().signal,
    })).rejects.toMatchObject({ code: 'CONTEXT_WINDOW_EXCEEDED' });
    expect(requests).toHaveLength(0);
  });

  it('VA-05：手动压缩产生装不下的检查点时明确失败并保留完整历史', async () => {
    const requests: ContextCompactionRequest[] = [];
    const manager = await managerFor(gateway(requests));
    const messages = Object.freeze([
      textMessage('system-1', 'system', '系统规则'),
      textMessage('user-old', 'user', '旧问题'.repeat(80)),
      textMessage('user-tail', 'user', '过大的最新安全段'.repeat(500)),
    ]);
    const original = conversation(messages, 900);
    const modelContextBefore = original.modelContext;

    await expect(manager.compact(
      original,
      'manual',
      { traceId: 'trace-manual', conversationId: 'conversation-1' },
      new AbortController().signal,
    )).rejects.toMatchObject({ code: 'CONTEXT_WINDOW_EXCEEDED' });

    expect(original.modelContext).toBe(modelContextBefore);
    expect(original.modelContext.checkpoint).toBeNull();
  });

  it('VA-05：预算内输入正常返回，错误消息说明窗口与输出预留来源', async () => {
    const requests: ContextCompactionRequest[] = [];
    const manager = await managerFor(gateway(requests));
    const original = conversation([
      textMessage('user-1', 'user', '问题'),
      textMessage('assistant-1', 'assistant', '答案'),
    ], 700);

    const prepared = await manager.prepareForModelCall({
      conversation: original,
      tools: [{
        name: 'local.small',
        description: '小工具',
        inputSchema: { type: 'object' },
      }],
      callContext: { traceId: 'trace-ok', conversationId: 'conversation-1' },
      signal: new AbortController().signal,
    });

    expect(prepared.modelMessages).toHaveLength(2);
    expect(requests).toHaveLength(0);
  });
});
