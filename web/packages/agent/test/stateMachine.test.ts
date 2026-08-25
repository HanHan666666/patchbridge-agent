/**
 * AgentState 纯函数测试：验证 ConversationContext、稳定消息和 ModelState 始终
 * 通过显式领域事件原子转换，不让 Controller 的异步时序渗入状态规则。
 */
import { describe, expect, it } from 'vitest';
import {
  createInitialAgentState,
  reduceAgentState,
} from '../src/stateMachine';
import type {
  AgentError,
  AgentMessage,
  Conversation,
  ModelState,
  ToolDefinition,
} from '../src/types';

/** 创建稳定测试会话。 */
function conversation(id: string, revision = 1): Conversation {
  return {
    conversationId: id,
    title: id,
    revision,
    status: 'ACTIVE',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  };
}

/** 创建单个文本块消息，避免测试把内容重新压成字符串字段。 */
function textMessage(id: string, role: 'user' | 'assistant', text: string): AgentMessage {
  return { id, role, blocks: [{ type: 'text', text }] };
}

/** 创建具有完整字段的标准错误。 */
function agentError(code: string): AgentError {
  return { code, message: code, retryable: false };
}

/** Provider 私有状态只作为不透明值随消息快照一起传递。 */
const MODEL_STATE: ModelState = {
  format: 'provider-state/v1',
  data: { responseMessageId: 'assistant-1' },
};

describe('AgentState 状态机', () => {
  it('加载 ConversationContext 时同时提交消息和 ModelState，并隔离消息数组', () => {
    const selected = conversation('conversation-1', 3);
    const messages = [textMessage('user-1', 'user', '历史消息')];
    let state = reduceAgentState(createInitialAgentState(), {
      type: 'CONVERSATIONS_LOADING_STARTED',
    });
    state = reduceAgentState(state, {
      type: 'CONVERSATIONS_LOADED',
      conversations: [selected],
    });
    state = reduceAgentState(state, {
      type: 'CONVERSATION_LOADING_STARTED',
    });
    state = reduceAgentState(state, {
      type: 'CONVERSATION_LOADED',
      conversation: selected,
      context: { messages, modelState: MODEL_STATE },
    });

    expect(state.status).toBe('done');
    expect(state.conversation).toEqual(selected);
    expect(state.messages).toEqual(messages);
    expect(state.messages).not.toBe(messages);
    expect(state.modelState).toEqual(MODEL_STATE);
    expect(state.runOutcome).toBeNull();

    const reset = reduceAgentState(state, { type: 'NEW_CONVERSATION_STARTED' });
    expect(reset).toMatchObject({
      status: 'idle',
      conversation: null,
      messages: [],
      modelState: null,
      runOutcome: null,
    });
  });

  it('Run 增量、确认中断、稳定消息与 ModelState 形成一条显式状态链', () => {
    const userMessage = textMessage('user-1', 'user', '重启设备');
    const assistantMessage = textMessage('assistant-1', 'assistant', '执行完成');
    let state = reduceAgentState(createInitialAgentState(), {
      type: 'RUN_STARTED',
      messages: [userMessage],
    });
    state = reduceAgentState(state, {
      type: 'RUN_STATUS_CHANGED',
      status: 'streaming',
    });
    state = reduceAgentState(state, {
      type: 'ASSISTANT_REASONING_RECEIVED',
      text: '检查权限',
    });
    state = reduceAgentState(state, {
      type: 'ASSISTANT_CONTENT_RECEIVED',
      text: '准备执行',
    });
    expect(state.streamingAssistant).toEqual({
      content: '准备执行',
      reasoning: '检查权限',
    });

    const tool = { name: 'local.device_restart' } as ToolDefinition;
    state = reduceAgentState(state, {
      type: 'TOOL_CONFIRMATION_REQUESTED',
      confirmation: {
        interruptId: 'interrupt-1',
        tool,
        arguments: { serial: 'DEV-1' },
      },
    });
    expect(state.status).toBe('waiting-confirmation');
    expect(state.pendingConfirmation?.interruptId).toBe('interrupt-1');

    state = reduceAgentState(state, {
      type: 'RUN_STATUS_CHANGED',
      status: 'calling-tool',
    });
    expect(state.status).toBe('waiting-confirmation');
    state = reduceAgentState(state, { type: 'TOOL_CONFIRMATION_RESOLVED' });
    state = reduceAgentState(state, {
      type: 'RUN_MESSAGES_COMMITTED',
      baseMessages: [userMessage],
      addedMessages: [assistantMessage],
      modelState: MODEL_STATE,
    });

    expect(state.messages).toEqual([userMessage, assistantMessage]);
    expect(state.modelState).toEqual(MODEL_STATE);
    expect(state.streamingAssistant).toBeNull();
    expect(state.pendingConfirmation).toBeNull();
  });

  it('保存开始与完成保留 Run Outcome，不拆分消息和 ModelState 快照', () => {
    const userMessage = textMessage('user-1', 'user', '问题');
    let state = reduceAgentState(createInitialAgentState(), {
      type: 'RUN_STARTED',
      messages: [userMessage],
    });
    state = reduceAgentState(state, {
      type: 'RUN_MESSAGES_COMMITTED',
      baseMessages: [userMessage],
      addedMessages: [],
      modelState: MODEL_STATE,
    });
    state = reduceAgentState(state, {
      type: 'RUN_FINISHED',
      outcome: { type: 'max-tokens' },
    });
    state = reduceAgentState(state, { type: 'CONVERSATION_SAVE_STARTED' });
    expect(state.status).toBe('saving');
    expect(state.runOutcome).toEqual({ type: 'max-tokens' });

    const saved = conversation('conversation-new', 2);
    state = reduceAgentState(state, {
      type: 'CONVERSATION_SAVED',
      conversation: saved,
    });
    expect(state.status).toBe('done');
    expect(state.conversation).toEqual(saved);
    expect(state.messages).toEqual([userMessage]);
    expect(state.modelState).toEqual(MODEL_STATE);
    expect(state.runOutcome).toEqual({ type: 'max-tokens' });
  });

  it('取消和各职责域错误都清理临时执行状态并收敛到明确终态', () => {
    let transient = reduceAgentState(createInitialAgentState(), {
      type: 'RUN_STARTED',
      messages: [textMessage('user-1', 'user', '开始')],
    });
    transient = reduceAgentState(transient, {
      type: 'ASSISTANT_CONTENT_RECEIVED',
      text: '尚未提交的内容',
    });
    transient = reduceAgentState(transient, {
      type: 'TOOL_CONFIRMATION_REQUESTED',
      confirmation: {
        interruptId: 'interrupt-failed',
        tool: { name: 'local.device_restart' } as ToolDefinition,
        arguments: { serial: 'DEV-1' },
      },
    });

    const cancelled = reduceAgentState(transient, {
      type: 'RUN_FINISHED',
      outcome: { type: 'cancelled' },
    });
    expect(cancelled.status).toBe('done');
    expect(cancelled.streamingAssistant).toBeNull();
    expect(cancelled.pendingConfirmation).toBeNull();
    expect(cancelled.runOutcome).toEqual({ type: 'cancelled' });

    for (const [type, code] of [
      ['RUN_FAILED', 'RUN_FAILED'],
      ['NAVIGATION_FAILED', 'NAVIGATION_FAILED'],
      ['CONVERSATION_SAVE_CONFLICTED', 'CONVERSATION_CONFLICT'],
      ['CONVERSATION_SAVE_FAILED', 'SAVE_FAILED'],
    ] as const) {
      const failed = reduceAgentState(transient, {
        type,
        error: agentError(code),
      });
      expect(failed.status).toBe('error');
      expect(failed.streamingAssistant).toBeNull();
      expect(failed.pendingConfirmation).toBeNull();
      expect(failed.error?.code).toBe(code);
      if (type === 'RUN_FAILED') {
        expect(failed.runOutcome).toBeNull();
      }
    }
  });

  it('新 Run 与会话导航清空上一轮 max-tokens 提示', () => {
    const finished = reduceAgentState(createInitialAgentState(), {
      type: 'RUN_FINISHED',
      outcome: { type: 'max-tokens' },
    });

    const nextRun = reduceAgentState(finished, {
      type: 'RUN_STARTED',
      messages: [textMessage('user-next', 'user', '继续')],
    });
    expect(nextRun.runOutcome).toBeNull();

    const loading = reduceAgentState(finished, {
      type: 'CONVERSATION_LOADING_STARTED',
    });
    expect(loading.runOutcome).toBeNull();
  });

  it('删除当前会话清空完整上下文，删除其他会话只更新列表', () => {
    const first = conversation('conversation-a');
    const second = conversation('conversation-b');
    const history = [textMessage('user-a', 'user', 'A')];
    let state = reduceAgentState(createInitialAgentState(), {
      type: 'CONVERSATIONS_REFRESHED',
      conversations: [first, second],
    });
    state = reduceAgentState(state, {
      type: 'CONVERSATION_LOADED',
      conversation: first,
      context: { messages: history, modelState: MODEL_STATE },
    });

    const otherDeleted = reduceAgentState(state, {
      type: 'CONVERSATION_DELETED',
      conversationId: 'conversation-b',
    });
    expect(otherDeleted.conversation).toEqual(first);
    expect(otherDeleted.messages).toEqual(history);
    expect(otherDeleted.modelState).toEqual(MODEL_STATE);

    const currentDeleted = reduceAgentState(otherDeleted, {
      type: 'CONVERSATION_DELETED',
      conversationId: 'conversation-a',
    });
    expect(currentDeleted).toMatchObject({
      status: 'idle',
      conversation: null,
      messages: [],
      modelState: null,
    });
  });
});
