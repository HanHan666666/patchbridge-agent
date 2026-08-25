package io.patchbridge.agent.core.model;

/**
 * Java 单次模型调用完成后的厂商中立结果。
 *
 * <p>响应保留结构化 assistant 消息和 Provider 连续状态，避免把普通文本、推理内容及协议状态压成一个字符串。所有成员均为不可变值对象，宿主可以自行决定是否以及如何持久化。
 */
public final class ModelResponse {

    /** 本次模型生成的完整 assistant 消息。 */
    private final AgentMessage message;

    /** Provider 归一化后的停止原因。 */
    private final ModelStopReason stopReason;

    /** 厂商报告的可选 token 用量；Core 不进行估算。 */
    private final ModelUsage usage;

    /** 下一次调用可原样传回相同 Provider 的可选连续状态。 */
    private final ModelState modelState;

    /** 创建已经完成协议校验的不可变模型响应。 */
    public ModelResponse(
            AgentMessage message,
            ModelStopReason stopReason,
            ModelUsage usage,
            ModelState modelState) {
        if (message == null) {
            throw new IllegalArgumentException("message 不可为空");
        }
        if (message.getRole() != MessageRole.ASSISTANT) {
            throw new IllegalArgumentException("ModelResponse.message 必须是 assistant 消息");
        }
        if (stopReason == null) {
            throw new IllegalArgumentException("stopReason 不可为空");
        }
        this.message = message;
        this.stopReason = stopReason;
        this.usage = usage;
        this.modelState = modelState;
    }

    /** 返回包含有序内容块的完整 assistant 消息。 */
    public AgentMessage getMessage() {
        return message;
    }

    /** 返回本次调用的标准化停止原因。 */
    public ModelStopReason getStopReason() {
        return stopReason;
    }

    /** 返回厂商报告的 token 用量；厂商未提供时为空。 */
    public ModelUsage getUsage() {
        return usage;
    }

    /** 返回 Provider 连续状态；当前协议不需要连续状态时为空。 */
    public ModelState getModelState() {
        return modelState;
    }

    /**
     * 按块顺序拼接普通文本，忽略 reasoning 等非文本展示块。
     *
     * <p>该方法只为常见文本用例减少样板代码，不承担业务 DTO 解析或内容改写。
     */
    public String getText() {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : message.getBlocks()) {
            if (block instanceof TextBlock) {
                text.append(((TextBlock) block).getText());
            }
        }
        return text.toString();
    }
}
