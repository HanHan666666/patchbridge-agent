package io.patchbridge.agent.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * properties/YAML MCP 配置源。
 *
 * <p>该 Adapter 在启动时拷贝并校验 Binder 结果，后续只读。Admin 的变更请求
 * 会明确失败，不写临时内存，也不与 JDBC 配置合并。
 */
public final class PropertiesMcpConfigurationStore implements McpConfigurationStore {

    /** 启动时固化的配置快照。 */
    private final Map<String, McpServerRecord> records;

    /** 从已完成绑定的 properties Map 创建只读配置源。 */
    public PropertiesMcpConfigurationStore(Map<String, McpServerConfig> configs) {
        Map<String, McpServerRecord> copied = new LinkedHashMap<String, McpServerRecord>();
        if (configs != null) {
            for (Map.Entry<String, McpServerConfig> entry : configs.entrySet()) {
                // null 配置（如 YAML 写了 key 但未给值）必须在绑定期明确失败，
                // 不能被当作“空配置”静默跳过或替换成默认对象。
                if (entry.getValue() == null) {
                    throw new IllegalArgumentException(
                            "patchbridge-agent.mcp.servers." + entry.getKey()
                                    + " 配置不能为空");
                }
                McpServerConfig config = entry.getValue().copy();
                McpServerConfigValidator.validate(entry.getKey(), config);
                copied.put(entry.getKey(), new McpServerRecord(entry.getKey(), config,
                        0L, 0L, 0L,
                        McpServerConfigValidator.credentialConfigured(config.getAuth())));
            }
        }
        records = Collections.unmodifiableMap(copied);
    }

    /** 返回 properties 来源标识。 */
    @Override
    public String source() { return "properties"; }

    /** properties 配置不接受运行时修改。 */
    @Override
    public boolean mutable() { return false; }

    /** properties 快照在进程生命周期内不会变化。 */
    @Override
    public String version() { return "properties-startup-snapshot"; }

    /** 返回按 Server 名称排序的固化快照。 */
    @Override
    public List<McpServerRecord> findAll() {
        List<McpServerRecord> result = new ArrayList<McpServerRecord>(records.values());
        Collections.sort(result, new Comparator<McpServerRecord>() {
            @Override
            public int compare(McpServerRecord left, McpServerRecord right) {
                return left.getName().compareTo(right.getName());
            }
        });
        return result;
    }

    /** 返回指定的固化记录。 */
    @Override
    public McpServerRecord find(String name) { return records.get(name); }

    /** properties 模式禁止创建。 */
    @Override
    public McpServerRecord create(String name, McpServerConfig config) {
        throw readOnly();
    }

    /** properties 模式禁止更新。 */
    @Override
    public McpServerRecord update(String name, McpServerConfig config, long expectedRevision) {
        throw readOnly();
    }

    /** properties 模式禁止删除。 */
    @Override
    public void delete(String name, long expectedRevision) {
        throw readOnly();
    }

    /** 创建统一的只读配置说明。 */
    private static McpConfigurationReadOnlyException readOnly() {
        return new McpConfigurationReadOnlyException(
                "当前 MCP 配置源为 properties；在线变更需显式设置 patchbridge-agent.mcp.source=jdbc");
    }
}
