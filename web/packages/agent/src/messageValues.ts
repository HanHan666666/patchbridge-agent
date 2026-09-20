/**
 * 稳定消息与 ModelContext 的运行时快照入口。
 *
 * <p>TypeScript readonly 只提供编译期约束；Controller、Runtime、Model 与宿主 View
 * 之间仍可能共享 JavaScript 对象。本文件统一深复制和冻结领域值，确保观察者或自定义
 * Adapter 不能反向修改已经提交的消息、Tool 参数或 Provider 状态。
 */
import {
  copyAndFreezeJsonObject,
  copyAndFreezeJsonValue,
} from './jsonValues';
import { invalidStateError } from './errors';
import type {
  AgentMessage,
  ContentBlock,
  ConversationContext,
  ModelContext,
  ModelState,
} from './types';

/** 复制并深冻结稳定消息。 */
export function snapshotAgentMessage(message: AgentMessage): AgentMessage {
  return Object.freeze({
    id: message.id,
    role: message.role,
    blocks: Object.freeze(message.blocks.map(snapshotContentBlock)),
  });
}

/** 复制并冻结 Provider 私有状态，但不解释 data 的业务含义。 */
export function snapshotModelState(state: ModelState | null): ModelState | null {
  if (state == null) {
    return null;
  }
  return Object.freeze({
    format: state.format,
    data: copyAndFreezeJsonValue(state.data),
  });
}

/** 同时固定消息和对应 ModelState，避免恢复后的上下文被宿主引用改写。 */
export function snapshotConversationContext(
  context: ConversationContext,
): ConversationContext {
  return Object.freeze({
    messages: Object.freeze(context.messages.map(snapshotAgentMessage)),
    modelContext: snapshotModelContext(context.modelContext),
  });
}

/** 复制并冻结模型工作上下文，不允许 View 或 Adapter 改写检查点与用量。 */
export function snapshotModelContext(context: ModelContext): ModelContext {
  return Object.freeze({
    checkpoint: context.checkpoint == null
      ? null
      : Object.freeze({ ...context.checkpoint }),
    firstRetainedMessageId: context.firstRetainedMessageId,
    modelState: snapshotModelState(context.modelState),
    usage: context.usage == null ? null : Object.freeze({ ...context.usage }),
  });
}

/** 根据判别字段复制一个 ContentBlock，并冻结其嵌套 JSON。 */
function snapshotContentBlock(block: ContentBlock): ContentBlock {
  switch (block.type) {
    case 'text':
    case 'reasoning':
      return Object.freeze({ ...block });
    case 'image':
      return Object.freeze({
        type: 'image',
        source: Object.freeze({ ...block.source }),
      });
    case 'tool-call':
      return Object.freeze({
        ...block,
        input: copyAndFreezeJsonObject(block.input),
      });
    case 'tool-result':
      if (!['completed', 'not-executed', 'unknown', 'result-omitted'].includes(block.execution)
        || (block.execution !== 'completed' && block.status !== 'error')) {
        throw invalidStateError('tool-result.execution 必须明确记录执行事实，非真实结果必须标为 error');
      }
      return Object.freeze({
        ...block,
        content: Object.freeze(block.content.map(item => Object.freeze({ ...item }))),
      });
  }
}
