/**
 * @patchbridge-agent/agent 包出口：浏览器 Agent Runtime 的全部公共契约与默认实现。
 *
 * <p>分层依赖（View → Controller → Engine → Clients）中的每一层都可以被
 * 宿主单独替换：View 引用本包的 Controller 与类型；高级用法可替换 Engine
 * 或 Client；Widget 包（@patchbridge-agent/widget）是本包之上的默认 View。
 */
export * from './types';
export type {
  AgentEngine,
  AgentExecution,
  AgentExecutionEvent,
  AgentInterrupt,
  AgentInterruptResponse,
  AgentRunInput,
  AgentRunResult,
} from './engine';
export {
  DEFAULT_AGENT_EXECUTION_LIMITS,
  DefaultAgentRuntime,
} from './runtime';
export type {
  AgentExecutionLimits,
  DefaultAgentRuntimeOptions,
  RuntimeIdKind,
} from './runtime';
export { DefaultAgentController } from './controller';
export type { AgentControllerOptions, PatchBridgeAgentController } from './controller';
export { createAgentController } from './factory';
export type { CreateAgentControllerOptions } from './factory';
export type {
  Model,
  ModelBlockDelta,
  ModelBlockStart,
  ModelCallContext,
  ModelRequest,
  ModelStopReason,
  ModelStreamEvent,
  ModelToolDefinition,
  ModelUsage,
} from './clients/modelClient';
export { HttpModel } from './clients/modelClient';
export type {
  ContextCompactionGateway,
  ContextCompactionRequest,
  ContextCompactionResult,
} from './clients/contextCompactionClient';
export { HttpContextCompactionGateway } from './clients/contextCompactionClient';
export type {
  ContextManager,
  ContextManagerOptions,
  PrepareModelContextInput,
  PreparedModelContext,
} from './contextManager';
export {
  DefaultContextManager,
  EMPTY_MODEL_CONTEXT,
  inspectContextWindow,
} from './contextManager';
export type { ToolClient, ToolCallContext } from './clients/toolClient';
export { HttpToolClient } from './clients/toolClient';
export type {
  ConversationClient,
  ConversationDetail,
  ConversationSaveBody,
} from './clients/conversationClient';
export { HttpConversationClient } from './clients/conversationClient';
export type { HttpTransport } from './clients/http';
export { FetchHttpTransport, defaultHttpTransport } from './clients/http';
export { SseParser } from './clients/sse';
export type {
  AgentHook,
  AgentHookContext,
  AgentHookFailure,
  AgentLifecycleEvent,
  AgentTerminalLifecycleEvent,
  BrowserToolInvocation,
  ModelCallNext,
  ModelInterceptor,
  ToolCallNext,
  ToolInterceptor,
} from './extensions';
export type {
  BrowserTool,
  BrowserToolAnnotations,
  BrowserToolExecutionContext,
  BrowserToolProvider,
  ExecutableTool,
  ToolProviderRegistration,
  ToolRegistration,
  ToolRegistry,
  ToolRegistrySnapshot,
} from './toolRegistry';
export {
  BackendToolProvider,
  DefaultToolRegistry,
  ToolAlreadyRegisteredError,
} from './toolRegistry';
export type {
  ToolInspectionScope,
  ToolInspectionSnapshot,
  ToolInspectionSource,
} from './toolInspection';
export {
  CALL_TRACE_MAX_TRACES_PER_CONVERSATION,
  CallTraceStore,
  disabledCallTraceSource,
} from './callTrace';
export type {
  CallTraceRecord,
  CallTraceSnapshot,
  CallTraceSource,
  CallTraceStorage,
  CallTraceStoreOptions,
  ConfirmationTraceRecord,
  ExecutionTrace,
  ModelCallTraceRecord,
  ToolCallTraceRecord,
  UserInputTraceRecord,
} from './callTrace';
