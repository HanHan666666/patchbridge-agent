package io.patchbridge.agent.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 工具执行结果块；第一版内容限定为文本块以保持模型协议明确。 */
public final class ToolResultBlock implements ContentBlock {
    /** 被响应的工具调用标识。 */
    private final String callId;

    /** 工具完整名称，便于审计和不支持 callId 的 Provider 使用。 */
    private final String name;

    /** 明确的执行状态。 */
    private final ToolResultStatus status;

    /** 不可变文本结果列表。 */
    private final List<TextBlock> content;

    /** 创建工具结果块。 */
    public ToolResultBlock(
            String callId, String name, ToolResultStatus status, List<TextBlock> content) {
        requireText(callId, "tool-result.callId 不可为空");
        requireText(name, "tool-result.name 不可为空");
        if (status == null) {
            throw new IllegalArgumentException("tool-result.status 不可为空");
        }
        if (content == null || content.isEmpty()) {
            throw new IllegalArgumentException("tool-result.content 不可为空");
        }
        if (content.contains(null)) {
            throw new IllegalArgumentException("tool-result.content 不允许包含 null");
        }
        this.callId = callId;
        this.name = name;
        this.status = status;
        this.content = Collections.unmodifiableList(new ArrayList<TextBlock>(content));
    }

    /** 返回工具结果块类型。 */
    @Override
    public BlockType getType() {
        return BlockType.TOOL_RESULT;
    }

    /** 返回调用标识。 */
    public String getCallId() {
        return callId;
    }

    /** 返回工具名。 */
    public String getName() {
        return name;
    }

    /** 返回执行状态。 */
    public ToolResultStatus getStatus() {
        return status;
    }

    /** 返回不可变文本内容。 */
    public List<TextBlock> getContent() {
        return content;
    }

    /** 校验协议必填文本。 */
    private static void requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }
}
