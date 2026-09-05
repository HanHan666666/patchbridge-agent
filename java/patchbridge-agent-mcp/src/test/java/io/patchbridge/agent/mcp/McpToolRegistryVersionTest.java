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
                servers(config("https://server-a.example/mcp")), client, "mcp");
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
                servers(config("https://server-a.example/mcp")), clientA, "mcp");
        McpToolRegistry instanceB = new McpToolRegistry(
                servers(config("https://server-a.example/mcp")), clientB, "mcp");
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

    /** 凭据轮换不改变调用语义：版本引用保持不变，鉴权由远程 Server 把关。 */
    @Test
    void credentialRotationDoesNotInvalidateVersion() {
        RecordingClient client = new RecordingClient();
        McpServerConfig withOldToken = config("https://server-a.example/mcp");
        withOldToken.getAuth().setType(McpServerConfig.Auth.BEARER);
        withOldToken.getAuth().setToken("old-token");
        McpToolRegistry registry = new McpToolRegistry(servers(withOldToken), client, "mcp");
        registry.refreshAll();
        String before = registry.list().get(0).getVersion();

        McpServerConfig withNewToken = config("https://server-a.example/mcp");
        withNewToken.getAuth().setType(McpServerConfig.Auth.BEARER);
        withNewToken.getAuth().setToken("new-token");
        registry.replaceAll(servers(withNewToken));
        registry.refreshAll();

        assertEquals(before, registry.list().get(0).getVersion(),
                "凭据值不参与版本计算，轮换凭据不应打散进行中的调用引用");
    }

    /** 远端工具下线或被配置排除后，旧调用必须在快照查找处明确失败。 */
    @Test
    void removedToolFailsExplicitlyAtSnapshotLookup() {
        RecordingClient client = new RecordingClient();
        McpToolRegistry registry = new McpToolRegistry(
                servers(config("https://server-a.example/mcp")), client, "mcp");
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
                servers(config("https://server-a.example/mcp")), client, "mcp");
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
                servers(config("https://server-a.example/mcp")), client, "mcp");
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
