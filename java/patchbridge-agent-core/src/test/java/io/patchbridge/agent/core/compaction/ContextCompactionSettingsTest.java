package io.patchbridge.agent.core.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** 验证模型窗口、固定自动阈值与近期保留预算的唯一派生规则。 */
class ContextCompactionSettingsTest {

    /** 128K 窗口应在 80% 自动压缩，并把默认近期预算限制为 20K。 */
    @Test
    void derivesDefaultBudgetsForLargeWindow() {
        ContextCompactionSettings settings = new ContextCompactionSettings(128_000);

        assertEquals(128_000, settings.getContextWindowTokens());
        assertEquals(102_400, settings.getAutomaticThresholdTokens());
        assertEquals(20_000, settings.getKeepRecentTokens());
    }

    /** 小窗口默认近期预算取窗口 20%，而不是固定 20K。 */
    @Test
    void derivesTwentyPercentRecentBudgetForSmallWindow() {
        ContextCompactionSettings settings = new ContextCompactionSettings(32_000);

        assertEquals(25_600, settings.getAutomaticThresholdTokens());
        assertEquals(6_400, settings.getKeepRecentTokens());
    }

    /** 宿主可明确覆盖近期预算，但不能越过自动压缩阈值。 */
    @Test
    void validatesExplicitRecentBudget() {
        assertEquals(
                8_000,
                new ContextCompactionSettings(128_000, Integer.valueOf(8_000))
                        .getKeepRecentTokens());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ContextCompactionSettings(1_000, Integer.valueOf(800)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ContextCompactionSettings(1_000, Integer.valueOf(0)));
    }

    /** 缺少有效上下文窗口时不能生成任何默认值。 */
    @Test
    void rejectsInvalidContextWindow() {
        assertThrows(IllegalArgumentException.class, () -> new ContextCompactionSettings(0));
        assertThrows(IllegalArgumentException.class, () -> new ContextCompactionSettings(-1));
    }
}
