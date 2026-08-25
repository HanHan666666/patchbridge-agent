package io.patchbridge.agent.core.model;

/** Agent 消息支持的内容块类型。 */
public enum BlockType {
    /** 可展示文本。 */
    TEXT("text"),
    /** 图片输入。 */
    IMAGE("image"),
    /** 可展示的推理摘要，不等同于厂商连续状态。 */
    REASONING("reasoning"),
    /** 模型发起的工具调用。 */
    TOOL_CALL("tool-call"),
    /** 工具执行结果。 */
    TOOL_RESULT("tool-result");

    /** 公共协议中的稳定文本。 */
    private final String wireValue;

    /** 保存稳定协议值。 */
    BlockType(String wireValue) {
        this.wireValue = wireValue;
    }

    /** 返回公共协议值。 */
    public String getWireValue() {
        return wireValue;
    }
}
