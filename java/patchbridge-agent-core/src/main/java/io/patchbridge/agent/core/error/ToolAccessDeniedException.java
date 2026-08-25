package io.patchbridge.agent.core.error;

/**
 * Tool 调用被服务端权限策略拒绝（TOOL_FORBIDDEN）。
 * 与业务失败（ToolExecutionException）严格区分：这是安全事件，必须审计。
 */
public class ToolAccessDeniedException extends Exception {

    public ToolAccessDeniedException(String message) {
        super(message);
    }
}
