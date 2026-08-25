package io.patchbridge.agent.mcp;

import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.tool.ToolCallResult;
import io.patchbridge.agent.core.tool.ToolContent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP Streamable HTTP（2026-07-28）客户端实现。
 *
 * <p>协议行为：每个 JSON-RPC 请求独立 POST，Accept 同时声明 JSON 与 SSE；
 * 服务端可以回复单个 JSON，也可以回复只属于本请求的 SSE 流——两种都要能解析。
 * 旧的 HTTP+SSE（GET 长连接）Transport 已被官方弃用，v0.1 不实现。
 *
 * <p>工程取舍：HTTP / 连接池 / TLS 复用 OkHttp，JSON 复用 Jackson，
 * 自己只维护“很薄的 MCP 协议适配”，不引入官方 SDK 的 Reactor 依赖树。
 *
 * <p>响应解析采取严格模式（二次审计 Q-04）：JSON-RPC 信封必须声明 jsonrpc=2.0、
 * id 必须与请求一致、result 与 error 互斥且必居其一；tools/list 必须返回 tools 数组、
 * 每个 Tool 必须有非空 name 和对象 inputSchema；tools/call 必须返回 content 数组。
 * 任何违约都立即以 McpException 失败，不猜测、不降级为空结果或"空成功"。
 */
public class StreamableHttpMcpClient implements RemoteMcpClient {

    /** 当前 Adapter 明确锁定的无状态 MCP 协议版本。 */
    private static final String PROTOCOL_VERSION = "2026-07-28";

    /** 每个请求携带的客户端实现名称，仅用于远端可观察性。 */
    private static final String CLIENT_NAME = "patchbridge-agent";

    /** 每个请求携带的客户端实现版本。 */
    private static final String CLIENT_VERSION = "0.1.0";

    /** 线程安全的 JSON 编解码器。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** MCP Streamable HTTP 请求的媒体类型。 */
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");

    /** 共享连接池的基础客户端；单 Server 超时通过 newBuilder 派生。 */
    private final OkHttpClient baseClient;

    /** 进程内 JSON-RPC 请求 ID。 */
    private final AtomicLong rpcId = new AtomicLong(0);

    /** 创建共享连接池的默认客户端。 */
    public StreamableHttpMcpClient() {
        this.baseClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                // 读超时按各 Server 配置派生（newBuilder 共享连接池）
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public ListToolsResult listTools(McpServerConfig config) {
        ObjectNode params = JSON.createObjectNode();
        JsonNode result = rpc(config, "tools/list", params);
        return parseListToolsResult(result, config.getUrl());
    }

    @Override
    public ToolCallResult callTool(McpServerConfig config, String remoteToolName,
                                   Map<String, Object> arguments, AiRequestContext context) {
        ObjectNode params = JSON.createObjectNode();
        params.put("name", remoteToolName);
        params.set("arguments", JSON.valueToTree(
                arguments == null ? new java.util.LinkedHashMap<String, Object>() : arguments));
        JsonNode result = rpc(config, "tools/call", params);
        return parseCallResult(result, config.getUrl());
    }

    // ---------- JSON-RPC ----------

    private JsonNode rpc(McpServerConfig config, String method, ObjectNode params) {
        addRequestMetadata(params);
        ObjectNode request = JSON.createObjectNode();
        request.put("jsonrpc", "2.0");
        long id = rpcId.incrementAndGet();
        request.put("id", id);
        request.put("method", method);
        request.set("params", params);

        Request.Builder http = new Request.Builder()
                .url(config.getUrl())
                .post(RequestBody.create(JSON_MEDIA_TYPE, request.toString()))
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                .header("Mcp-Method", method);
        JsonNode requestName = params.get("name");
        if (requestName != null && requestName.isTextual()) {
            http.header("Mcp-Name", requestName.textValue());
        }
        applyAuth(config, http);

        OkHttpClient client = this.baseClient.newBuilder()
                .readTimeout(config.getTimeoutMs(), TimeUnit.MILLISECONDS)
                .build();
        try {
            Response response = client.newCall(http.build()).execute();
            try {
                if (!response.isSuccessful()) {
                    throw new McpException("MCP Server [" + config.getUrl() + "] HTTP "
                            + response.code());
                }
                String contentType = response.header("Content-Type", "");
                if (contentType.contains("text/event-stream")) {
                    return readJsonRpcFromSse(config, response, id);
                }
                return parseJsonRpcResponse(JSON.readTree(response.body().string()), id, config.getUrl());
            } finally {
                response.close();
            }
        } catch (IOException e) {
            throw new McpException("MCP Server [" + config.getUrl() + "] 连接失败: " + e.getMessage(), e);
        }
    }

    /**
     * 为每个无状态请求附加协议版本、客户端身份和能力声明。
     * 当前 Adapter 不处理服务端回调，因此能力对象保持为空。
     */
    private static void addRequestMetadata(ObjectNode params) {
        ObjectNode metadata = params.putObject("_meta");
        metadata.put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION);
        ObjectNode clientInfo = metadata.putObject("io.modelcontextprotocol/clientInfo");
        clientInfo.put("name", CLIENT_NAME);
        clientInfo.put("version", CLIENT_VERSION);
        metadata.set("io.modelcontextprotocol/clientCapabilities", JSON.createObjectNode());
    }

    /** 从 SSE 流读取属于本次请求 id 的 JSON-RPC 响应（支持多行 data 拼接）。 */
    private JsonNode readJsonRpcFromSse(McpServerConfig config, Response response, long id)
            throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8));
        StringBuilder dataBuffer = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                JsonNode message = takeSseData(dataBuffer, config);
                if (message != null && message.path("id").asLong(-1) == id) {
                    return parseJsonRpcResponse(message, id, config.getUrl());
                }
                dataBuffer.setLength(0);
            } else if (line.startsWith("data:")) {
                dataBuffer.append(line.substring(5).trim());
            }
        }
        JsonNode tail = takeSseData(dataBuffer, config);
        if (tail != null && tail.path("id").asLong(-1) == id) {
            return parseJsonRpcResponse(tail, id, config.getUrl());
        }
        throw new McpException("MCP Server [" + config.getUrl() + "] SSE 流中未找到 id=" + id + " 的响应");
    }

    private JsonNode takeSseData(StringBuilder dataBuffer, McpServerConfig config) {
        if (dataBuffer.length() == 0) {
            return null;
        }
        try {
            return JSON.readTree(dataBuffer.toString());
        } catch (IOException e) {
            throw new McpException("MCP Server [" + config.getUrl() + "] SSE data 不是合法 JSON", e);
        }
    }

    private JsonNode parseJsonRpcResponse(JsonNode message, long id, String serverUrl) {
        if (!message.isObject()) {
            throw new McpException("MCP Server [" + serverUrl + "] 响应不是 JSON 对象");
        }
        if (!"2.0".equals(message.path("jsonrpc").asText(null))) {
            throw new McpException("MCP Server [" + serverUrl + "] 响应缺少 jsonrpc=2.0 版本声明");
        }
        // 本客户端只发出数值 id；响应必须回带同值数值 id，字符串或漂移 id 视为违约。
        JsonNode responseId = message.path("id");
        if (!responseId.isNumber() || responseId.asLong() != id) {
            throw new McpException("MCP Server [" + serverUrl + "] 响应 id=" + responseId.toString()
                    + " 与请求 id=" + id + " 不匹配");
        }
        JsonNode error = message.get("error");
        JsonNode result = message.get("result");
        boolean hasError = error != null && !error.isNull();
        boolean hasResult = result != null && !result.isNull();
        if (hasError && hasResult) {
            throw new McpException("MCP Server [" + serverUrl
                    + "] 响应同时包含 result 与 error，违反 JSON-RPC 互斥约束");
        }
        if (hasError) {
            if (!error.isObject() || !error.path("code").isNumber()
                    || !error.path("message").isTextual()) {
                throw new McpException("MCP Server [" + serverUrl
                        + "] JSON-RPC error 对象缺少数值 code 或文本 message");
            }
            throw new McpException("MCP JSON-RPC error: " + error.path("code").asLong()
                    + " " + error.path("message").asText());
        }
        if (!hasResult) {
            throw new McpException("MCP Server [" + serverUrl + "] 响应缺少 result 或 error 字段");
        }
        return result;
    }

    // ---------- 结果解析 ----------

    @SuppressWarnings("unchecked")
    private ListToolsResult parseListToolsResult(JsonNode result, String serverUrl) {
        JsonNode toolsNode = result.path("tools");
        if (!toolsNode.isArray()) {
            throw new McpException("MCP Server [" + serverUrl + "] tools/list 结果缺少 tools 数组");
        }
        List<RemoteToolDefinition> tools = new ArrayList<RemoteToolDefinition>();
        int index = 0;
        for (JsonNode toolNode : toolsNode) {
            // name 与 inputSchema 是 MCP Tool 定义的必填字段；缺失即协议违约，不产生匿名 Tool。
            JsonNode name = toolNode.path("name");
            JsonNode schemaNode = toolNode.path("inputSchema");
            if (!name.isTextual() || name.textValue().isEmpty()) {
                throw new McpException("MCP Server [" + serverUrl + "] tools[" + index
                        + "] 缺少非空字符串 name");
            }
            if (!schemaNode.isObject()) {
                throw new McpException("MCP Server [" + serverUrl + "] tools[" + index
                        + "] 缺少对象形式的 inputSchema");
            }
            Map<String, Object> schema = JSON.convertValue(schemaNode, Map.class);
            JsonNode annotations = toolNode.path("annotations");
            tools.add(new RemoteToolDefinition(
                    name.textValue(),
                    toolNode.path("title").asText(null),
                    toolNode.path("description").asText(""),
                    schema,
                    annotations.path("readOnlyHint").asBoolean(false),
                    annotations.path("destructiveHint").asBoolean(false),
                    annotations.path("idempotentHint").asBoolean(false),
                    // requireConfirmation 非 MCP 标准注解，但部分企业 MCP Server 会自定义提供
                    annotations.path("requireConfirmation").asBoolean(false)));
            index += 1;
        }
        long ttlMs = result.path("ttlMs").asLong(0);
        return new ListToolsResult(tools, ttlMs);
    }

    private ToolCallResult parseCallResult(JsonNode result, String serverUrl) {
        JsonNode contentArray = result.path("content");
        if (!contentArray.isArray()) {
            // content 是 tools/call 结果的必填字段；缺失即协议违约，不允许降级为"空成功"。
            throw new McpException("MCP Server [" + serverUrl + "] tools/call 结果缺少 content 数组");
        }
        List<ToolContent> contents = new ArrayList<ToolContent>();
        for (JsonNode block : contentArray) {
            if ("text".equals(block.path("type").asText())) {
                contents.add(ToolContent.text(block.path("text").asText("")));
            } else {
                // v0.1 模型侧只消费文本：非文本块以 JSON 形式透出结构，不丢弃事实
                contents.add(ToolContent.text(block.toString()));
            }
        }
        boolean isError = result.path("isError").asBoolean(false);
        return new ToolCallResult(contents, isError);
    }

    // ---------- 凭据注入（不透传浏览器身份） ----------

    private void applyAuth(McpServerConfig config, Request.Builder http) {
        McpServerConfig.Auth auth = config.getAuth();
        String type = auth.getType() == null ? McpServerConfig.Auth.NONE : auth.getType();
        if (McpServerConfig.Auth.BASIC.equals(type)) {
            String credential = auth.getUsername() + ":" + auth.getPassword();
            http.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString(credential.getBytes(StandardCharsets.UTF_8)));
        } else if (McpServerConfig.Auth.BEARER.equals(type)) {
            http.header("Authorization", "Bearer " + auth.getToken());
        } else if (McpServerConfig.Auth.API_KEY_HEADER.equals(type)) {
            http.header(auth.getHeaderName(), auth.getToken());
        } else if (McpServerConfig.Auth.STATIC_HEADERS.equals(type)) {
            for (Map.Entry<String, String> entry : auth.getHeaders().entrySet()) {
                http.header(entry.getKey(), entry.getValue());
            }
        }
    }
}
