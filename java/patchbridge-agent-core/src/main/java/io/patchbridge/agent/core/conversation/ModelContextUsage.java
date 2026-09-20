package io.patchbridge.agent.core.conversation;

/**
 * 模型工作上下文最近一次 token 计量。
 *
 * <p>Provider 来源是正常模型响应的准确基线；Estimated 只允许表示一次成功压缩后的
 * 过渡快照，不能用于掩盖 Provider 缺失 usage。
 */
public final class ModelContextUsage {

    /** token 计量来源。 */
    public enum Source {
        /** 上游模型 Provider 明确报告。 */
        PROVIDER("provider"),
        /** 成功压缩后按模型消息保守估算。 */
        ESTIMATED("estimated");

        /** 稳定 JSON 协议值。 */
        private final String wireValue;

        /** 绑定稳定协议值。 */
        Source(String wireValue) { this.wireValue = wireValue; }

        /** 返回跨语言协议值。 */
        public String getWireValue() { return wireValue; }

        /** 从 JSON 协议值恢复来源。 */
        public static Source fromWireValue(String value) {
            for (Source source : values()) {
                if (source.wireValue.equals(value)) {
                    return source;
                }
            }
            throw new IllegalArgumentException("不支持的模型上下文用量来源: " + value);
        }
    }

    /** 当前模型工作上下文 token 总数。 */
    private final long totalTokens;
    /** Provider 或压缩估算来源。 */
    private final Source source;
    /** 计量覆盖到的最后一条完整聊天消息。 */
    private final String measuredThroughMessageId;

    /** 基线所覆盖目录的同口径估算量；压缩估算只覆盖消息，必须为 0。 */
    private final long toolDefinitionTokens;

    /** 创建不可变模型上下文计量。 */
    public ModelContextUsage(long totalTokens, Source source, String measuredThroughMessageId,
                             long toolDefinitionTokens) {
        if (totalTokens < 0) {
            throw new IllegalArgumentException("modelContext.usage.totalTokens 不可为负数");
        }
        if (source == null) {
            throw new IllegalArgumentException("modelContext.usage.source 不可为空");
        }
        if (measuredThroughMessageId != null && measuredThroughMessageId.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "modelContext.usage.measuredThroughMessageId 不允许为空字符串");
        }
        if (toolDefinitionTokens < 0 || (source == Source.ESTIMATED && toolDefinitionTokens != 0)) {
            throw new IllegalArgumentException(
                    "usage.toolDefinitionTokens 必须非负，且 estimated 基线必须为 0");
        }
        this.toolDefinitionTokens = toolDefinitionTokens;
        this.totalTokens = totalTokens;
        this.source = source;
        this.measuredThroughMessageId = measuredThroughMessageId;
    }

    /** 返回真实基线覆盖的目录估算量，供浏览器补计下一次目录增长量。 */
    public long getToolDefinitionTokens() { return toolDefinitionTokens; }
    /** 返回工作上下文 token 总数。 */
    public long getTotalTokens() { return totalTokens; }
    /** 返回计量来源。 */
    public Source getSource() { return source; }
    /** 返回计量覆盖到的最后消息 ID。 */
    public String getMeasuredThroughMessageId() { return measuredThroughMessageId; }
}
