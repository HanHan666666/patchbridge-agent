package io.patchbridge.agent.core.model;

/**
 * 一条 Agent 消息中的语义内容块。
 *
 * <p>内容块以领域语义取代厂商请求字段，使 UI、Runtime 和 Provider 可以分别负责 展示、编排与协议编码。新增能力应增加明确的块类型，而不是向消息追加可选 Map 字段。
 */
public interface ContentBlock {
    /** 返回内容块的稳定类型。 */
    BlockType getType();
}
