package io.patchbridge.agent.core.compaction;

/**
 * 服务端模型窗口与压缩预算的唯一派生值。
 *
 * <p>自动阈值固定为窗口 80%；近期消息默认预算取 20% 与 20,000 token 的较小值。
 * 窗口必须由宿主明确配置，Core 不维护可能与实际模型不一致的默认值。
 */
public final class ContextCompactionSettings {

    /** 当前模型上下文窗口。 */
    private final int contextWindowTokens;
    /** 固定 80% 自动压缩阈值。 */
    private final int automaticThresholdTokens;
    /** 压缩后近期真实消息预算。 */
    private final int keepRecentTokens;

    /** 使用默认近期预算派生设置。 */
    public ContextCompactionSettings(int contextWindowTokens) {
        this(contextWindowTokens, null);
    }

    /** 使用宿主明确覆盖的近期预算派生设置。 */
    public ContextCompactionSettings(int contextWindowTokens, Integer keepRecentTokens) {
        if (contextWindowTokens <= 0) {
            throw new IllegalArgumentException("contextWindowTokens 必须大于 0");
        }
        int threshold = (int) (((long) contextWindowTokens * 80L) / 100L);
        int defaultKeep = Math.min(20_000, (int) (((long) contextWindowTokens * 20L) / 100L));
        int retained = keepRecentTokens == null ? defaultKeep : keepRecentTokens.intValue();
        if (threshold <= 0) {
            throw new IllegalArgumentException("contextWindowTokens 太小，无法派生 80% 阈值");
        }
        if (retained <= 0 || retained >= threshold) {
            throw new IllegalArgumentException(
                    "keepRecentTokens 必须大于 0 且小于自动压缩阈值");
        }
        this.contextWindowTokens = contextWindowTokens;
        this.automaticThresholdTokens = threshold;
        this.keepRecentTokens = retained;
    }

    /** 返回模型上下文窗口。 */
    public int getContextWindowTokens() { return contextWindowTokens; }
    /** 返回固定 80% 自动压缩阈值。 */
    public int getAutomaticThresholdTokens() { return automaticThresholdTokens; }
    /** 返回近期真实消息保留预算。 */
    public int getKeepRecentTokens() { return keepRecentTokens; }
}
