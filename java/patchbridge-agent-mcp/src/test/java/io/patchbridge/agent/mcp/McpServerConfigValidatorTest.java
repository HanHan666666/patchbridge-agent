package io.patchbridge.agent.mcp;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MCP Admin 输入的认证字段与 HTTP Header 边界测试。
 *
 * <p>重点覆盖会改变 OkHttp/MCP 协议语义的 Header，以及不同 auth.type
 * 之间的旧敏感字段，防止无效值被静默带入凭据密文。
 */
class McpServerConfigValidatorTest {

    /** API Key Header 不能覆盖 Host、Content-Type 或 Authorization 等受控字段。 */
    @Test
    void apiKeyRejectsProtocolControlledHeadersCaseInsensitively() {
        for (String header : new String[]{"Host", "content-length", "Transfer-Encoding",
                "Connection", "Accept", "Content-Type", "Mcp-Session-Id",
                "MCP-Protocol-Version", "Authorization"}) {
            McpServerConfig config = apiKeyConfig(header);
            assertThrows(IllegalArgumentException.class,
                    () -> McpServerConfigValidator.validate("inventory", config), header);
        }
    }

    /** Static Headers 同样不能越过协议和 auth.type 边界。 */
    @Test
    void staticHeadersRejectControlledNames() {
        McpServerConfig config = baseConfig();
        config.getAuth().setType(McpServerConfig.Auth.STATIC_HEADERS);
        config.getAuth().setHeaders(Collections.singletonMap("Cookie", "sid=secret"));
        assertThrows(IllegalArgumentException.class,
                () -> McpServerConfigValidator.validate("inventory", config));
    }

    /** 合法的业务 API Key Header 仍可使用。 */
    @Test
    void customApiKeyHeaderIsAccepted() {
        assertDoesNotThrow(() -> McpServerConfigValidator.validate(
                "inventory", apiKeyConfig("X-Enterprise-Api-Key")));
    }

    /** Bearer 类型不能夹带 basic/static-header 的旧凭据字段。 */
    @Test
    void authTypeRejectsUnrelatedSensitiveFields() {
        McpServerConfig config = baseConfig();
        config.getAuth().setType(McpServerConfig.Auth.BEARER);
        config.getAuth().setToken("bearer-token");
        config.getAuth().setPassword("stale-password");
        assertThrows(IllegalArgumentException.class,
                () -> McpServerConfigValidator.validate("inventory", config));
    }

    /** 创建一个只需填充认证字段的最小有效配置。 */
    private static McpServerConfig baseConfig() {
        McpServerConfig config = new McpServerConfig();
        config.setUrl("https://mcp.example.test/mcp");
        return config;
    }

    /** 创建指定 Header 名的 API Key 认证配置。 */
    private static McpServerConfig apiKeyConfig(String header) {
        McpServerConfig config = baseConfig();
        config.getAuth().setType(McpServerConfig.Auth.API_KEY_HEADER);
        config.getAuth().setHeaderName(header);
        config.getAuth().setToken("secret-key");
        return config;
    }
}
