package io.patchbridge.agent.core.model;

/** 厂商中立的模型停止原因。 */
public enum ModelStopReason {
    /** 模型自然完成当前回答。 */
    END_TURN("end-turn"),
    /** 模型要求执行工具。 */
    TOOL_USE("tool-use"),
    /** 达到输出 token 上限。 */
    MAX_TOKENS("max-tokens"),
    /** 命中调用方配置的停止序列。 */
    STOP_SEQUENCE("stop-sequence"),
    /** 厂商返回了框架尚未细分的停止原因。 */
    OTHER("other");

    /** 浏览器协议稳定值。 */
    private final String wireValue;

    /** 保存稳定协议值。 */
    ModelStopReason(String wireValue) {
        this.wireValue = wireValue;
    }

    /** 返回浏览器协议值。 */
    public String getWireValue() {
        return wireValue;
    }
}
