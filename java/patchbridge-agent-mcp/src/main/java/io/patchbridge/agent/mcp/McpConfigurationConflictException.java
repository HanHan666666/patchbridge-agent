package io.patchbridge.agent.mcp;

/**
 * MCP 配置同名或 revision 冲突。
 *
 * <p>Admin API 将该异常映射为 409，强制管理员重新读取最新配置，
 * 而不是静默覆盖另一个管理员的修改。
 */
public class McpConfigurationConflictException extends RuntimeException {

    /** 保存可直接展示给 Admin 的冲突说明。 */
    public McpConfigurationConflictException(String message) {
        super(message);
    }
}
