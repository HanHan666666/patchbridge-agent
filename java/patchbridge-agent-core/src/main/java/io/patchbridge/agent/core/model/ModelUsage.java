package io.patchbridge.agent.core.model;

/** 单次模型响应的标准化 token 用量。 */
public final class ModelUsage {
    /** 输入 token 数。 */
    private final long inputTokens;

    /** 输出 token 数。 */
    private final long outputTokens;

    /** 厂商报告的总 token 数。 */
    private final long totalTokens;

    /** 创建非负 token 用量。 */
    public ModelUsage(long inputTokens, long outputTokens, long totalTokens) {
        if (inputTokens < 0 || outputTokens < 0 || totalTokens < 0) {
            throw new IllegalArgumentException("token 用量不可为负数");
        }
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.totalTokens = totalTokens;
    }

    /** 返回输入 token 数。 */
    public long getInputTokens() {
        return inputTokens;
    }

    /** 返回输出 token 数。 */
    public long getOutputTokens() {
        return outputTokens;
    }

    /** 返回总 token 数。 */
    public long getTotalTokens() {
        return totalTokens;
    }
}
