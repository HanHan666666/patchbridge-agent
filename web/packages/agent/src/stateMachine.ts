/**
 * AgentState 纯函数状态机：集中定义 Browser Agent 的业务状态转换。
 *
 * <p>Controller 只负责协调异步依赖、竞态令牌与副作用，本文件只接收带有
 * 明确业务语义的事件并返回新的状态快照。将状态转换与 I/O 分离后，宿主替换
 * Engine、Client 或 View 时仍能复用同一套状态语义，也可以不启动浏览器环境
 * 就完整验证会话导航、流式运行、人工确认与保存流程。
 */
import type {
  AgentError,
  AgentMessage,
  AgentRunOutcome,
  AgentState,
  Conversation,
  ConversationContext,
  ModelState,
  PendingConfirmation,
} from './types';

/** AgentState 可以接受的领域事件；每种事件只表达一个明确的业务事实。 */
export type AgentStateEvent =
  | {
      /** 开始加载初始化所需的会话列表。 */
      readonly type: 'CONVERSATIONS_LOADING_STARTED';
    }
  | {
      /** 初始化阶段取得服务端会话列表。 */
      readonly type: 'CONVERSATIONS_LOADED';
      /** 当前用户可见的完整会话列表。 */
      readonly conversations: readonly Conversation[];
    }
  | {
      /** 初始化完成，但当前没有可恢复的会话。 */
      readonly type: 'EMPTY_INITIALIZATION_COMPLETED';
    }
  | {
      /** 用户主动刷新会话列表并取得最新结果。 */
      readonly type: 'CONVERSATIONS_REFRESHED';
      /** 当前用户可见的完整会话列表。 */
      readonly conversations: readonly Conversation[];
    }
  | {
      /** 开始加载一个明确指定的会话。 */
      readonly type: 'CONVERSATION_LOADING_STARTED';
    }
  | {
      /** 指定会话及其稳定消息已经加载完成。 */
      readonly type: 'CONVERSATION_LOADED';
      /** 成为当前上下文的会话元数据。 */
      readonly conversation: Conversation;
      /** 该会话原子加载的消息与 Provider 状态。 */
      readonly context: ConversationContext;
    }
  | {
      /** 用户开始一个不属于任何持久化会话的新对话。 */
      readonly type: 'NEW_CONVERSATION_STARTED';
    }
  | {
      /** 服务端已删除指定会话。 */
      readonly type: 'CONVERSATION_DELETED';
      /** 已删除的会话标识。 */
      readonly conversationId: string;
    }
  | {
      /** 用户消息已经进入一轮新的 Agent Run。 */
      readonly type: 'RUN_STARTED';
      /** 包含本轮用户消息在内的稳定消息基线。 */
      readonly messages: readonly AgentMessage[];
    }
  | {
      /** Engine 报告当前 Run 的执行阶段发生变化。 */
      readonly type: 'RUN_STATUS_CHANGED';
      /** Engine 能够直接报告的运行状态。 */
      readonly status: 'streaming' | 'calling-tool';
    }
  | {
      /** 模型产生了一段可展示的回答正文。 */
      readonly type: 'ASSISTANT_CONTENT_RECEIVED';
      /** 本次新增的正文片段。 */
      readonly text: string;
    }
  | {
      /** 推理模型产生了一段可展示的思考内容。 */
      readonly type: 'ASSISTANT_REASONING_RECEIVED';
      /** 本次新增的推理片段。 */
      readonly text: string;
    }
  | {
      /** Engine 提交了本轮最新的稳定消息快照。 */
      readonly type: 'RUN_MESSAGES_COMMITTED';
      /** Run 开始前的历史与本轮用户消息。 */
      readonly baseMessages: readonly AgentMessage[];
      /** Engine 在本轮新增的稳定消息。 */
      readonly addedMessages: readonly AgentMessage[];
      /** 与该消息快照严格对应的 Provider 续接状态。 */
      readonly modelState: ModelState | null;
    }
  | {
      /** 危险工具在执行前请求用户确认。 */
      readonly type: 'TOOL_CONFIRMATION_REQUESTED';
      /** View 展示确认信息所需的纯数据。 */
      readonly confirmation: PendingConfirmation;
    }
  | {
      /** 用户已经处理当前工具确认请求。 */
      readonly type: 'TOOL_CONFIRMATION_RESOLVED';
    }
  | {
      /** 当前 Run 以成功、输出截断或主动取消之一正常收敛。 */
      readonly type: 'RUN_FINISHED';
      /** Engine 返回且由各观察边界共享的唯一终态。 */
      readonly outcome: AgentRunOutcome;
    }
  | {
      /** 一轮 Agent Run 已完成，开始持久化稳定消息。 */
      readonly type: 'CONVERSATION_SAVE_STARTED';
    }
  | {
      /** 会话及本轮消息已经持久化成功。 */
      readonly type: 'CONVERSATION_SAVED';
      /** 带有服务端最新 revision 的会话元数据。 */
      readonly conversation: Conversation;
    }
  | {
      /** 会话导航请求失败。 */
      readonly type: 'NAVIGATION_FAILED';
      /** 已标准化、可直接交给 View 的错误。 */
      readonly error: AgentError;
    }
  | {
      /** 当前 Agent Run 失败。 */
      readonly type: 'RUN_FAILED';
      /** 已标准化、可直接交给 View 的错误。 */
      readonly error: AgentError;
    }
  | {
      /** 保存因 revision 变化而发生乐观锁冲突。 */
      readonly type: 'CONVERSATION_SAVE_CONFLICTED';
      /** 指示用户重新加载会话的标准冲突错误。 */
      readonly error: AgentError;
    }
  | {
      /** 会话保存因非冲突原因失败。 */
      readonly type: 'CONVERSATION_SAVE_FAILED';
      /** 已标准化、可直接交给 View 的错误。 */
      readonly error: AgentError;
    };

/** 创建隔离的初始状态，避免不同 Controller 实例共享数组引用。 */
export function createInitialAgentState(): AgentState {
  return {
    status: 'idle',
    conversation: null,
    conversations: [],
    messages: [],
    modelState: null,
    streamingAssistant: null,
    pendingConfirmation: null,
    error: null,
    runOutcome: null,
  };
}

/**
 * 根据一个领域事件计算下一状态。
 *
 * <p>函数不修改输入状态或事件载荷；所有进入状态的数组都会复制，确保异步
 * Controller 与订阅者不能通过共享数组引用绕过状态机修改唯一状态源。
 */
export function reduceAgentState(
  state: AgentState,
  event: AgentStateEvent,
): AgentState {
  switch (event.type) {
    case 'CONVERSATIONS_LOADING_STARTED':
      return copyState(state, {
        status: 'loading-conversations',
        error: null,
        runOutcome: null,
      });
    case 'CONVERSATIONS_LOADED':
      return copyState(state, {
        conversations: event.conversations,
      });
    case 'EMPTY_INITIALIZATION_COMPLETED':
      return copyState(state, {
        status: 'idle',
      });
    case 'CONVERSATIONS_REFRESHED':
      return copyState(state, {
        conversations: event.conversations,
      });
    case 'CONVERSATION_LOADING_STARTED':
      return copyState(state, {
        status: 'loading-conversation',
        error: null,
        streamingAssistant: null,
        pendingConfirmation: null,
        runOutcome: null,
      });
    case 'CONVERSATION_LOADED':
      return copyState(state, {
        status: 'done',
        conversation: event.conversation,
        messages: event.context.messages,
        modelState: event.context.modelState,
        runOutcome: null,
      });
    case 'NEW_CONVERSATION_STARTED':
      return copyState(state, {
        status: 'idle',
        conversation: null,
        messages: [],
        modelState: null,
        streamingAssistant: null,
        pendingConfirmation: null,
        error: null,
        runOutcome: null,
      });
    case 'CONVERSATION_DELETED': {
      const conversations = state.conversations.filter(
        conversation => conversation.conversationId !== event.conversationId,
      );
      if (state.conversation?.conversationId !== event.conversationId) {
        return copyState(state, { conversations });
      }
      return copyState(state, {
        status: 'idle',
        conversation: null,
        conversations,
        messages: [],
        modelState: null,
        streamingAssistant: null,
        pendingConfirmation: null,
        error: null,
        runOutcome: null,
      });
    }
    case 'RUN_STARTED':
      return copyState(state, {
        status: 'loading-tools',
        messages: event.messages,
        streamingAssistant: null,
        pendingConfirmation: null,
        error: null,
        runOutcome: null,
      });
    case 'RUN_STATUS_CHANGED':
      // waiting-confirmation 是用户必须处理的阻塞状态，普通 Engine 状态不能覆盖它。
      if (state.pendingConfirmation != null) {
        return copyState(state, {});
      }
      return copyState(state, { status: event.status });
    case 'ASSISTANT_CONTENT_RECEIVED':
      return appendStreamingText(state, 'content', event.text);
    case 'ASSISTANT_REASONING_RECEIVED':
      return appendStreamingText(state, 'reasoning', event.text);
    case 'RUN_MESSAGES_COMMITTED':
      return copyState(state, {
        messages: [...event.baseMessages, ...event.addedMessages],
        modelState: event.modelState,
        streamingAssistant: null,
      });
    case 'TOOL_CONFIRMATION_REQUESTED':
      return copyState(state, {
        status: 'waiting-confirmation',
        pendingConfirmation: event.confirmation,
      });
    case 'TOOL_CONFIRMATION_RESOLVED':
      return copyState(state, { pendingConfirmation: null });
    case 'RUN_FINISHED':
      return copyState(state, {
        status: 'done',
        streamingAssistant: null,
        pendingConfirmation: null,
        runOutcome: Object.freeze({ ...event.outcome }),
      });
    case 'CONVERSATION_SAVE_STARTED':
      return copyState(state, {
        status: 'saving',
        streamingAssistant: null,
      });
    case 'CONVERSATION_SAVED':
      return copyState(state, {
        status: 'done',
        conversation: event.conversation,
        conversations: upsertConversation(state.conversations, event.conversation),
      });
    case 'RUN_FAILED':
      return copyState(state, {
        status: 'error',
        streamingAssistant: null,
        pendingConfirmation: null,
        error: event.error,
        runOutcome: null,
      });
    case 'NAVIGATION_FAILED':
    case 'CONVERSATION_SAVE_CONFLICTED':
    case 'CONVERSATION_SAVE_FAILED':
      return copyState(state, {
        status: 'error',
        streamingAssistant: null,
        pendingConfirmation: null,
        error: event.error,
      });
    default:
      return assertNever(event);
  }
}

/** 状态字段覆盖类型；只供 reducer 内部构造完整快照，不是对外领域事件。 */
type AgentStateChanges = Partial<AgentState>;

/**
 * 构造全新的状态对象并隔离数组引用。
 *
 * <p>该辅助函数只消除 reducer 分支里的机械复制；对外输入仍必须经过具名领域
 * 事件，因此不会形成可以任意修改状态的通用 patch 通道。
 */
function copyState(state: AgentState, changes: AgentStateChanges): AgentState {
  const next = { ...state, ...changes };
  return {
    ...next,
    conversations: [...next.conversations],
    messages: [...next.messages],
  };
}

/** 将正文或推理增量追加到独立的流式 assistant 状态。 */
function appendStreamingText(
  state: AgentState,
  field: 'content' | 'reasoning',
  text: string,
): AgentState {
  const current = state.streamingAssistant ?? { content: '', reasoning: '' };
  return copyState(state, {
    streamingAssistant: {
      ...current,
      [field]: current[field] + text,
    },
  });
}

/** 列表内替换或前置插入会话，保持服务端最新会话在最前。 */
function upsertConversation(
  conversations: readonly Conversation[],
  conversation: Conversation,
): Conversation[] {
  const remaining = conversations.filter(
    item => item.conversationId !== conversation.conversationId,
  );
  return [conversation, ...remaining];
}

/** 编译期穷尽检查：新增事件却遗漏 reducer 分支时必须构建失败。 */
function assertNever(event: never): never {
  throw new Error(`未处理的 Agent 状态事件：${JSON.stringify(event)}`);
}
