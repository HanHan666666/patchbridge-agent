package io.patchbridge.agent.core.compaction;

/**
 * 服务端模型窗口与压缩预算的唯一派生值。
 *
 * <p>自动阈值固定为窗口 80%；近期消息默认预算取 20% 与 20,000 token 的较小值；
 * 输出预留默认取窗口的 10%，且必须保存在阈值之上的余量里。
 * 窗口必须由宿主明确配置，Core 不维护可能与实际模型不一致的默认值。
 *
 * <p>输出预留是“模型输入最终预算检查”的唯一来源：输入（工作消息、system 指令、
 * Tool 定义）加上预留不得超过窗口，摘要请求同样遵守该预算。派生集中在本文类，
 * Browser 与摘要 Provider 都不得各自推导第二套预算。
 */
public final class ContextCompactionSettings {

    /** 当前模型上下文窗口。 */
    private final int contextWindowTokens;
    /** 固定 80% 自动压缩阈值。 */
    private final int automaticThresholdTokens;
    /** 压缩后近期真实消息预算。 */
    private final int keepRecentTokens;
    /** 为模型输出预留的窗口容量。 */
    private final int reservedOutputTokens;

    /** 使用默认近期预算与默认输出预留派生设置。 */
    public ContextCompactionSettings(int contextWindowTokens) {
        this(contextWindowTokens, null, null);
    }

    /** 使用宿主明确覆盖的近期预算派生设置，输出预留按默认规则派生。 */
    public ContextCompactionSettings(int contextWindowTokens, Integer keepRecentTokens) {
        this(contextWindowTokens, keepRecentTokens, null);
    }

    /**
     * 使用宿主明确覆盖的近期预算与输出预留派生设置。
     *
     * @param contextWindowTokens 当前模型上下文窗口
     * @param keepRecentTokens 可选的近期消息预算覆盖
     * @param reservedOutputTokens 可选的输出预留覆盖
     */
    public ContextCompactionSettings(int contextWindowTokens, Integer keepRecentTokens,
                                     Integer reservedOutputTokens) {
        if (contextWindowTokens <= 0) {
            throw new IllegalArgumentException("contextWindowTokens 必须大于 0");
        }
        int threshold = (int) (((long) contextWindowTokens * 80L) / 100L);
        int defaultKeep = Math.min(20_000, (int) (((long) contextWindowTokens * 20L) / 100L));
        int retained = keepRecentTokens == null ? defaultKeep : keepRecentTokens.intValue();
        int defaultReserve = (int) (((long) contextWindowTokens * 10L) / 100L);
        int reserve = reservedOutputTokens == null ? defaultReserve : reservedOutputTokens.intValue();
        if (threshold <= 0) {
            throw new IllegalArgumentException("contextWindowTokens 太小，无法派生 80% 阈值");
        }
        if (retained <= 0 || retained >= threshold) {
            throw new IllegalArgumentException(
                    "keepRecentTokens 必须大于 0 且小于自动压缩阈值");
        }
        // 预留必须完全落在阈值之上的余量内：否则压缩触发点本身已经超过
        // “窗口 − 输出预留”的输入预算，自动压缩将永远无法产出可用输入。
        if (reserve <= 0 || threshold + reserve >= contextWindowTokens) {
            throw new IllegalArgumentException(
                    "reservedOutputTokens 必须大于 0 且与 80% 阈值之和小于上下文窗口");
        }
        this.contextWindowTokens = contextWindowTokens;
        this.automaticThresholdTokens = threshold;
        this.keepRecentTokens = retained;
        this.reservedOutputTokens = reserve;
    }

    /** 返回模型上下文窗口。 */
    public int getContextWindowTokens() { return contextWindowTokens; }

    /** 返回固定 80% 自动压缩阈值。 */
    public int getAutomaticThresholdTokens() { return automaticThresholdTokens; }

    /** 返回近期真实消息保留预算。 */
    public int getKeepRecentTokens() { return keepRecentTokens; }

    /** 返回为模型输出预留的容量；输入预算即“窗口 − 该值”。 */
    public int getReservedOutputTokens() { return reservedOutputTokens; }
}
