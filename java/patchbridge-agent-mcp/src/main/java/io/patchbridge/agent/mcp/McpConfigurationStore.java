package io.patchbridge.agent.mcp;

import java.util.List;

/**
 * Global MCP Server 配置源端口。
 *
 * <p>properties 与 JDBC 是互斥 Adapter，Registry 每次只从一个实现读取完整快照，
 * 因此不存在同名覆盖、来源优先级或静默合并。存储实现必须使用 revision
 * 实现乐观锁，防止多个 Admin 页面互相覆盖。
 */
public interface McpConfigurationStore {

    /** 返回稳定的配置源标识，用于 Admin 显示当前管理边界。 */
    String source();

    /** 返回当前配置源是否允许在线变更。 */
    boolean mutable();

    /**
     * 返回只随持久化配置变化而改变的快速版本标识。
     * Registry 用它在多实例部署中检测其他实例的 Admin 变更，
     * 而无需每次解密并重建全部配置。
     */
    String version();

    /** 读取全部 Global MCP Server 配置快照。 */
    List<McpServerRecord> findAll();

    /** 按名称读取配置；不存在时返回 null。 */
    McpServerRecord find(String name);

    /** 创建新配置；同名配置必须明确报冲突。 */
    McpServerRecord create(String name, McpServerConfig config);

    /** 按预期 revision 更新完整配置。 */
    McpServerRecord update(String name, McpServerConfig config, long expectedRevision);

    /** 按预期 revision 删除配置。 */
    void delete(String name, long expectedRevision);
}
