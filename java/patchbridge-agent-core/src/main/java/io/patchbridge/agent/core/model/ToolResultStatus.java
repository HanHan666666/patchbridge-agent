package io.patchbridge.agent.core.model;

/** 工具结果状态；让模型能区分业务结果与执行失败。 */
public enum ToolResultStatus {
    /** 工具执行成功。 */
    SUCCESS("success"),
    /** 工具已执行但返回错误。 */
    ERROR("error");

    /** 公共协议稳定值。 */
    private final String wireValue;

    /** 保存稳定协议值。 */
    ToolResultStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    /** 返回公共协议值。 */
    public String getWireValue() {
        return wireValue;
    }

    /** 解析公共协议值，未知状态不得静默当成成功。 */
    public static ToolResultStatus fromWireValue(String value) {
        for (ToolResultStatus status : values()) {
            if (status.wireValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("不支持的工具结果状态: " + value);
    }
}
