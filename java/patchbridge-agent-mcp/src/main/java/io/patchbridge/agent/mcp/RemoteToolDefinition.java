package io.patchbridge.agent.mcp;

import java.util.Map;

/**
 * 远程 MCP Server 返回的原始 Tool 定义（未加命名空间前缀、未做权限映射）。
 * 统一命名空间与权限覆盖由 {@link McpToolRegistry} 在导入时完成。
 */
public final class RemoteToolDefinition {

    private final String name;
    private final String title;
    private final String description;
    private final Map<String, Object> inputSchema;
    private final boolean readOnlyHint;
    private final boolean destructiveHint;
    private final boolean idempotentHint;
    private final boolean requireConfirmation;

    public RemoteToolDefinition(String name, String title, String description,
                                 Map<String, Object> inputSchema,
                                 boolean readOnlyHint, boolean destructiveHint,
                                 boolean idempotentHint, boolean requireConfirmation) {
        this.name = name;
        this.title = title;
        this.description = description;
        this.inputSchema = inputSchema;
        this.readOnlyHint = readOnlyHint;
        this.destructiveHint = destructiveHint;
        this.idempotentHint = idempotentHint;
        this.requireConfirmation = requireConfirmation;
    }

    public String getName() { return name; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public Map<String, Object> getInputSchema() { return inputSchema; }
    public boolean isReadOnlyHint() { return readOnlyHint; }
    public boolean isDestructiveHint() { return destructiveHint; }
    public boolean isIdempotentHint() { return idempotentHint; }
    public boolean isRequireConfirmation() { return requireConfirmation; }
}
