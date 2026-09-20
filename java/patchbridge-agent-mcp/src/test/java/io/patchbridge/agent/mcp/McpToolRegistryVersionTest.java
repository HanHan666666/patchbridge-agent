package io.patchbridge.agent.mcp;

import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.error.ToolExecutionException;
import io.patchbridge.agent.core.error.ToolVersionMismatchException;
import io.patchbridge.agent.core.tool.ToolCallResult;
import io.patchbridge.agent.core.tool.ToolDefinition;
import io.patchbridge.agent.core.user.UserContext;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VA-01 回归：MCP Tool 的定义/路由版本引用必须贯穿发现与调用。
 *
 * <p>对应审查报告第 5 节的最小复现：同一 server key 在定义取得后切换 endpoint，
 * 携带旧版本引用的调用必须明确失败，不能把旧语义执行到新目标；无配置变化时
 * 版本保持稳定且跨实例一致；工具下线或被排除后旧调用同样明确失败。
 */
class McpToolRegistryVersionTest {

    /** 仅用于测试的固定版本密钥哨兵，生产部署必须注入随机共享密钥。 */
    private static final String TOOL_VERSION_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private final AiRequestContext ctx = new AiRequestContext(
            UserContext.builder().userId("u1").build(), "trace-1", null, null, null);

    /** 创建只指向给定地址、含单个 action 工具的 server 配置。 */
    private static McpServerConfig config(String url) {
        McpServerConfig config = new McpServerConfig();
        config.setUrl(url);
        return config;
    }

    /** 创建单 Server 配置集。 */
    private static Map<String, McpServerConfig> servers(McpServerConfig config) {
        Map<String, McpServerConfig> configs = new LinkedHashMap<String, McpServerConfig>();
        configs.put("srv", config);
        return configs;
    }

    /**
     * 审查复现序列：发现（A 地址）→ 配置更新（B 地址）→ 旧引用调用。
     * 旧引用必须被版本校验拒绝；新引用按新配置路由并触达 B 地址。
     */
    @Test
    void staleVersionCannotExecuteNewTargetAfterEndpointUpdate() throws Exception {
        RecordingClient client = new RecordingClient();
        McpToolRegistry registry = new McpToolRegistry(
                servers(config("https://server-a.example/mcp")), client, "mcp", TOOL_VERSION_KEY);
        registry.refreshAll();
        ToolDefinition discovered = registry.list().get(0);
        String staleVersion = discovered.getVersion();
        assertNotNull(staleVersion);

        registry.replaceAll(servers(config("https://server-b.example/mcp")));
        registry.refreshAll();
        ToolDefinition updated = registry.list().get(0);

        assertThrows(ToolVersionMismatchException.class, () ->
                registry.call("srv.action", staleVersion,
                        Collections.<String, Object>emptyMap(), ctx));
        assertEquals(0, client.callCount.get(), "过期版本引用不得触达远程 Server");

        assertNotEquals(staleVersion, updated.getVersion(),
                "endpoint 变化必须产生新的定义/路由版本");
        registry.call("srv.action", updated.getVersion(),
                Collections.<String, Object>emptyMap(), ctx);
        assertEquals("https://server-b.example/mcp", client.lastConfigUrl.get(),
                "有效版本引用必须路由到当前配置目标");
    }

    /** 无配置变化时刷新不产生新版本；两个实例对同一配置算出同一版本引用。 */
    @Test
    void versionIsStableAndDeterministicAcrossInstances() {
        RecordingClient clientA = new RecordingClient();
        RecordingClient clientB = new RecordingClient();
        McpToolRegistry instanceA = new McpToolRegistry(
                servers(config("https://server-a.example/mcp")), clientA, "mcp", TOOL_VERSION_KEY);
        McpToolRegistry instanceB = new McpToolRegistry(
                servers(config("https://server-a.example/mcp")), clientB, "mcp", TOOL_VERSION_KEY);
        instanceA.refreshAll();
        instanceB.refreshAll();
        String versionA = instanceA.list().get(0).getVersion();
        String versionB = instanceB.list().get(0).getVersion();

        instanceA.refreshAll();
        assertEquals(versionA, instanceA.list().get(0).getVersion(),
                "配置与远端定义未变化时版本必须稳定");
        assertEquals(versionA, versionB,
                "相同配置的独立实例必须得到相同版本，多实例校验不依赖内存快照");
    }

    /** 凭据轮换改变目标身份：版本引用必须更新，旧引用不得再触达远程。 */
    @Test
    void credentialRotationProducesNewVersion() throws Exception {
        RecordingClient client = new RecordingClient();
        McpServerConfig withOldToken = config("https://server-a.example/mcp");
        withOldToken.getAuth().setType(McpServerConfig.Auth.BEARER);
        withOldToken.getAuth().setToken("old-token");
        McpToolRegistry registry = new McpToolRegistry(servers(withOldToken), client, "mcp", TOOL_VERSION_KEY);
        registry.refreshAll();
        String before = registry.list().get(0).getVersion();

        McpServerConfig withNewToken = config("https://server-a.example/mcp");
        withNewToken.getAuth().setType(McpServerConfig.Auth.BEARER);
        withNewToken.getAuth().setToken("new-token");
        registry.replaceAll(servers(withNewToken));
        registry.refreshAll();
        String after = registry.list().get(0).getVersion();

        assertNotEquals(before, after,
                "凭据决定目标身份，轮换凭据必须产生新的定义/路由版本");
        assertThrows(ToolVersionMismatchException.class, () ->
                registry.call("srv.action", before,
                        Collections.<String, Object>emptyMap(), ctx));
        assertEquals(0, client.callCount.get(), "旧身份的引用不得触达远程 Server");
        registry.call("srv.action", after, Collections.<String, Object>emptyMap(), ctx);
        assertEquals(1, client.callCount.get(), "新身份的引用按当前配置放行");
    }

    /** 审查复现：静态 Header 从租户 A 切到租户 B，旧引用必须失败而不是调到 B。 */
    @Test
    void staticHeaderTenantSwitchProducesNewVersion() {
        RecordingClient client = new RecordingClient();
        McpServerConfig tenantA = config("https://server-a.example/mcp");
        tenantA.getAuth().setType(McpServerConfig.Auth.STATIC_HEADERS);
        tenantA.getAuth().getHeaders().put("X-Tenant-Id", "tenant-a");
        McpToolRegistry registry = new McpToolRegistry(servers(tenantA), client, "mcp", TOOL_VERSION_KEY);
        registry.refreshAll();
        String staleVersion = registry.list().get(0).getVersion();

        McpServerConfig tenantB = config("https://server-a.example/mcp");
        tenantB.getAuth().setType(McpServerConfig.Auth.STATIC_HEADERS);
        tenantB.getAuth().getHeaders().put("X-Tenant-Id", "tenant-b");
        registry.replaceAll(servers(tenantB));
        registry.refreshAll();

        assertThrows(ToolVersionMismatchException.class, () ->
                registry.call("srv.action", staleVersion,
                        Collections.<String, Object>emptyMap(), ctx));
        assertEquals(0, client.callCount.get(), "租户 A 的旧引用不得把调用执行到租户 B");
    }

    /** Basic 认证的用户名变化同样属于目标身份变化，必须产生新版本。 */
    @Test
    void basicAuthUsernameChangeProducesNewVersion() {
        RecordingClient client = new RecordingClient();
        McpServerConfig userA = config("https://server-a.example/mcp");
        userA.getAuth().setType(McpServerConfig.Auth.BASIC);
        userA.getAuth().setUsername("user-a");
        userA.getAuth().setPassword("shared-secret");
        McpToolRegistry registry = new McpToolRegistry(servers(userA), client, "mcp", TOOL_VERSION_KEY);
        registry.refreshAll();
        String before = registry.list().get(0).getVersion();

        McpServerConfig userB = config("https://server-a.example/mcp");
        userB.getAuth().setType(McpServerConfig.Auth.BASIC);
        userB.getAuth().setUsername("user-b");
        userB.getAuth().setPassword("shared-secret");
        registry.replaceAll(servers(userB));
        registry.refreshAll();

        assertNotEquals(before, registry.list().get(0).getVersion(),
                "认证主体变化必须反映到版本引用");
    }

    /** 远端工具下线或被配置排除后，旧调用必须在快照查找处明确失败。 */
    @Test
    void removedToolFailsExplicitlyAtSnapshotLookup() {
        RecordingClient client = new RecordingClient();
        McpToolRegistry registry = new McpToolRegistry(
                servers(config("https://server-a.example/mcp")), client, "mcp", TOOL_VERSION_KEY);
        registry.refreshAll();
        String version = registry.list().get(0).getVersion();

        client.provideTool = false;
        registry.refreshAll();

        assertThrows(ToolExecutionException.class, () ->
                registry.call("srv.action", version,
                        Collections.<String, Object>emptyMap(), ctx));
        assertEquals(0, client.callCount.get());
    }

    /** MCP Tool 携带 null 版本引用属于协议违约，不得放行。 */
    @Test
    void nullVersionReferenceIsRejectedForDynamicTools() {
        RecordingClient client = new RecordingClient();
        McpToolRegistry registry = new McpToolRegistry(
                servers(config("https://server-a.example/mcp")), client, "mcp", TOOL_VERSION_KEY);
        registry.refreshAll();

        assertThrows(ToolVersionMismatchException.class, () ->
                registry.call("srv.action", null,
                        Collections.<String, Object>emptyMap(), ctx));
        assertEquals(0, client.callCount.get());
    }

    /** 远端 Schema 变化产生新版本：旧引用失败，新引用按新定义放行。 */
    @Test
    void remoteSchemaChangeProducesNewVersion() throws Exception {
        RecordingClient client = new RecordingClient();
        McpToolRegistry registry = new McpToolRegistry(
                servers(config("https://server-a.example/mcp")), client, "mcp", TOOL_VERSION_KEY);
        registry.refreshAll();
        String before = registry.list().get(0).getVersion();

        client.schemaVersion = 2;
        registry.refreshAll();
        String after = registry.list().get(0).getVersion();

        assertNotEquals(before, after, "远端定义的语义变化必须反映到版本引用");
        assertThrows(ToolVersionMismatchException.class, () ->
                registry.call("srv.action", before,
                        Collections.<String, Object>emptyMap(), ctx));
        registry.call("srv.action", after, Collections.<String, Object>emptyMap(), ctx);
        assertEquals(1, client.callCount.get());
    }

    /** 未知共享密钥时，即使猜中全部路由与密码也不能本地生成已发布版本；轮换使旧引用失效。 */
    @Test
    void sharedVersionKeyIsRequiredAndRotationInvalidatesReferences() {
        McpServerConfig config = config("https://server-a.example/mcp");
        config.getAuth().setType(McpServerConfig.Auth.BASIC);
        config.getAuth().setUsername("known-user");
        config.getAuth().setPassword("guessable-password");
        RecordingClient client = new RecordingClient();
        McpToolRegistry original = new McpToolRegistry(servers(config), client, "mcp", TOOL_VERSION_KEY);
        original.refreshAll();
        String originalVersion = original.list().get(0).getVersion();
        String otherKey = "YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=";
        McpToolRegistry rotated = new McpToolRegistry(servers(config), client, "mcp", otherKey);
        rotated.refreshAll();
        assertNotEquals(originalVersion, rotated.list().get(0).getVersion());
        assertThrows(ToolVersionMismatchException.class, () -> rotated.call(
                "srv.action", originalVersion, Collections.<String, Object>emptyMap(), ctx));
        assertEquals(0, client.callCount.get());
        for (String invalid : new String[] { null, "", "not-base64", "YWJj" }) {
            assertThrows(IllegalArgumentException.class,
                    () -> new McpToolRegistry(servers(config), client, "mcp", invalid));
        }
    }

    /** 带换行的定义不能伪造相邻字段边界；对象键顺序则不改变语义。 */
    @Test
    void canonicalEncodingSeparatesFieldBoundariesAndSortsObjectKeys() {
        McpToolVersions versions = new McpToolVersions(TOOL_VERSION_KEY);
        McpServerConfig config = config("https://server-a.example/mcp");
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("description", "参数");
        Map<String, Object> reversed = new LinkedHashMap<String, Object>();
        reversed.put("description", "参数");
        reversed.put("type", "object");
        String first = versions.versionOf("srv", config, "action", "a\ndescription=b", "c",
                schema, null, Collections.<String>emptyList());
        assertNotEquals(first, versions.versionOf("srv", config, "action", "a", "b\ndescription=c",
                schema, null, Collections.<String>emptyList()));
        assertEquals(first, versions.versionOf("srv", config, "action", "a\ndescription=b", "c",
                reversed, null, Collections.<String>emptyList()));
    }

    /** 可编程远程 Client：返回固定工具列表并记录实际收到的调用配置。 */
    private static final class RecordingClient implements RemoteMcpClient {
        /** 记录 tools/call 实际收到的目标地址。 */
        final AtomicReference<String> lastConfigUrl = new AtomicReference<String>();
        /** tools/call 成功次数。 */
        final AtomicInteger callCount = new AtomicInteger();
        /** 是否仍提供 action 工具；置为 false 模拟远端下线。 */
        boolean provideTool = true;
        /** Schema 版本号，用于模拟远端定义语义变化。 */
        int schemaVersion = 1;

        /** 返回单个 action 工具；Schema 随 schemaVersion 重建以模拟远端变化。 */
        @Override
        public ListToolsResult listTools(McpServerConfig config) {
            if (!provideTool) {
                return new ListToolsResult(
                        Collections.<RemoteToolDefinition>emptyList(), 0L);
            }
            Map<String, Object> schema = new LinkedHashMap<String, Object>();
            schema.put("type", "object");
            schema.put("description", "schema-v" + schemaVersion);
            return new ListToolsResult(Collections.singletonList(new RemoteToolDefinition(
                    "action", "action", "测试动作", schema,
                    true, false, true, false)), 0L);
        }

        /** 记录实际路由目标并返回成功结果。 */
        @Override
        public ToolCallResult callTool(McpServerConfig config, String remoteToolName,
                                       Map<String, Object> arguments, AiRequestContext context) {
            lastConfigUrl.set(config.getUrl());
            callCount.incrementAndGet();
            return ToolCallResult.ofText("ok");
        }
    }
}
