package io.patchbridge.agent.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 工具执行结果块；第一版内容限定为文本块以保持模型协议明确。 */
public final class ToolResultBlock implements ContentBlock {
    /** 区分真实结果与 Runtime 中止记录；恢复会话时据此约束未知调用的再次执行。 */
    public enum Execution {
        /** 已取得真实业务结果，无论业务成功或失败。 */
        COMPLETED("completed"),
        /** 尚未发出调用，含取消和人工拒绝。 */
        NOT_EXECUTED("not-executed"),
        /** 调用已发出，但未取得结果。 */
        UNKNOWN("unknown"),
        /** 结果已返回，但因结果上限未回填。 */
        RESULT_OMITTED("result-omitted");

        /** 跨语言稳定协议值。 */
        private final String wireValue;

        /** 绑定执行事实的协议表示。 */
        Execution(String wireValue) { this.wireValue = wireValue; }

        /** 用于持久化和 HTTP 传输的协议值。 */
        public String getWireValue() { return wireValue; }

        /** 严格恢复执行事实；缺失或未知值不得被视为已完成。 */
        public static Execution fromWireValue(String value) {
            for (Execution execution : values()) {
                if (execution.wireValue.equals(value)) {
                    return execution;
                }
            }
            throw new IllegalArgumentException("不支持的 tool-result.execution: " + value);
        }
    }

    /** 与业务状态独立的执行事实，必须随结果持久化。 */
    private final Execution execution;

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
            String callId, String name, ToolResultStatus status, Execution execution, List<TextBlock> content) {
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
        if (execution == null) {
            throw new IllegalArgumentException("tool-result.execution 不可为空");
        }
        if (execution != Execution.COMPLETED && status != ToolResultStatus.ERROR) {
            throw new IllegalArgumentException("非真实结果的 tool-result.status 必须为 error");
        }
        this.execution = execution;
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

    /** 返回可用于恢复核实约束的真实执行状态。 */
    public Execution getExecution() { return execution; }

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
