/**
 * ConversationClient：浏览器访问会话持久化 API 的唯一通道。
 *
 * <p>设计原因：后端是跨刷新 / 跨设备恢复的 Source of Truth（设计文档第 13 节），
 * 浏览器不维护本地副本真相；保存语义为“整回合全量替换 + revision 乐观锁”，
 * 多 Tab 冲突以 409 显式暴露而非静默覆盖。
 */
import { invalidStateError } from '../errors';
import type { Conversation, ConversationContext } from '../types';
import {
  defaultHttpTransport,
  jsonInit,
  normalizeEndpoint,
  requireArrayField,
  requestJson,
  type HttpTransport,
} from './http';

/** GET /ai/conversations/{id} 的响应结构。 */
export interface ConversationDetail {
  conversation: Conversation;
  /** 与 conversation.revision 属于同一原子快照的完整上下文。 */
  context: ConversationContext;
}

/** PUT /ai/conversations/{id} 的请求体：完整上下文 + 客户端持有的 revision。 */
export interface ConversationSaveBody {
  title: string | null;
  revision: number;
  /** messages 与 modelState 必须由服务端在同一 revision 内原子保存。 */
  context: ConversationContext;
}

/** Conversation Client 契约：Controller 依赖此接口，测试可注入假实现。 */
export interface ConversationClient {
  list(signal?: AbortSignal): Promise<Conversation[]>;
  create(title: string | null, signal?: AbortSignal): Promise<Conversation>;
  get(id: string, signal?: AbortSignal): Promise<ConversationDetail>;
  save(id: string, body: ConversationSaveBody, signal?: AbortSignal): Promise<Conversation>;
  delete(id: string, signal?: AbortSignal): Promise<void>;
}

export class HttpConversationClient implements ConversationClient {
  private readonly endpoint: string;
  /** 会话查询与写入共享的宿主可注入传输层。 */
  private readonly transport: HttpTransport;

  /** 创建会话客户端；未注入传输时保持同源 fetch 行为。 */
  constructor(endpoint: string, transport: HttpTransport = defaultHttpTransport) {
    this.endpoint = normalizeEndpoint(endpoint);
    this.transport = transport;
  }

  async list(signal?: AbortSignal): Promise<Conversation[]> {
    const body = await requestJson<{ conversations: unknown }>(
      `${this.endpoint}/conversations`,
      { method: 'GET', signal },
      this.transport,
    );
    return requireArrayField<unknown>(body.conversations, 'conversations')
      .map(item => requireConversation(item, 'conversations[]'));
  }

  async create(title: string | null, signal?: AbortSignal): Promise<Conversation> {
    const body = await requestJson<{ conversation: unknown }>(
      `${this.endpoint}/conversations`,
      jsonInit('POST', { title }, signal),
      this.transport,
    );
    return requireConversation(body.conversation, 'conversation');
  }

  async get(id: string, signal?: AbortSignal): Promise<ConversationDetail> {
    const body = await requestJson<unknown>(
      `${this.endpoint}/conversations/${encodeURIComponent(id)}`,
      { method: 'GET', signal },
      this.transport,
    );
    return requireConversationDetail(body);
  }

  async save(
    id: string,
    body: ConversationSaveBody,
    signal?: AbortSignal,
  ): Promise<Conversation> {
    const saved = await requestJson<{ conversation: unknown }>(
      `${this.endpoint}/conversations/${encodeURIComponent(id)}`,
      jsonInit('PUT', body, signal),
      this.transport,
    );
    return requireConversation(saved.conversation, 'conversation');
  }

  async delete(id: string, signal?: AbortSignal): Promise<void> {
    await requestJson<void>(
      `${this.endpoint}/conversations/${encodeURIComponent(id)}`,
      { method: 'DELETE', signal },
      this.transport,
    );
  }
}

/**
 * 校验单个 Conversation 响应对象（二次审计 Q-07：HTTP 响应属于不可信输入）。
 *
 * <p>字段级失败显式抛出 invalidStateError，不静默容忍错误形状——否则畸形数据
 * 会流入 Controller 状态与 View 渲染，把协议问题变成更难定位的界面错误。
 */
function requireConversation(value: unknown, field: string): Conversation {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw invalidStateError(`服务端响应字段 ${field} 必须是会话对象`);
  }
  const conversation = value as Record<string, unknown>;
  if (typeof conversation.conversationId !== 'string' || conversation.conversationId.length === 0) {
    throw invalidStateError(`服务端响应字段 ${field}.conversationId 必须是非空字符串`);
  }
  if (conversation.title !== null && typeof conversation.title !== 'string') {
    throw invalidStateError(`服务端响应字段 ${field}.title 必须是字符串或 null`);
  }
  if (typeof conversation.revision !== 'number' || !Number.isFinite(conversation.revision)) {
    throw invalidStateError(`服务端响应字段 ${field}.revision 必须是有限数字`);
  }
  if (typeof conversation.status !== 'string') {
    throw invalidStateError(`服务端响应字段 ${field}.status 必须是字符串`);
  }
  if (typeof conversation.createdAt !== 'string' || typeof conversation.updatedAt !== 'string') {
    throw invalidStateError(`服务端响应字段 ${field}.createdAt/updatedAt 必须是字符串`);
  }
  return value as Conversation;
}

/** 校验 GET /ai/conversations/{id} 的完整响应：会话元数据 + 恢复上下文。 */
function requireConversationDetail(value: unknown): ConversationDetail {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw invalidStateError('服务端响应必须是会话详情对象');
  }
  const detail = value as Record<string, unknown>;
  const conversation = requireConversation(detail.conversation, 'conversation');
  const context = detail.context;
  if (typeof context !== 'object' || context === null || Array.isArray(context)) {
    throw invalidStateError('服务端响应字段 context 必须是对象');
  }
  const contextRecord = context as Record<string, unknown>;
  if (!Array.isArray(contextRecord.messages)) {
    throw invalidStateError('服务端响应字段 context.messages 必须是数组');
  }
  if (contextRecord.modelState !== null
    && (typeof contextRecord.modelState !== 'object' || Array.isArray(contextRecord.modelState))) {
    throw invalidStateError('服务端响应字段 context.modelState 必须是对象或 null');
  }
  return value as ConversationDetail;
}
