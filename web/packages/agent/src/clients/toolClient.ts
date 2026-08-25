/**
 * ToolClient：浏览器访问 Unified Tool Gateway 的唯一通道。
 *
 * <p>设计原因：Controller 与 View 都不关心工具来源（本地 @AiTool / MCP），
 * 所有差异已由后端收敛为 GET /ai/tools 与 POST /ai/tools/call 两个端点。
 * 链路字段（traceId / toolCallId / conversationId）在此注入，
 * 供服务端审计把“模型 → 工具 → 模型”串成完整 Trace。
 */
import type { JsonObject, ToolCallResult, ToolDefinition } from '../types';
import {
  defaultHttpTransport,
  jsonInit,
  normalizeEndpoint,
  requireArrayField,
  requestJson,
  type HttpTransport,
} from './http';

/** 工具调用时随请求携带的链路追踪上下文。 */
export interface ToolCallContext {
  traceId: string;
  conversationId: string | null;
  toolCallId: string;
}

/** Tool Client 契约：Runtime 依赖此接口，测试可注入假实现。 */
export interface ToolClient {
  /** 当前用户可发现的工具列表（服务端已按权限过滤）。 */
  list(signal?: AbortSignal): Promise<ToolDefinition[]>;
  /**
   * 调用一个工具。HTTP 层错误（不存在 / 无权限 / 执行异常）以 AgentError 抛出；
   * 工具本身的业务失败通过返回值 isError=true 表达，交由模型自行向用户解释。
   */
  call(
    name: string,
    arguments_: JsonObject,
    context: ToolCallContext,
    signal?: AbortSignal,
  ): Promise<ToolCallResult>;
}

export class HttpToolClient implements ToolClient {
  private readonly endpoint: string;
  /** 工具发现与调用共享的宿主可注入传输层。 */
  private readonly transport: HttpTransport;

  /** 创建工具客户端；未注入传输时保持同源 fetch 行为。 */
  constructor(endpoint: string, transport: HttpTransport = defaultHttpTransport) {
    this.endpoint = normalizeEndpoint(endpoint);
    this.transport = transport;
  }

  async list(signal?: AbortSignal): Promise<ToolDefinition[]> {
    const body = await requestJson<{ tools: ToolDefinition[] }>(
      `${this.endpoint}/tools`,
      { method: 'GET', signal },
      this.transport,
    );
    return requireArrayField<ToolDefinition>(body.tools, 'tools');
  }

  async call(
    name: string,
    arguments_: JsonObject,
    context: ToolCallContext,
    signal?: AbortSignal,
  ): Promise<ToolCallResult> {
    const body = await requestJson<ToolCallResult>(
      `${this.endpoint}/tools/call`,
      jsonInit('POST', { name, arguments: arguments_, ...context }, signal),
      this.transport,
    );
    return body;
  }
}
