/**
 * 模型目标目录与显式切换的浏览器端口。
 *
 * <p>目录只暴露服务端授权后的公开能力；切换先由服务端验证完整历史、工具目录和
 * 目标窗口，Controller 只有收到完整结果才提交唯一状态源。
 */
import { invalidStateError } from '../errors';
import type { Conversation, ConversationContext, ModelTarget, ModelTargetRef } from '../types';
import type { ModelToolDefinition } from './modelClient';
import { requireConfiguration } from './contextCompactionClient';
import { requireConversation, requireConversationContext, requireModelTargetRef } from './conversationClient';
import {
  defaultHttpTransport, jsonInit, normalizeEndpoint, requestJson, type HttpTransport,
} from './http';

/** 当前身份可调用的目录以及部署默认引用。 */
export interface ModelTargetCatalog {
  readonly targets: readonly ModelTarget[];
  readonly defaultTarget: ModelTargetRef | null;
}

/** 保存会话切换后的原子响应。 */
export interface SavedModelHandoff {
  readonly conversation: Conversation;
  readonly context: ConversationContext;
}

/** Controller 所需的全部模型目标操作。 */
export interface ModelTargetClient {
  catalog(signal?: AbortSignal): Promise<ModelTargetCatalog>;
  switchDraft(context: ConversationContext, target: ModelTargetRef,
    tools: readonly ModelToolDefinition[], signal?: AbortSignal): Promise<ConversationContext>;
  switchConversation(id: string, revision: number, target: ModelTargetRef,
    tools: readonly ModelToolDefinition[], signal?: AbortSignal): Promise<SavedModelHandoff>;
}

/** 同源部署的目标客户端，不持有可变会话。 */
export class HttpModelTargetClient implements ModelTargetClient {
  private readonly endpoint: string;
  private readonly transport: HttpTransport;

  /** 与其他 Agent Client 使用同一宿主传输。 */
  constructor(endpoint: string, transport: HttpTransport = defaultHttpTransport) {
    this.endpoint = normalizeEndpoint(endpoint);
    this.transport = transport;
  }

  /** 每次列表请求仍由服务端重新检查用户权限。 */
  async catalog(signal?: AbortSignal): Promise<ModelTargetCatalog> {
    const value = await requestJson<unknown>(`${this.endpoint}/model/targets`,
      { method: 'GET', signal }, this.transport);
    const record = requireRecord(value, '模型目标目录');
    exactKeys(record, ['targets', 'defaultTarget'], '模型目标目录');
    if (!Array.isArray(record.targets)) throw invalidStateError('模型目标目录 targets 必须是数组');
    const targets = Object.freeze(record.targets.map(requireTarget));
    const ids = new Set(targets.map(target => target.ref.targetId));
    if (ids.size !== targets.length) throw invalidStateError('模型目标目录包含重复 targetId');
    const defaultTarget = record.defaultTarget == null ? null : requireModelTargetRef(record.defaultTarget);
    if (defaultTarget != null && !targets.some(target => sameTarget(target.ref, defaultTarget))) {
      throw invalidStateError('默认模型目标不在当前用户可用目录中');
    }
    return Object.freeze({ targets, defaultTarget });
  }

  /** 草稿只在后端完整预检通过后获得新上下文。 */
  async switchDraft(context: ConversationContext, target: ModelTargetRef,
    tools: readonly ModelToolDefinition[], signal?: AbortSignal): Promise<ConversationContext> {
    const value = await requestJson<unknown>(`${this.endpoint}/model/handoff`,
      jsonInit('POST', { context, target, tools }, signal), this.transport);
    const record = requireRecord(value, '草稿切换结果');
    exactKeys(record, ['context'], '草稿切换结果');
    return requireConversationContext(record.context);
  }

  /** 保存会话由服务端基于当前 revision 的原历史执行单次 CAS。 */
  async switchConversation(id: string, revision: number, target: ModelTargetRef,
    tools: readonly ModelToolDefinition[], signal?: AbortSignal): Promise<SavedModelHandoff> {
    const value = await requestJson<unknown>(
      `${this.endpoint}/conversations/${encodeURIComponent(id)}/model-target`,
      jsonInit('POST', { revision, target, tools }, signal), this.transport,
    );
    const record = requireRecord(value, '会话切换结果');
    exactKeys(record, ['conversation', 'context'], '会话切换结果');
    const conversation = requireConversation(record.conversation, '切换后会话');
    if (conversation.conversationId !== id || !Number.isSafeInteger(conversation.revision)
      || conversation.revision <= revision) {
      throw invalidStateError('切换后会话版本或身份无效');
    }
    return Object.freeze({
      conversation,
      context: requireConversationContext(record.context),
    });
  }
}

/** 验证一个服务端公开目标，不允许凭据或未知字段流进 Browser 状态。 */
function requireTarget(value: unknown): ModelTarget {
  const record = requireRecord(value, '模型目标');
  exactKeys(record, ['ref', 'displayName', 'protocol', 'imageInput', 'toolCalling', 'configuration'], '模型目标');
  if (typeof record.displayName !== 'string' || record.displayName.trim() === ''
    || typeof record.protocol !== 'string' || record.protocol.trim() === ''
    || typeof record.imageInput !== 'boolean' || typeof record.toolCalling !== 'boolean') {
    throw invalidStateError('模型目标公开字段无效');
  }
  return Object.freeze({
    ref: requireModelTargetRef(record.ref),
    displayName: record.displayName,
    protocol: record.protocol,
    imageInput: record.imageInput,
    toolCalling: record.toolCalling,
    configuration: requireConfiguration(record.configuration),
  });
}

/** 比较稳定目标身份与修订。 */
export function sameTarget(left: ModelTargetRef, right: ModelTargetRef): boolean {
  return left.targetId === right.targetId && left.routingRevision === right.routingRevision;
}

/** 不可信 HTTP 值必须是普通对象。 */
function requireRecord(value: unknown, field: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw invalidStateError(`${field} 必须是对象`);
  }
  return value as Record<string, unknown>;
}

/** 服务端契约发生变化时明确失败，避免误用不完整切换结果。 */
function exactKeys(record: Record<string, unknown>, keys: readonly string[], field: string): void {
  const actual = Object.keys(record);
  if (actual.length !== keys.length || actual.some(key => !keys.includes(key))) {
    throw invalidStateError(`${field} 字段不符合协议`);
  }
}
