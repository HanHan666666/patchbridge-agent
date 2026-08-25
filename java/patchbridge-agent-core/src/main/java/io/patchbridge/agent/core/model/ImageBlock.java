package io.patchbridge.agent.core.model;

/** 图片输入内容块；具体厂商图片结构由 Provider 编码。 */
public final class ImageBlock implements ContentBlock {
    /** 已校验为 URL 或 Base64 的互斥图片来源。 */
    private final ImageSource source;

    /** 创建图片块。 */
    public ImageBlock(ImageSource source) {
        if (source == null) {
            throw new IllegalArgumentException("图片块 source 不可为空");
        }
        this.source = source;
    }

    /** 返回图片块类型。 */
    @Override
    public BlockType getType() {
        return BlockType.IMAGE;
    }

    /** 返回不可变图片来源。 */
    public ImageSource getSource() {
        return source;
    }
}
