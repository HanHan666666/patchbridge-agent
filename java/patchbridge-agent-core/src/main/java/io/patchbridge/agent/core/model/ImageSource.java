package io.patchbridge.agent.core.model;

/** 图片输入来源；URL 与 Base64 使用互斥工厂，避免无效字段组合。 */
public final class ImageSource {
    /** 图片来源类型。 */
    public enum Type {
        /** 可由模型服务访问的 URL。 */
        URL("url"),
        /** 内嵌 Base64 字节。 */
        BASE64("base64");

        /** 公共协议稳定值。 */
        private final String wireValue;

        /** 保存稳定协议值。 */
        Type(String wireValue) {
            this.wireValue = wireValue;
        }

        /** 返回公共协议值。 */
        public String getWireValue() {
            return wireValue;
        }
    }

    /** 来源类型，决定其余字段的有效组合。 */
    private final Type type;

    /** URL 来源地址，仅 URL 类型有效。 */
    private final String url;

    /** Base64 MIME 类型，仅 Base64 类型有效。 */
    private final String mediaType;

    /** Base64 原始编码文本，仅 Base64 类型有效。 */
    private final String data;

    /** 保存已经校验的互斥来源字段。 */
    private ImageSource(Type type, String url, String mediaType, String data) {
        this.type = type;
        this.url = url;
        this.mediaType = mediaType;
        this.data = data;
    }

    /** 创建 URL 图片来源。 */
    public static ImageSource url(String url) {
        requireText(url, "图片 URL 不可为空");
        return new ImageSource(Type.URL, url, null, null);
    }

    /** 创建内嵌 Base64 图片来源。 */
    public static ImageSource base64(String mediaType, String data) {
        requireText(mediaType, "Base64 图片 mediaType 不可为空");
        requireText(data, "Base64 图片 data 不可为空");
        return new ImageSource(Type.BASE64, null, mediaType, data);
    }

    /** 返回来源类型。 */
    public Type getType() {
        return type;
    }

    /** 返回 URL；非 URL 来源时为空。 */
    public String getUrl() {
        return url;
    }

    /** 返回 MIME 类型；非 Base64 来源时为空。 */
    public String getMediaType() {
        return mediaType;
    }

    /** 返回 Base64 编码文本；非 Base64 来源时为空。 */
    public String getData() {
        return data;
    }

    /** 校验必填文本，禁止由 Core 工厂创建歧义来源。 */
    private static void requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }
}
