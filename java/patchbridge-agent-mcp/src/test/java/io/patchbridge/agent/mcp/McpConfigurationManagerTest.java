package io.patchbridge.agent.mcp;

import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.tool.ToolCallResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MCP 配置管理服务的凭据语义与 Registry 热更新测试。
 *
 * <p>这些测试使用显式可变内存 Store 作为端口替身，只验证
 * Manager/Registry 边界；JDBC 加密和 SQL revision 由存储模块独立测试。
 */
class McpConfigurationManagerTest {

    /** 新配置成功写入后应立即出现在 Registry 路由快照中。 */
    @Test
    void createPublishesNewImmutableRegistrySnapshot() {
        InMemoryStore store = new InMemoryStore();
        RecordingClient client = new RecordingClient();
        McpToolRegistry registry = new McpToolRegistry(
                Collections.<String, McpServerConfig>emptyMap(), client, "mcp");
        McpConfigurationManager manager = new McpConfigurationManager(store, registry);

        manager.create("inventory", config("first-token"));
        registry.refresh("inventory");

        assertEquals(Collections.singletonList("inventory"), client.listedServers);
        assertEquals("first-token", client.lastToken);
        assertEquals(1, registry.serverViews().size());
    }

    /** KEEP 必须保留旧凭据，但不允许借此改变认证类型。 */
    @Test
    void keepCredentialIsExplicitAndTypeSafe() {
        InMemoryStore store = new InMemoryStore();
        McpToolRegistry registry = new McpToolRegistry(
                Collections.<String, McpServerConfig>emptyMap(), new RecordingClient(), "mcp");
        McpConfigurationManager manager = new McpConfigurationManager(store, registry);
        McpServerRecord created = manager.create("inventory", config("first-token"));
        McpServerConfig requested = config(null);
        requested.setCacheTtlMs(6000L);

        McpServerRecord updated = manager.update("inventory", requested,
                created.getRevision(), McpCredentialUpdate.KEEP);

        assertEquals("first-token", updated.getConfig().getAuth().getToken());
        McpServerConfig changedType = requested.copy();
        changedType.getAuth().setType(McpServerConfig.Auth.BASIC);
        assertThrows(IllegalArgumentException.class, () -> manager.update(
                "inventory", changedType, updated.getRevision(), McpCredentialUpdate.KEEP));
    }

    /** KEEP 的请求 auth 不能为 null，避免深拷贝时被静默解释为 none。 */
    @Test
    void keepRejectsMissingAuthObjectExplicitly() {
        InMemoryStore store = new InMemoryStore();
        McpToolRegistry registry = new McpToolRegistry(
                Collections.<String, McpServerConfig>emptyMap(), new RecordingClient(), "mcp");
        McpConfigurationManager manager = new McpConfigurationManager(store, registry);
        McpServerRecord created = manager.create("inventory", config("first-token"));
        McpServerConfig requested = config(null);
        requested.setAuth(null);

        assertThrows(IllegalArgumentException.class, () -> manager.update(
                "inventory", requested, created.getRevision(), McpCredentialUpdate.KEEP));
    }

    /** REPLACE 只负责写入新凭据，none 必须使用语义明确的 CLEAR。 */
    @Test
    void replaceRejectsNoneAuth() {
        InMemoryStore store = new InMemoryStore();
        McpToolRegistry registry = new McpToolRegistry(
                Collections.<String, McpServerConfig>emptyMap(), new RecordingClient(), "mcp");
        McpConfigurationManager manager = new McpConfigurationManager(store, registry);
        McpServerRecord created = manager.create("inventory", config("first-token"));
        McpServerConfig requested = config(null);
        requested.setAuth(new McpServerConfig.Auth());

        assertThrows(IllegalArgumentException.class, () -> manager.update(
                "inventory", requested, created.getRevision(), McpCredentialUpdate.REPLACE));
    }

    /** properties 源应在解析变更内容前直接拒绝，不创建内存替代配置。 */
    @Test
    void propertiesSourceRejectsMutationAtBoundary() {
        PropertiesMcpConfigurationStore store = new PropertiesMcpConfigurationStore(
                Collections.<String, McpServerConfig>emptyMap());
        McpToolRegistry registry = new McpToolRegistry(
                Collections.<String, McpServerConfig>emptyMap(), new RecordingClient(), "mcp");
        McpConfigurationManager manager = new McpConfigurationManager(store, registry);

        assertThrows(McpConfigurationReadOnlyException.class,
                () -> manager.create("inventory", null));
    }

    /** 创建最小有效配置；token 允许为 null 仅用于 KEEP 请求 DTO 模拟。 */
    private static McpServerConfig config(String token) {
        McpServerConfig config = new McpServerConfig();
        config.setUrl("https://mcp.example.test/mcp");
        config.setCacheTtlMs(5000L);
        config.getAuth().setType(McpServerConfig.Auth.BEARER);
        config.getAuth().setToken(token);
        return config;
    }

    /** 只用于 Manager 边界测试的显式可变 Store。 */
    private static final class InMemoryStore implements McpConfigurationStore {
        /** 按 Server 名称保存当前记录。 */
        private final Map<String, McpServerRecord> records =
                new LinkedHashMap<String, McpServerRecord>();

        @Override public String source() { return "test"; }
        @Override public boolean mutable() { return true; }
        @Override public String version() { return String.valueOf(records.hashCode()); }
        @Override public List<McpServerRecord> findAll() {
            return new ArrayList<McpServerRecord>(records.values());
        }
        @Override public McpServerRecord find(String name) { return records.get(name); }
        @Override public McpServerRecord create(String name, McpServerConfig config) {
            McpServerRecord record = new McpServerRecord(name, config, 0L, 1L, 1L, true);
            records.put(name, record);
            return record;
        }
        @Override public McpServerRecord update(String name, McpServerConfig config,
                                                long expectedRevision) {
            McpServerRecord current = records.get(name);
            if (current.getRevision() != expectedRevision) {
                throw new McpConfigurationConflictException("revision conflict");
            }
            McpServerRecord updated = new McpServerRecord(name, config,
                    expectedRevision + 1L, current.getCreatedAt(), 2L,
                    McpServerConfigValidator.credentialConfigured(config.getAuth()));
            records.put(name, updated);
            return updated;
        }
        @Override public void delete(String name, long expectedRevision) { records.remove(name); }
    }

    /** 记录 Registry 实际使用的热更新配置。 */
    private static final class RecordingClient implements RemoteMcpClient {
        /** 收到 tools/list 的 Server 名称，由 URL 测试标识替代。 */
        private final List<String> listedServers = new ArrayList<String>();
        /** 最后一次 tools/list 使用的 Bearer token。 */
        private String lastToken;

        @Override
        public ListToolsResult listTools(McpServerConfig config) {
            listedServers.add("inventory");
            lastToken = config.getAuth().getToken();
            return new ListToolsResult(Collections.<RemoteToolDefinition>emptyList(), 5000L);
        }

        @Override
        public ToolCallResult callTool(McpServerConfig config, String remoteToolName,
                                       Map<String, Object> arguments,
                                       AiRequestContext requestContext) {
            throw new UnsupportedOperationException("test does not invoke tools");
        }
    }
}
