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

    /** 创建不可变模型上下文计量。 */
    public ModelContextUsage(long totalTokens, Source source, String measuredThroughMessageId) {
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
        this.totalTokens = totalTokens;
        this.source = source;
        this.measuredThroughMessageId = measuredThroughMessageId;
    }

    /** 返回工作上下文 token 总数。 */
    public long getTotalTokens() { return totalTokens; }
    /** 返回计量来源。 */
    public Source getSource() { return source; }
    /** 返回计量覆盖到的最后消息 ID。 */
    public String getMeasuredThroughMessageId() { return measuredThroughMessageId; }
}
