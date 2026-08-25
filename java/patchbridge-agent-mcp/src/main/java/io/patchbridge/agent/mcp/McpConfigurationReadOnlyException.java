package io.patchbridge.agent.mcp;

/**
 * 当选中 properties 配置源时拒绝 Admin 在线修改。
 *
 * <p>框架不会把变更写到临时内存或与 YAML 暗中合并；用户必须
 * 显式选择 JDBC 模式后才能获得动态管理能力。
 */
public class McpConfigurationReadOnlyException extends RuntimeException {

    /** 创建包含当前配置源信息的只读异常。 */
    public McpConfigurationReadOnlyException(String message) {
        super(message);
    }
}
