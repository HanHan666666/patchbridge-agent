package io.patchbridge.agent.core.model;

/**
 * 面向用户展示的模型推理文本块。
 *
 * <p>该块绝不能承载签名、加密 reasoning item 等协议连续状态；那些内容只能放在 {@link ModelState} 中并由同一 Provider 解释。
 */
public final class ReasoningBlock implements ContentBlock {
    /** 模型允许展示的推理文本或摘要。 */
    private final String text;

    /** 创建可展示推理块。 */
    public ReasoningBlock(String text) {
        if (text == null) {
            throw new IllegalArgumentException("推理块 text 不可为空");
        }
        this.text = text;
    }

    /** 返回推理块类型。 */
    @Override
    public BlockType getType() {
        return BlockType.REASONING;
    }

    /** 返回可展示推理文本。 */
    public String getText() {
        return text;
    }
}
