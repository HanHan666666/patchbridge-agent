package io.patchbridge.agent.core.model;

/** 可展示或可发送给模型的普通文本块。 */
public final class TextBlock implements ContentBlock {
    /** 文本原文；空字符串有明确流式语义，因此不自动丢弃。 */
    private final String text;

    /** 创建文本块。 */
    public TextBlock(String text) {
        if (text == null) {
            throw new IllegalArgumentException("文本块 text 不可为空");
        }
        this.text = text;
    }

    /** 返回文本块类型。 */
    @Override
    public BlockType getType() {
        return BlockType.TEXT;
    }

    /** 返回未经改写的文本。 */
    public String getText() {
        return text;
    }
}
