/**
 * ConversationClient：浏览器访问会话持久化 API 的唯一通道。
 *
 * <p>设计原因：后端是跨刷新 / 跨设备恢复的 Source of Truth（设计文档第 13 节），
 * 浏览器不维护本地副本真相；保存语义为“整回合全量替换 + revision 乐观锁”，
 * 多 Tab 冲突以 409 显式暴露而非静默覆盖。
 */
import { invalidStateError } from '../errors';
import type { Conversation, ConversationContext } from '../types';
import { snapshotConversationContext } from '../messageValues';
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
  /** 完整 messages 与 modelContext 必须由服务端在同一 revision 内原子保存。 */
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
  const modelContext = requireModelContext(contextRecord.modelContext);
  return Object.freeze({
    conversation,
    context: snapshotConversationContext({
      messages: contextRecord.messages as ConversationContext['messages'],
      modelContext,
    }),
  });
}

/** 严格校验持久化模型工作上下文，禁止旧 modelState 顶层结构静默恢复。 */
function requireModelContext(value: unknown): ConversationContext['modelContext'] {
  const context = requireRecord(value, 'context.modelContext');
  requireExactKeys(
    context,
    ['checkpoint', 'firstRetainedMessageId', 'modelState', 'usage'],
    'context.modelContext',
  );
  const checkpoint = context.checkpoint == null
    ? null
    : requireCheckpoint(context.checkpoint);
  if (context.firstRetainedMessageId !== null
    && (typeof context.firstRetainedMessageId !== 'string'
      || context.firstRetainedMessageId.trim().length === 0)) {
    throw invalidStateError(
      '服务端响应字段 context.modelContext.firstRetainedMessageId 必须是非空字符串或 null',
    );
  }
  if ((checkpoint == null) !== (context.firstRetainedMessageId == null)) {
    throw invalidStateError('context.modelContext 检查点与保留边界必须同时存在');
  }
  if (context.modelState !== null) {
    const modelState = requireRecord(context.modelState, 'context.modelContext.modelState');
    requireExactKeys(modelState, ['format', 'data'], 'context.modelContext.modelState');
    if (typeof modelState.format !== 'string' || modelState.format.trim().length === 0) {
      throw invalidStateError('context.modelContext.modelState.format 必须是非空字符串');
    }
  }
  const usage = context.usage == null ? null : requireUsage(context.usage);
  return Object.freeze({
    checkpoint,
    firstRetainedMessageId: context.firstRetainedMessageId as string | null,
    modelState: context.modelState as ConversationContext['modelContext']['modelState'],
    usage,
  });
}

/** 校验最近一次成功压缩检查点。 */
function requireCheckpoint(
  value: unknown,
): NonNullable<ConversationContext['modelContext']['checkpoint']> {
  const checkpoint = requireRecord(value, 'context.modelContext.checkpoint');
  requireExactKeys(checkpoint, [
    'id',
    'summary',
    'trigger',
    'compactedAt',
    'tokensBefore',
    'estimatedTokensAfter',
    'compactionCount',
  ], 'context.modelContext.checkpoint');
  for (const field of ['id', 'summary', 'compactedAt'] as const) {
    if (typeof checkpoint[field] !== 'string' || checkpoint[field].trim().length === 0) {
      throw invalidStateError(`context.modelContext.checkpoint.${field} 必须是非空字符串`);
    }
  }
  if (checkpoint.trigger !== 'automatic' && checkpoint.trigger !== 'manual') {
    throw invalidStateError('context.modelContext.checkpoint.trigger 格式非法');
  }
  const tokensBefore = requireNonNegativeInteger(checkpoint.tokensBefore, 'tokensBefore');
  const estimatedTokensAfter = requireNonNegativeInteger(
    checkpoint.estimatedTokensAfter,
    'estimatedTokensAfter',
  );
  const compactionCount = requirePositiveInteger(
    checkpoint.compactionCount,
    'compactionCount',
  );
  return Object.freeze({
    id: checkpoint.id as string,
    summary: checkpoint.summary as string,
    trigger: checkpoint.trigger,
    compactedAt: checkpoint.compactedAt as string,
    tokensBefore,
    estimatedTokensAfter,
    compactionCount,
  });
}

/** 校验模型上下文最近一次 token 计量。 */
function requireUsage(
  value: unknown,
): NonNullable<ConversationContext['modelContext']['usage']> {
  const usage = requireRecord(value, 'context.modelContext.usage');
  requireExactKeys(
    usage,
    ['totalTokens', 'source', 'measuredThroughMessageId'],
    'context.modelContext.usage',
  );
  if (usage.source !== 'provider' && usage.source !== 'estimated') {
    throw invalidStateError('context.modelContext.usage.source 格式非法');
  }
  if (usage.measuredThroughMessageId !== null
    && (typeof usage.measuredThroughMessageId !== 'string'
      || usage.measuredThroughMessageId.trim().length === 0)) {
    throw invalidStateError('usage.measuredThroughMessageId 必须是非空字符串或 null');
  }
  return Object.freeze({
    totalTokens: requireNonNegativeInteger(usage.totalTokens, 'usage.totalTokens'),
    source: usage.source,
    measuredThroughMessageId: usage.measuredThroughMessageId as string | null,
  });
}

/** 要求不可信响应值为普通对象。 */
function requireRecord(value: unknown, field: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw invalidStateError(`服务端响应字段 ${field} 必须是对象`);
  }
  return value as Record<string, unknown>;
}

/** 要求对象字段与当前版本协议完全一致。 */
function requireExactKeys(
  value: Record<string, unknown>,
  expected: readonly string[],
  field: string,
): void {
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  if (actual.length !== wanted.length || actual.some((key, index) => key !== wanted[index])) {
    throw invalidStateError(`服务端响应字段 ${field} 结构不匹配`);
  }
}

/** 要求 token 与计数是安全非负整数。 */
function requireNonNegativeInteger(value: unknown, field: string): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) {
    throw invalidStateError(`${field} 必须是安全非负整数`);
  }
  return value;
}

/** 要求累计次数是安全正整数。 */
function requirePositiveInteger(value: unknown, field: string): number {
  const parsed = requireNonNegativeInteger(value, field);
  if (parsed === 0) {
    throw invalidStateError(`${field} 必须大于 0`);
  }
  return parsed;
}
