package io.patchbridge.agent.core.tool;

/**
 * Tool 行为标注（字段命名对齐 MCP annotations，便于将来直接映射标准 MCP Tool）。
 *
 * <p>readOnlyHint / requireConfirmation 共同决定浏览器端的交互策略：
 * 只读 Tool 直接执行；requireConfirmation 的写操作在浏览器弹出 Human-in-the-loop 审批。
 * 注意这些只是交互提示，服务端真正的约束始终是 ToolAccessPolicy。
 */
public final class ToolAnnotations {

    private final boolean readOnlyHint;
    private final boolean destructiveHint;
    private final boolean idempotentHint;
    private final boolean requireConfirmation;

    public ToolAnnotations(boolean readOnlyHint, boolean destructiveHint,
                           boolean idempotentHint, boolean requireConfirmation) {
        this.readOnlyHint = readOnlyHint;
        this.destructiveHint = destructiveHint;
        this.idempotentHint = idempotentHint;
        this.requireConfirmation = requireConfirmation;
    }

    public boolean isReadOnlyHint() {
        return readOnlyHint;
    }

    public boolean isDestructiveHint() {
        return destructiveHint;
    }

    public boolean isIdempotentHint() {
        return idempotentHint;
    }

    public boolean isRequireConfirmation() {
        return requireConfirmation;
    }
}
