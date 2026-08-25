package io.patchbridge.agent.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Global MCP 配置的应用服务与唯一变更入口。
 *
 * <p>它负责凭据更新语义、配置校验以及存储成功后的 Registry
 * 不可变快照重建。变更方法使用实例锁串行化，保证同一进程内“落库—重载”
 * 的发布顺序；数据库 revision 仍负责防止多页面或多实例覆盖。
 */
public final class McpConfigurationManager {

    /** 当前唯一配置源。 */
    private final McpConfigurationStore store;

    /** 接收配置快照的运行时 Registry。 */
    private final McpToolRegistry registry;

    /** 组合选定的配置源和运行时 Registry。 */
    public McpConfigurationManager(McpConfigurationStore store, McpToolRegistry registry) {
        this.store = store;
        this.registry = registry;
    }

    /** 返回当前配置源标识。 */
    public String source() { return store.source(); }

    /** 返回当前配置源是否允许 Admin 变更。 */
    public boolean mutable() { return store.mutable(); }

    /** 返回全部配置记录，仅供 Controller 转为脱敏视图。 */
    public List<McpServerRecord> records() { return store.findAll(); }

    /** 返回指定记录；不存在时明确失败。 */
    public McpServerRecord require(String name) {
        McpServerRecord record = store.find(name);
        if (record == null) {
            throw new McpException("未配置的 MCP Server: " + name);
        }
        return record;
    }

    /** 创建配置并原子发布新的运行时路由快照。 */
    public synchronized McpServerRecord create(String name, McpServerConfig config) {
        ensureMutable();
        if (config == null) {
            throw new IllegalArgumentException("MCP Server 配置不能为空");
        }
        McpServerConfig safe = config.copy();
        McpServerConfigValidator.validate(name, safe);
        McpServerRecord created = store.create(name, safe);
        publishStoreSnapshot();
        return created;
    }

    /**
     * 更新配置并按显式指令保留、替换或清除凭据。
     * KEEP 不允许改变认证类型，避免将旧凭据误用于新协议。
     */
    public synchronized McpServerRecord update(String name, McpServerConfig requested,
                                                long expectedRevision,
                                                McpCredentialUpdate credentialUpdate) {
        ensureMutable();
        if (credentialUpdate == null) {
            throw new IllegalArgumentException("credentialUpdate 必须显式指定");
        }
        if (requested == null) {
            throw new IllegalArgumentException("MCP Server 配置不能为空");
        }
        if (requested.getAuth() == null) {
            throw new IllegalArgumentException("MCP auth 不能为空");
        }
        McpServerConfig previous = require(name).getConfig();
        McpServerConfig safe = requested.copy();
        if (credentialUpdate == McpCredentialUpdate.KEEP) {
            if (!previous.getAuth().getType().equals(safe.getAuth().getType())) {
                throw new IllegalArgumentException("KEEP 凭据时不能改变 auth.type");
            }
            safe.setAuth(previous.getAuth());
        } else if (credentialUpdate == McpCredentialUpdate.REPLACE) {
            if (McpServerConfig.Auth.NONE.equals(safe.getAuth().getType())) {
                throw new IllegalArgumentException(
                        "REPLACE 必须提供非 none 的完整凭据；清除凭据请使用 CLEAR");
            }
        } else if (credentialUpdate == McpCredentialUpdate.CLEAR) {
            if (!McpServerConfig.Auth.NONE.equals(safe.getAuth().getType())) {
                throw new IllegalArgumentException("CLEAR 凭据时 auth.type 必须为 none");
            }
            safe.setAuth(new McpServerConfig.Auth());
        }
        McpServerConfigValidator.validate(name, safe);
        McpServerRecord updated = store.update(name, safe, expectedRevision);
        publishStoreSnapshot();
        return updated;
    }

    /** 只改变启用状态，已存凭据与其他配置保持不变。 */
    public synchronized McpServerRecord setEnabled(String name, boolean enabled,
                                                    long expectedRevision) {
        ensureMutable();
        McpServerConfig config = require(name).getConfig();
        config.setEnabled(enabled);
        McpServerRecord updated = store.update(name, config, expectedRevision);
        publishStoreSnapshot();
        return updated;
    }

    /** 删除配置并立即从运行时路由快照移除它。 */
    public synchronized void delete(String name, long expectedRevision) {
        ensureMutable();
        store.delete(name, expectedRevision);
        publishStoreSnapshot();
    }

    /** 将配置源当前全量状态转为 Registry 的单一不可变快照。 */
    private void publishStoreSnapshot() {
        Map<String, McpServerConfig> configs = new LinkedHashMap<String, McpServerConfig>();
        for (McpServerRecord record : store.findAll()) {
            configs.put(record.getName(), record.getConfig());
        }
        registry.replaceAll(configs);
    }

    /** 在任何请求内容解析前先强制配置源的只读边界。 */
    private void ensureMutable() {
        if (!store.mutable()) {
            throw new McpConfigurationReadOnlyException(
                    "当前 MCP 配置源为 " + store.source()
                            + "；在线变更需显式选择可变配置源");
        }
    }
}
