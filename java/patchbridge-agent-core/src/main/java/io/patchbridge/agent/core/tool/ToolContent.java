package io.patchbridge.agent.core.tool;

/**
 * Tool 返回内容块。v0.1 只支持 text 类型（业务对象序列化为 JSON 文本），
 * 字段命名与 MCP content 对齐，便于后续直接映射标准 MCP Tool。
 */
public final class ToolContent {

    public static final String TYPE_TEXT = "text";

    private final String type;
    private final String text;

    public ToolContent(String type, String text) {
        this.type = type;
        this.text = text;
    }

    public static ToolContent text(String text) {
        return new ToolContent(TYPE_TEXT, text);
    }

    public String getType() {
        return type;
    }

    public String getText() {
        return text;
    }
}
