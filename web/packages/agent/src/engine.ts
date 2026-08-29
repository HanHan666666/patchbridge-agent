/**
 * Browser Agent Engine 与单次 Execution 的公共契约。
 *
 * <p>Controller 只表达用户意图和消费执行事件，不感知默认 Runtime 或宿主提供的
 * 其他 Engine 实现。取消、Human-in-the-loop 响应和结果都绑定单次 Execution，
 * 避免 Engine 内部存在无法观察的“当前 run”全局状态。
 */
import type { ToolRegistrySnapshot } from './toolRegistry';
import type {
  AgentMessage,
  AgentRunOutcome,
  ConversationContext,
  JsonObject,
  JsonValue,
  ModelContext,
  ToolDefinition,
} from './types';

/** 当前浏览器版本支持的 Human-in-the-loop 中断。 */
export interface ToolConfirmationInterrupt {
  /** 中断标识；响应必须精确引用，避免迟到确认作用于后续 Tool。 */
  readonly id: string;
  /** 中断类型；以后新增交互时通过联合类型扩展。 */
  readonly type: 'tool-confirmation';
  /** 需要确认的本轮冻结 Tool 定义。 */
  readonly tool: ToolDefinition;
  /** 模型生成且已完成 JSON 校验的业务参数。 */
  readonly arguments: JsonObject;
}

/** Agent Execution 可发布的中断联合类型。 */
export type AgentInterrupt = ToolConfirmationInterrupt;

/** 宿主对中断的结构化响应；具体 Execution 负责校验 value 语义。 */
export interface AgentInterruptResponse {
  /** 被响应的准确中断标识。 */
  readonly interruptId: string;
  /** 可 JSON 持久化的响应；Tool 确认第一版要求 boolean。 */
  readonly value: JsonValue;
}

/** 单次 Execution 发布给 Controller、Headless UI 和 Debug Hook 的事件。 */
export type AgentExecutionEvent =
  | {
      /** Runtime 阶段变化事件。 */
      readonly type: 'status';
      /** 仅包含 Engine 自己能够确认的运行阶段。 */
      readonly status: 'compacting-context' | 'streaming' | 'calling-tool';
    }
  | {
      /** 可立即展示的 Assistant 正文增量。 */
      readonly type: 'text-delta';
      /** 本次新增文本。 */
      readonly text: string;
    }
  | {
      /** 可立即展示的思考摘要增量。 */
      readonly type: 'reasoning-delta';
      /** 本次新增思考文本。 */
      readonly text: string;
    }
  | {
      /** 模型已经形成稳定 Tool Call 的观察事件。 */
      readonly type: 'tool-call';
      /** 模型生成的 Tool Call ID。 */
      readonly toolCallId: string;
      /** 本轮快照中的完整 Tool 名。 */
      readonly toolName: string;
      /** 已完成 JSON 对象校验的参数。 */
      readonly arguments: JsonObject;
    }
  | {
      /** Tool 已经完成或以业务错误返回的观察事件。 */
      readonly type: 'tool-result';
      /** 对应 Tool Call ID。 */
      readonly toolCallId: string;
      /** 本轮快照中的完整 Tool 名。 */
      readonly toolName: string;
      /** 回填模型的文本结果。 */
      readonly content: string;
      /** 是否为业务错误结果。 */
      readonly isError: boolean;
    }
  | {
      /** 本轮稳定消息和 Provider 状态已经前进的事件。 */
      readonly type: 'messages';
      /** 仅包含本轮新增消息的不可变快照。 */
      readonly messages: readonly AgentMessage[];
      /** 与消息快照严格对应的最新 Provider 状态。 */
      readonly modelContext: ModelContext;
    }
  | {
      /** Execution 等待宿主响应的 Human-in-the-loop 事件。 */
      readonly type: 'interrupt';
      /** 带稳定 ID 的结构化中断。 */
      readonly interrupt: AgentInterrupt;
    };

/** 一次 Agent Run 的输入；所有可变外部能力都在开始时冻结。 */
export interface AgentRunInput {
  /** 包含本轮用户消息和上一份 ModelContext 的完整上下文。 */
  readonly conversation: ConversationContext;
  /** 定义与执行器属于同一 revision 的唯一 Tool 快照。 */
  readonly toolSnapshot: ToolRegistrySnapshot;
  /** 当前会话标识；首次发送尚未创建会话时为 null。 */
  readonly conversationId: string | null;
  /** 串联本轮全部模型与 Tool 调用的链路标识。 */
  readonly traceId: string;
}

/** 单次 Agent Execution 的最终稳定结果。 */
export interface AgentRunResult {
  /** 本轮新增且已经完成的稳定消息。 */
  readonly messages: readonly AgentMessage[];
  /** 最后一次已完成模型调用对应的模型工作上下文。 */
  readonly modelContext: ModelContext;
  /** 成功、输出截断与主动取消互斥的统一终态。 */
  readonly outcome: AgentRunOutcome;
}

/**
 * 一次独立 Agent Execution。
 *
 * <p>respond 与 cancel 只影响该对象。Execution 完成后再响应中断必须明确失败，
 * 防止页面迟到操作污染下一轮执行。
 */
export interface AgentExecution {
  /** 本轮最终结果；模型、协议与 Runtime 错误通过 rejection 传播。 */
  readonly result: Promise<AgentRunResult>;
  /** 响应当前挂起的 Human-in-the-loop 中断。 */
  respond(response: AgentInterruptResponse): void;
  /** 幂等取消本轮执行，并把 result 收敛为 cancelled 终态。 */
  cancel(): void;
}

/** Browser Agent 执行引擎；默认实现为 DefaultAgentRuntime。 */
export interface AgentEngine {
  /**
   * 创建并立即启动一次 Execution。
   * listener 在启动前已经绑定，因此不会丢失同步发布的首个状态事件。
   */
  start(
    input: AgentRunInput,
    listener: (event: AgentExecutionEvent) => void,
  ): AgentExecution;
  /** 释放 Engine，并取消仍在进行的全部 Execution。 */
  dispose(): void;
}
