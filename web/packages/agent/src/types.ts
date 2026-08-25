/**
 * 前端领域契约：AgentState、厂商中立消息、Tool、会话与错误的唯一权威定义。
 *
 * <p>本文件是分层架构的锚点：View 只允许依赖这里的纯数据类型，
 * 不允许触及 Engine、Client 或任何基础设施对象（设计约束：AgentState 是唯一状态源）。
 * 消息使用 Message + ContentBlock，避免把 OpenAI、Anthropic 或 Responses API
 * 的传输字段泄漏到 Controller 与 View。Model Provider 是厂商协议的唯一转换边界。
 */

/** JSON 标量；Model State 和 Tool 参数只能使用可跨进程持久化的数据。 */
export type JsonPrimitive = string | number | boolean | null;

/** 递归 JSON 值；禁止函数、DOM、Promise 等运行时对象进入稳定领域状态。 */
export type JsonValue = JsonPrimitive | JsonObject | readonly JsonValue[];

/** 带只读索引签名的 JSON 对象，适用于 Tool 参数与 Provider 私有状态。 */
export interface JsonObject {
  readonly [key: string]: JsonValue;
}

/** Agent 生命周期状态：所有状态转换只能由 AgentController 执行。 */
export type AgentStatus =
  | 'idle'
  | 'loading-conversations'
  | 'loading-conversation'
  | 'loading-tools'
  | 'streaming'
  | 'waiting-confirmation'
  | 'calling-tool'
  | 'saving'
  | 'done'
  | 'error';

/**
 * 标准化浏览器错误：View 只根据 code / message 决定展示方式，
 * 不解析 HTTP 状态码或模型厂商细节（错误标准化集中在 errors.ts / 各 Client）。
 */
export interface AgentError {
  /** 与后端 AgentErrorCode 对齐的稳定错误码。 */
  readonly code: string;
  /** 面向用户的中文提示。 */
  readonly message: string;
  /** 是否值得用户重试（网络抖动可重试，权限拒绝重试无意义）。 */
  readonly retryable: boolean;
}

/**
 * 一次 Agent Run 非异常收敛后的明确终态。
 *
 * <p>该联合类型刻意不包含失败：模型协议、资源限额和编程错误继续通过
 * Execution.result rejection 传播。把自然结束、完整消息被长度上限截断和
 * 主动取消分开后，Controller、Hook、Call Trace 与 View 可以共享同一套语义，
 * 不再从 boolean 或模型厂商字段推测最终状态。
 */
export type AgentRunOutcome =
  | {
      /** 模型自然结束，回答可以作为完整回合保存。 */
      readonly type: 'completed';
      /** 只有不要求继续 Tool Loop 的自然停止原因才能进入完成态。 */
      readonly stopReason: 'end-turn' | 'stop-sequence' | 'other';
    }
  | {
      /** 模型已封闭稳定消息，但回答因输出长度上限可能不完整。 */
      readonly type: 'max-tokens';
    }
  | {
      /** 用户或宿主主动取消；取消不是失败，也不会触发本轮会话保存。 */
      readonly type: 'cancelled';
    };

/** 会话元数据：后端是持久化 Source of Truth，revision 用于多 Tab 乐观锁。 */
export interface Conversation {
  /** 会话全局标识。 */
  readonly conversationId: string;
  /** 用户可见标题；未命名时为 null。 */
  readonly title: string | null;
  /** 多窗口全量保存使用的乐观锁版本。 */
  readonly revision: number;
  /** 服务端定义的会话生命周期状态。 */
  readonly status: string;
  /** ISO-8601 创建时间。 */
  readonly createdAt: string;
  /** ISO-8601 最近更新时间。 */
  readonly updatedAt: string;
}

/** 消息角色是 Agent 语义，不对应任一厂商的 wire role。 */
export type MessageRole = 'system' | 'user' | 'assistant' | 'tool';

/**
 * 厂商中立的稳定消息。
 *
 * <p>id 供框架渲染、持久化和追踪使用，Provider 编码厂商请求时必须剥离；
 * blocks 保留内容顺序，使正文、图片、思考和 Tool 交互不再依赖可选字段组合。
 */
export interface AgentMessage {
  /** 跨保存与恢复保持不变的框架消息标识。 */
  readonly id: string;
  /** 产生消息的语义角色；Provider 负责映射目标厂商的角色规则。 */
  readonly role: MessageRole;
  /** 按产生顺序排列的稳定语义内容块。 */
  readonly blocks: readonly ContentBlock[];
}

/** 第一版稳定内容块，只覆盖框架当前已经实现的能力。 */
export type ContentBlock =
  | TextBlock
  | ImageBlock
  | ReasoningBlock
  | ToolCallBlock
  | ToolResultBlock;

/** 用户或 Assistant 可展示的正文块。 */
export interface TextBlock {
  /** 判别字段确保渲染与 Provider 编码使用穷尽分支。 */
  readonly type: 'text';
  /** 原始文本；Markdown 是否渲染由宿主 View 决定。 */
  readonly text: string;
}

/** 图片来源；显式区分远程 URL 与内联数据，避免 Provider 猜测字符串格式。 */
export type ImageSource = UrlImageSource | Base64ImageSource;

/** 由模型服务端主动获取的远程图片。 */
export interface UrlImageSource {
  /** URL 来源判别字段。 */
  readonly type: 'url';
  /** 宿主提供的完整 HTTP(S) URL。 */
  readonly url: string;
}

/** 浏览器内联上传的 Base64 图片。 */
export interface Base64ImageSource {
  /** Base64 来源判别字段。 */
  readonly type: 'base64';
  /** 图片媒体类型，例如 image/png；Provider 用它生成厂商请求。 */
  readonly mediaType: string;
  /** 不含 data URL 前缀的 Base64 数据。 */
  readonly data: string;
}

/** 多模态图片块；不携带 OpenAI image_url 等厂商字段。 */
export interface ImageBlock {
  /** 图片块判别字段。 */
  readonly type: 'image';
  /** 图片真实来源。 */
  readonly source: ImageSource;
}

/**
 * 允许展示的思考内容。
 *
 * <p>它不是模型协议状态。签名、encrypted_content、continuation token 等必须
 * 存入独立 ModelState，禁止混入该字段或由 View 读取。
 */
export interface ReasoningBlock {
  /** 思考展示块判别字段。 */
  readonly type: 'reasoning';
  /** Provider 明确允许展示的思考文本或摘要。 */
  readonly text: string;
}

/** Assistant 发起的稳定 Tool 调用；input 已完成 JSON 聚合与对象校验。 */
export interface ToolCallBlock {
  /** Tool 调用块判别字段。 */
  readonly type: 'tool-call';
  /** 模型生成的调用标识，用于严格关联 Tool 结果。 */
  readonly callId: string;
  /** 与本轮 ToolRegistrySnapshot 定义一致的完整名称。 */
  readonly name: string;
  /** 已解析的结构化参数；流式 JSON 片段不得进入稳定消息。 */
  readonly input: JsonObject;
}

/** 第一版 Tool 内容只支持文本，与当前 Unified Tool Gateway 能力一致。 */
export interface ToolResultTextContent {
  /** Tool 结果内容类型。 */
  readonly type: 'text';
  /** 回填给模型并可供 UI 展示的结果文本。 */
  readonly text: string;
}

/** Tool 结果内容联合类型；以后按真实网关能力增加图片或结构化块。 */
export type ToolResultContent = ToolResultTextContent;

/** Tool 执行结果块；它是消息内容而不是模型厂商定义的 role 字段。 */
export interface ToolResultBlock {
  /** Tool 结果块判别字段。 */
  readonly type: 'tool-result';
  /** 对应 ToolCallBlock.callId。 */
  readonly callId: string;
  /** 便于审计和 View 展示的完整 Tool 名。 */
  readonly name: string;
  /** 业务执行状态；框架级异常仍通过异常通道传播。 */
  readonly status: 'success' | 'error';
  /** 保留 Tool 内容块边界，禁止在消息层拼成厂商专有结构。 */
  readonly content: readonly ToolResultContent[];
}

/**
 * Provider 私有且可持久化的模型续接状态。
 *
 * <p>Runtime 只能原样保存与传递；format 不匹配时 Provider 必须明确失败，
 * 禁止静默剥离后继续调用，否则推理模型可能得到不完整的 Tool 历史。
 */
export interface ModelState {
  /** Provider 校验的状态格式与版本，例如 openai-chat-reasoning/v1。 */
  readonly format: string;
  /** 厂商所需的结构化状态；字段含义只属于对应 Provider。 */
  readonly data: JsonValue;
}

/** 一次可恢复会话的完整 Agent 上下文。 */
export interface ConversationContext {
  /** 可展示、可持久化的稳定消息。 */
  readonly messages: readonly AgentMessage[];
  /** 模型续接状态；普通无状态模型为 null。 */
  readonly modelState: ModelState | null;
}

/**
 * 待发送的图片附件：View 在消息提交前完成读取，Controller 只接收稳定图片来源。
 * File 等浏览器对象不得进入 AgentState，保证会话可以 JSON 持久化。
 */
export interface ImageAttachment {
  /** 已读取完成的图片来源。 */
  readonly source: ImageSource;
}

/** 工具能力注记：与后端 ToolAnnotations 字段一一对应。 */
export interface ToolAnnotations {
  /** Tool 是否保证不修改业务状态。 */
  readonly readOnlyHint: boolean;
  /** Tool 是否可能产生破坏性后果。 */
  readonly destructiveHint: boolean;
  /** 相同参数重复执行是否具有同一业务效果。 */
  readonly idempotentHint: boolean;
  /** 为 true 时 Runtime 必须先取得用户确认才会真正调用。 */
  readonly requireConfirmation: boolean;
  /** WebMCP 标记工具结果包含不可信内容时使用；后端 Tool 可省略。 */
  readonly untrustedContentHint?: boolean;
}

/** 浏览器统一 Tool Registry 使用的来源标识。 */
export type ToolSource =
  | 'LOCAL'
  | 'OPENAPI'
  | 'MCP'
  | 'FRONTEND_LOCAL'
  | 'WEBMCP';

/**
 * Agent 可发现的统一工具定义。
 *
 * <p>LOCAL / OPENAPI / MCP 来自后端 Gateway；FRONTEND_LOCAL 与 WEBMCP
 * 只存在于浏览器。source 只用于可观察性和冲突诊断，Engine 不按来源分支。
 */
export interface ToolDefinition {
  /** 带命名空间的全名，如 local.device_get / mcp.inventory.xxx。 */
  readonly name: string;
  /** 面向用户的标题；为空时 View 可以使用 name。 */
  readonly title: string | null;
  /** 帮助模型判断调用时机的能力说明。 */
  readonly description: string;
  /** 模型必须遵循的 JSON Schema 参数约束。 */
  readonly inputSchema: JsonObject;
  /** 风险与确认语义；来源未提供时为 null。 */
  readonly annotations: ToolAnnotations | null;
  /** 仅用于可观察性和冲突诊断的来源。 */
  readonly source: ToolSource;
  /** 宿主定义的完整权限元数据；ALL / ANY 组合语义由服务端策略决定。 */
  readonly permissions: readonly string[];
}

/** 工具调用结果（POST /ai/tools/call 的响应结构）。 */
export interface ToolCallResult {
  /** 对应模型调用标识；Runtime 要求它与当前请求严格一致。 */
  readonly toolCallId: string;
  /** 回填给模型的文本结果。 */
  readonly content: string;
  /** Tool 是否以业务错误完成。 */
  readonly isError: boolean;
}

/** 正在生成的 assistant 消息：与已提交的稳定消息分离，避免 DOM 记录中间态。 */
export interface StreamingAssistant {
  /** 尚未形成稳定 TextBlock 的正文增量。 */
  readonly content: string;
  /** 尚未形成稳定 ReasoningBlock 的展示思考增量。 */
  readonly reasoning: string;
}

/** Human-in-the-loop 待确认项：属于状态而不属于 DOM（设计文档第 12 节）。 */
export interface PendingConfirmation {
  /** 当前 AgentExecution 中断标识；批准或拒绝必须精确引用。 */
  readonly interruptId: string;
  /** 需要确认的本轮 Tool 定义。 */
  readonly tool: ToolDefinition;
  /** 已完成 JSON 校验的调用参数。 */
  readonly arguments: JsonObject;
}

/** Browser 唯一状态源：纯数据、可快照、可测试，禁止放入 DOM / Promise / Client 实例。 */
export interface AgentState {
  /** 当前 Controller 业务阶段。 */
  readonly status: AgentStatus;
  /** 当前选中会话；首次发送前可以为空。 */
  readonly conversation: Conversation | null;
  /** 当前用户可见的会话元数据列表。 */
  readonly conversations: readonly Conversation[];
  /** 当前会话已经稳定提交的完整消息。 */
  readonly messages: readonly AgentMessage[];
  /** 与展示消息分离的 Provider 续接状态；View 不应读取其 data。 */
  readonly modelState: ModelState | null;
  /** 当前尚未完成的 Assistant 展示增量。 */
  readonly streamingAssistant: StreamingAssistant | null;
  /** 正在等待宿主响应的 Tool 确认。 */
  readonly pendingConfirmation: PendingConfirmation | null;
  /** 最近一次导航、执行或保存错误。 */
  readonly error: AgentError | null;
  /**
   * 当前视图最近一次已收敛 Run 的终态；新 Run 或会话导航开始时清空。
   * 它只服务即时交互提示，不属于模型上下文，也不写入 ConversationContext。
   */
  readonly runOutcome: AgentRunOutcome | null;
}
