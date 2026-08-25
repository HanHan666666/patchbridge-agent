/**
 * 稳定消息与 ModelState 的运行时快照入口。
 *
 * <p>TypeScript readonly 只提供编译期约束；Controller、Runtime、Model 与宿主 View
 * 之间仍可能共享 JavaScript 对象。本文件统一深复制和冻结领域值，确保观察者或自定义
 * Adapter 不能反向修改已经提交的消息、Tool 参数或 Provider 状态。
 */
import {
  copyAndFreezeJsonObject,
  copyAndFreezeJsonValue,
} from './jsonValues';
import type {
  AgentMessage,
  ContentBlock,
  ConversationContext,
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
    modelState: snapshotModelState(context.modelState),
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
      return Object.freeze({
        ...block,
        content: Object.freeze(block.content.map(item => Object.freeze({ ...item }))),
      });
  }
}
