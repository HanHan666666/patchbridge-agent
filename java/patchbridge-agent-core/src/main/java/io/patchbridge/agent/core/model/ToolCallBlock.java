package io.patchbridge.agent.core.model;

import java.util.Map;

/**
 * 已完成聚合的工具调用块。
 *
 * <p>稳定消息中的 input 必须是 JSON 对象；厂商流里的参数字符串增量只能存在于 {@link ModelBlockDeltaEvent}，不得泄漏到会话领域模型。
 */
public final class ToolCallBlock implements ContentBlock {
    /** 单次调用稳定标识，用于关联工具结果。 */
    private final String callId;

    /** Tool Registry 中的完整工具名。 */
    private final String name;

    /** 已解析且不可变的 JSON 参数对象。 */
    private final Map<String, Object> input;

    /** 创建工具调用块。 */
    public ToolCallBlock(String callId, String name, Map<String, Object> input) {
        requireText(callId, "tool-call.callId 不可为空");
        requireText(name, "tool-call.name 不可为空");
        this.callId = callId;
        this.name = name;
        this.input = ModelValues.immutableObject(input);
    }

    /** 返回工具调用块类型。 */
    @Override
    public BlockType getType() {
        return BlockType.TOOL_CALL;
    }

    /** 返回调用标识。 */
    public String getCallId() {
        return callId;
    }

    /** 返回完整工具名。 */
    public String getName() {
        return name;
    }

    /** 返回不可变参数对象。 */
    public Map<String, Object> getInput() {
        return input;
    }

    /** 校验协议必填文本。 */
    private static void requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }
}
