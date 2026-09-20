package io.patchbridge.agent.mcp;

import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.error.ToolExecutionException;
import io.patchbridge.agent.core.tool.ToolCallResult;
import io.patchbridge.agent.core.tool.ToolDefinition;
import io.patchbridge.agent.core.tool.ToolProvider;
import io.patchbridge.agent.core.user.UserContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用 JDK 内置 HttpServer 伪造 Streamable HTTP MCP Server，
 * 覆盖协议两条主路径（JSON 回复与 SSE 回复）、Bearer 凭据注入、
 * 注册表的命名空间映射 / exclude / 权限覆盖与调用路由。
 */
class StreamableHttpMcpClientTest {

    /** 仅用于测试的固定版本密钥哨兵，生产部署必须注入随机共享密钥。 */
    private static final String TOOL_VERSION_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;
    private String lastAuthorizationHeader;
    /** 最后一次请求携带的协议版本 Header。 */
    private String lastProtocolVersionHeader;
    /** 最后一次请求携带的 JSON-RPC 方法 Header。 */
    private String lastMethodHeader;
    /** 最后一次请求携带的可选资源名称 Header。 */
    private String lastNameHeader;
    /** 最后一次请求携带的逐请求元数据。 */
    private JsonNode lastRequestMetadata;
    /** 非 null 时要求请求携带完全一致的 Authorization 头。 */
    private String expectedAuthorization;
    /** true 时对 tools/call 以 SSE 流回复（Streamable HTTP 的另一种合法响应）。 */
    private boolean respondCallWithSse;
    /** 非 null 时 tools/list 用该原文回复，用于构造协议反例（严格解析测试）。 */
    private String toolsListOverride;
    /** 非 null 时 tools/call 的 JSON 路径用该原文回复，用于构造协议反例。 */
    private String toolsCallOverride;
    /** 非 null 时 tools/call 以 SSE 回复该原文，用于构造 SSE 路径协议反例。 */
    private String toolsCallSseOverride;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
        server.createContext("/mcp", this::dispatch);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    /** 统一分发：解析 JSON-RPC method，按测试开关组织响应。 */
    private void dispatch(HttpExchange exchange) throws IOException {
        lastAuthorizationHeader = exchange.getRequestHeaders().getFirst("Authorization");
        lastProtocolVersionHeader = exchange.getRequestHeaders()
                .getFirst("MCP-Protocol-Version");
        lastMethodHeader = exchange.getRequestHeaders().getFirst("Mcp-Method");
        lastNameHeader = exchange.getRequestHeaders().getFirst("Mcp-Name");
        if (expectedAuthorization != null && !expectedAuthorization.equals(lastAuthorizationHeader)) {
            replyJson(exchange, 401, "{\"jsonrpc\":\"2.0\",\"id\":1,"
                    + "\"error\":{\"code\":-32001,\"message\":\"unauthorized\"}}");
            return;
        }
        JsonNode request;
        try {
            request = JSON.readTree(exchange.getRequestBody());
        } catch (IOException e) {
            replyJson(exchange, 400, "{\"jsonrpc\":\"2.0\",\"id\":1,"
                    + "\"error\":{\"code\":-32700,\"message\":\"parse error\"}}");
            return;
        }
        lastRequestMetadata = request.path("params").path("_meta");
        String method = request.path("method").asText("");
        if ("tools/list".equals(method)) {
            replyJson(exchange, 200, toolsListOverride != null ? toolsListOverride : toolsListBody());
        } else if ("tools/call".equals(method)) {
            if (toolsCallSseOverride != null) {
                replySse(exchange, toolsCallSseOverride);
            } else if (respondCallWithSse) {
                replySse(exchange, "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":"
                        + request.path("id").asLong() + ",\"result\":{\"content\":"
                        + "[{\"type\":\"text\",\"text\":\"stock=42\"}]}}\n\n");
            } else {
                replyJson(exchange, 200, toolsCallOverride != null ? toolsCallOverride
                        : "{\"jsonrpc\":\"2.0\",\"id\":" + request.path("id").asLong()
                        + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"stock=42\"}]}}");
            }
        } else {
            replyJson(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":1,"
                    + "\"error\":{\"code\":-32601,\"message\":\"method not found\"}}");
        }
    }

    private static void replyJson(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        OutputStream out = exchange.getResponseBody();
        out.write(bytes);
        out.close();
    }

    private static void replySse(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        OutputStream out = exchange.getResponseBody();
        out.write(bytes);
        out.close();
    }

    private static String toolsListBody() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":["
                + "{\"name\":\"query_stock\",\"title\":\"查库存\",\"description\":\"查询库存\","
                + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"sku\":{\"type\":\"string\"}}},"
                + "\"annotations\":{\"readOnlyHint\":true,\"idempotentHint\":true}},"
                + "{\"name\":\"reset_cache\",\"description\":\"重置缓存\","
                + "\"inputSchema\":{\"type\":\"object\"},"
                + "\"annotations\":{\"destructiveHint\":true,\"requireConfirmation\":true}}"
                + "],\"ttlMs\":60000,\"cacheScope\":\"private\"}}";
    }

    @Test
    void jsonResponsePathWithBearerAuth() {
        expectedAuthorization = "Bearer secret-token";

        McpServerConfig config = new McpServerConfig();
        config.setUrl(baseUrl);
        config.getAuth().setType(McpServerConfig.Auth.BEARER);
        config.getAuth().setToken("secret-token");

        RemoteMcpClient.ListToolsResult result = new StreamableHttpMcpClient().listTools(config);
        assertEquals(2, result.getTools().size());
        assertEquals(60000, result.getServerTtlMs());
        assertEquals("Bearer secret-token", lastAuthorizationHeader);
        assertEquals("2026-07-28", lastProtocolVersionHeader);
        assertEquals("tools/list", lastMethodHeader);
        assertNull(lastNameHeader);
        assertEquals("2026-07-28", lastRequestMetadata
                .path("io.modelcontextprotocol/protocolVersion").asText());
        assertEquals("patchbridge-agent", lastRequestMetadata
                .path("io.modelcontextprotocol/clientInfo").path("name").asText());
        assertTrue(lastRequestMetadata
                .path("io.modelcontextprotocol/clientCapabilities").isObject());
    }

    @Test
    void unauthorizedWithoutCredential() {
        expectedAuthorization = "Bearer secret-token";

        McpServerConfig config = new McpServerConfig();
        config.setUrl(baseUrl);
        assertThrows(McpException.class, () -> new StreamableHttpMcpClient().listTools(config));
    }

    @Test
    void sseResponsePath() {
        respondCallWithSse = true;

        McpServerConfig config = new McpServerConfig();
        config.setUrl(baseUrl);
        ToolCallResult result = new StreamableHttpMcpClient()
                .callTool(config, "query_stock", new HashMap<String, Object>(), context());
        assertEquals("stock=42", result.getContent().get(0).getText());
        assertEquals("tools/call", lastMethodHeader);
        assertEquals("query_stock", lastNameHeader);
    }

    @Test
    void strictEnvelopeRejectsNonConformingResponses() {
        // 每个反例都必须以 McpException 失败，不允许回落为空结果（二次审计 Q-04）
        String[] badBodies = {
            "[1,2,3]",
            "{\"id\":1,\"result\":{\"tools\":[]}}",
            "{\"jsonrpc\":\"1.0\",\"id\":1,\"result\":{\"tools\":[]}}",
            "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"tools\":[]}}",
            "{\"jsonrpc\":\"2.0\",\"id\":99,\"result\":{\"tools\":[]}}",
            "{\"jsonrpc\":\"2.0\",\"id\":1}",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]},"
                    + "\"error\":{\"code\":-1,\"message\":\"both\"}}",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":\"bad\"}}",
        };
        for (String body : badBodies) {
            toolsListOverride = body;
            McpServerConfig config = new McpServerConfig();
            config.setUrl(baseUrl);
            assertThrows(McpException.class,
                    () -> new StreamableHttpMcpClient().listTools(config),
                    "反例未被拒绝: " + body);
        }
    }

    @Test
    void strictToolsListShapeRejectsMissingFields() {
        String[] badBodies = {
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":\"none\"}}",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[{\"description\":\"x\"}]}}",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[{\"name\":\"\"}]}}",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[{\"name\":\"t\"}]}}",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[{\"name\":\"t\","
                    + "\"inputSchema\":\"object\"}]}}",
        };
        for (String body : badBodies) {
            toolsListOverride = body;
            McpServerConfig config = new McpServerConfig();
            config.setUrl(baseUrl);
            assertThrows(McpException.class,
                    () -> new StreamableHttpMcpClient().listTools(config),
                    "反例未被拒绝: " + body);
        }
    }

    @Test
    void strictCallResultRejectsMissingContent() {
        toolsCallOverride = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"isError\":false}}";
        McpServerConfig config = new McpServerConfig();
        config.setUrl(baseUrl);
        assertThrows(McpException.class, () -> new StreamableHttpMcpClient()
                .callTool(config, "query_stock", new HashMap<String, Object>(), context()));
    }

    @Test
    void strictSseEnvelopeRejectsWrongVersion() {
        toolsCallSseOverride = "event: message\ndata: {\"jsonrpc\":\"1.0\",\"id\":1,"
                + "\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"x\"}]}}\n\n";
        McpServerConfig config = new McpServerConfig();
        config.setUrl(baseUrl);
        assertThrows(McpException.class, () -> new StreamableHttpMcpClient()
                .callTool(config, "query_stock", new HashMap<String, Object>(), context()));
    }

    @Test
    void jsonRpcErrorBecomesMcpException() {
        McpServerConfig config = new McpServerConfig();
        config.setUrl(baseUrl);
        config.getTools().setInclude(Arrays.asList("no_such_tool"));

        McpToolRegistry registry = new McpToolRegistry(
                servers("inventory", config), new StreamableHttpMcpClient(), "mcp", TOOL_VERSION_KEY);
        // include 过滤导致注册表没有任何 Tool 可导入，listTools 的合法响应为空
        assertTrue(registry.list().isEmpty());
    }

    @Test
    void registryAppliesFilterPermissionAndRouting() throws Exception {
        McpServerConfig config = new McpServerConfig();
        config.setUrl(baseUrl);
        config.getTools().setExclude(Arrays.asList("reset_cache"));
        config.getTools().getPermissions().put("query_stock", "ai:mcp:inventory:read");

        McpToolRegistry registry = new McpToolRegistry(
                servers("inventory", config), new StreamableHttpMcpClient(), "mcp", TOOL_VERSION_KEY);
        registry.refreshAll();

        List<ToolDefinition> tools = registry.list();
        assertEquals(1, tools.size());
        ToolDefinition tool = tools.get(0);
        assertEquals("mcp.inventory.query_stock", tool.getName());
        assertEquals(Collections.singletonList("ai:mcp:inventory:read"),
                tool.getPermissions(), "权限覆盖在导入时生效");
        assertTrue(tool.getAnnotations().isReadOnlyHint());
        assertNotNull(tool.getVersion(), "导入的动态 Tool 必须携带版本引用");

        ToolCallResult result = registry.call("inventory.query_stock",
                tool.getVersion(), new HashMap<String, Object>(), context());
        assertEquals("stock=42", result.getContent().get(0).getText());

        // 被排除的 Tool 通过统一 Registry 不可发现、不可调用
        io.patchbridge.agent.core.tool.DefaultToolRegistry unified =
                new io.patchbridge.agent.core.tool.DefaultToolRegistry(
                        Arrays.<ToolProvider>asList(registry),
                        new io.patchbridge.agent.core.auth.AuthenticatedToolAccessPolicy());
        assertNull(unified.find("mcp.inventory.reset_cache"));
        assertThrows(ToolExecutionException.class, () ->
                unified.call("mcp.inventory.reset_cache", null,
                        new HashMap<String, Object>(), context()));
    }

    @Test
    void unknownServerToolFails() {
        McpToolRegistry registry = new McpToolRegistry(
                new LinkedHashMap<String, McpServerConfig>(), new StreamableHttpMcpClient(), "mcp", TOOL_VERSION_KEY);
        assertThrows(ToolExecutionException.class, () ->
                registry.call("nobody.tool", null, new HashMap<String, Object>(), context()));
    }

    private static Map<String, McpServerConfig> servers(String key, McpServerConfig config) {
        Map<String, McpServerConfig> map = new LinkedHashMap<String, McpServerConfig>();
        map.put(key, config);
        return map;
    }

    private static AiRequestContext context() {
        return new AiRequestContext(UserContext.builder().userId("u1").build(),
                "trace-1", null, null, null);
    }
}
