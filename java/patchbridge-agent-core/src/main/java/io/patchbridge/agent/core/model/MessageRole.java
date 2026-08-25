package io.patchbridge.agent.core.model;

/** Agent 领域消息角色；不隐含任何厂商序列化字段。 */
public enum MessageRole {
    /** 系统提供的行为约束。 */
    SYSTEM("system"),
    /** 最终用户输入。 */
    USER("user"),
    /** 模型生成的内容或工具调用。 */
    ASSISTANT("assistant"),
    /** 工具执行结果。 */
    TOOL("tool");

    /** 浏览器公共协议使用的稳定文本值。 */
    private final String wireValue;

    /** 保存稳定协议值，避免依赖枚举名称的大小写。 */
    MessageRole(String wireValue) {
        this.wireValue = wireValue;
    }

    /** 返回浏览器公共协议值。 */
    public String getWireValue() {
        return wireValue;
    }

    /** 从浏览器协议值解析角色，未知值必须明确失败。 */
    public static MessageRole fromWireValue(String value) {
        for (MessageRole role : values()) {
            if (role.wireValue.equals(value)) {
                return role;
            }
        }
        throw new IllegalArgumentException("不支持的消息角色: " + value);
    }
}
