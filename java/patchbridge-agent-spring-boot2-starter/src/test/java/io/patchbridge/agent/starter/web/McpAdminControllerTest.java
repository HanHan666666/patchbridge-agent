package io.patchbridge.agent.starter.web;

import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.tool.ToolCallResult;
import io.patchbridge.agent.mcp.McpConfigurationManager;
import io.patchbridge.agent.mcp.McpServerConfig;
import io.patchbridge.agent.mcp.McpToolRegistry;
import io.patchbridge.agent.mcp.RemoteMcpClient;
import io.patchbridge.agent.mcp.RemoteToolDefinition;
import io.patchbridge.agent.storage.jdbc.AesGcmMcpCredentialCipher;
import io.patchbridge.agent.storage.jdbc.JdbcMcpConfigurationStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP Admin API 的凭据只写边界测试。
 *
 * <p>通过真实 JDBC Store 创建 Bearer 配置，然后序列化创建响应与列表响应，
 * 确保 Controller 没有把内部 McpServerRecord 直接交给 Jackson。
 */
class McpAdminControllerTest {

    /** 仅用于测试的固定版本密钥哨兵，生产部署必须注入随机共享密钥。 */
    private static final String TOOL_VERSION_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    /** 被测 Admin Controller。 */
    private McpAdminController controller;

    /** 响应序列化器。 */
    private ObjectMapper objectMapper;

    /** 初始化独立 H2 库、密码器、Registry 和 Controller。 */
    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:mcp-admin-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("agent-schema-h2.sql"));
        }
        objectMapper = new ObjectMapper();
        String key = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        JdbcMcpConfigurationStore store = new JdbcMcpConfigurationStore(
                dataSource, objectMapper, new AesGcmMcpCredentialCipher(key));
        McpToolRegistry registry = new McpToolRegistry(store, new EmptyRemoteClient(), "mcp", TOOL_VERSION_KEY);
        controller = new McpAdminController(registry,
                new McpConfigurationManager(store, registry));
    }

    /** 创建与列表响应只包含 authType/credentialConfigured，不包含 token 字段或值。 */
    @Test
    void credentialsAreWriteOnlyAcrossAllResponses() throws Exception {
        McpAdminController.ServerWriteRequest request =
                new McpAdminController.ServerWriteRequest();
        request.setName("inventory");
        request.setUrl("https://mcp.example.test/mcp");
        request.getAuth().setType(McpServerConfig.Auth.BEARER);
        request.getAuth().setToken("response-secret-token");

        String created = objectMapper.writeValueAsString(
                controller.create(request).getBody());
        String listed = objectMapper.writeValueAsString(controller.servers());

        assertTrue(created.contains("credentialConfigured"));
        assertTrue(listed.contains("\"authType\":\"bearer\""));
        assertFalse(created.contains("response-secret-token"));
        assertFalse(listed.contains("response-secret-token"));
        assertFalse(created.contains("\"token\""));
        assertFalse(listed.contains("\"token\""));
    }

    /** 反序列化辅助：以真实 Jackson 路径构造 DTO，验证严格契约。 */
    private McpAdminController.ServerWriteRequest parseWriteRequest(String json) throws Exception {
        return objectMapper.readValue(json, McpAdminController.ServerWriteRequest.class);
    }

    /** 顶层未知字段在创建/更新校验阶段被拒绝。 */
    @Test
    void serverWriteRequestRejectsUnknownTopLevelFields() throws Exception {
        McpAdminController.ServerWriteRequest request = parseWriteRequest(
                "{\"name\":\"inventory\",\"url\":\"https://mcp.example.test/mcp\","
                        + "\"auth\":{\"type\":\"none\"},\"unexpected\":true}");

        assertThrows(IllegalArgumentException.class, request::validateCreate);
        assertThrows(IllegalArgumentException.class, () -> request.validateUpdate("inventory"));
    }

    /** 显式 null 的 auth/tools 是协议违规，不得被替换为默认对象。 */
    @Test
    void serverWriteRequestRejectsExplicitNullAuthAndTools() throws Exception {
        McpAdminController.ServerWriteRequest nullAuth = parseWriteRequest(
                "{\"name\":\"inventory\",\"url\":\"https://mcp.example.test/mcp\",\"auth\":null}");
        McpAdminController.ServerWriteRequest nullTools = parseWriteRequest(
                "{\"name\":\"inventory\",\"url\":\"https://mcp.example.test/mcp\",\"tools\":null}");

        assertThrows(IllegalArgumentException.class, nullAuth::validateCreate);
        assertThrows(IllegalArgumentException.class, nullTools::validateCreate);
    }

    /** 创建请求携带 revision / credentialUpdate 必须失败；更新请求 name 不一致必须失败。 */
    @Test
    void serverWriteRequestEnforcesCreateAndUpdateScoping() throws Exception {
        McpAdminController.ServerWriteRequest createWithRevision = parseWriteRequest(
                "{\"name\":\"inventory\",\"url\":\"https://mcp.example.test/mcp\","
                        + "\"auth\":{\"type\":\"none\"},\"revision\":0}");
        McpAdminController.ServerWriteRequest createWithCredentialUpdate = parseWriteRequest(
                "{\"name\":\"inventory\",\"url\":\"https://mcp.example.test/mcp\","
                        + "\"auth\":{\"type\":\"none\"},\"credentialUpdate\":\"KEEP\"}");
        McpAdminController.ServerWriteRequest mismatchedName = parseWriteRequest(
                "{\"name\":\"other\",\"url\":\"https://mcp.example.test/mcp\","
                        + "\"auth\":{\"type\":\"none\"},\"revision\":0,"
                        + "\"credentialUpdate\":\"KEEP\"}");

        assertThrows(IllegalArgumentException.class, createWithRevision::validateCreate);
        assertThrows(IllegalArgumentException.class, createWithCredentialUpdate::validateCreate);
        assertThrows(IllegalArgumentException.class, () -> mismatchedName.validateUpdate("inventory"));
    }

    /** 嵌套 auth/tools 的未知字段由 Validator 统一拒绝（经 copy 保留证据）。 */
    @Test
    void serverWriteRequestRejectsUnknownNestedFieldsThroughValidator() throws Exception {
        McpAdminController.ServerWriteRequest unknownAuthField = parseWriteRequest(
                "{\"name\":\"inventory\",\"url\":\"https://mcp.example.test/mcp\","
                        + "\"auth\":{\"type\":\"none\",\"tokenHint\":\"x\"}}");
        McpAdminController.ServerWriteRequest unknownToolsField = parseWriteRequest(
                "{\"name\":\"inventory\",\"url\":\"https://mcp.example.test/mcp\","
                        + "\"auth\":{\"type\":\"none\"},\"tools\":{\"rename\":{\"a\":\"b\"}}}");

        // Controller 侧先通过 DTO 校验，再由 Manager 的 Validator 拒绝嵌套未知字段
        assertThrows(IllegalArgumentException.class, () ->
                controller.create(unknownAuthField));
        assertThrows(IllegalArgumentException.class, () ->
                controller.create(unknownToolsField));
    }

    /** enabled 必须显式提供，未知字段被拒绝。 */
    @Test
    void enabledRequestRequiresExplicitEnabledAndRejectsUnknownFields() throws Exception {
        McpAdminController.EnabledRequest missingEnabled = objectMapper.readValue(
                "{\"revision\":0}", McpAdminController.EnabledRequest.class);
        McpAdminController.EnabledRequest unknownField = objectMapper.readValue(
                "{\"enabled\":true,\"revision\":0,\"force\":true}",
                McpAdminController.EnabledRequest.class);

        assertThrows(IllegalArgumentException.class, missingEnabled::validate);
        assertThrows(IllegalArgumentException.class, unknownField::validate);
    }

    /** 本测试不发起远程请求，因此 Client 仅提供空发现实现。 */
    private static final class EmptyRemoteClient implements RemoteMcpClient {
        /** 返回空 Tool 集。 */
        @Override
        public ListToolsResult listTools(McpServerConfig config) {
            return new ListToolsResult(Collections.<RemoteToolDefinition>emptyList(), 5000L);
        }

        /** 该测试禁止发起 Tool 调用。 */
        @Override
        public ToolCallResult callTool(McpServerConfig config, String remoteToolName,
                                       Map<String, Object> arguments,
                                       AiRequestContext requestContext) {
            throw new AssertionError("test must not call remote tool");
        }
    }
}
