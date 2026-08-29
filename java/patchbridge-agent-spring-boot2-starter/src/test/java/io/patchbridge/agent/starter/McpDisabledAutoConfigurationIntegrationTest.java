package io.patchbridge.agent.starter;

import io.patchbridge.agent.mcp.McpConfigurationManager;
import io.patchbridge.agent.mcp.McpConfigurationStore;
import io.patchbridge.agent.mcp.McpCredentialCipher;
import io.patchbridge.agent.mcp.McpToolRegistry;
import io.patchbridge.agent.mcp.RemoteMcpClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * MCP 显式关闭时的装配边界测试。
 *
 * <p>mcp.enabled=false 必须让默认 RemoteMcpClient、properties/JDBC 配置源、
 * 密码器、Manager 与 MCP Admin Controller 全部不装配；尤其 source=jdbc 时
 * 不得因条件残留而要求 DataSource 或加密密钥。本测试刻意不提供
 * mcp.jdbc.encryption-key——若 cipher 被错误装配，启动会直接失败。
 */
@SpringBootTest(classes = PatchBridgeAgentStarterIntegrationTest.TestApp.class,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:mcpdisabledtest;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:agent-schema-h2.sql",
                "patchbridge-agent.mcp.enabled=false",
                "patchbridge-agent.mcp.source=jdbc",
                // 模型网关默认 Provider 是硬依赖，与本测试的 MCP 边界无关
                "patchbridge-agent.model.base-url=http://localhost:0/v1",
                "patchbridge-agent.model.model=fake-model",
                "patchbridge-agent.model.context-window-tokens=128000"
        })
class McpDisabledAutoConfigurationIntegrationTest {

    /** 用于按类型与按名称双重确认 Bean 缺失。 */
    @Autowired
    private ApplicationContext context;

    /** MCP 关闭后所有默认 MCP Bean 都不应存在，source=jdbc 也无需密钥即可启动。 */
    @Test
    void mcpDefaultBeansAbsentWhenDisabled() {
        assertFalse(context.containsBean("streamableHttpMcpClient"));
        assertFalse(context.containsBean("propertiesMcpConfigurationStore"));
        assertFalse(context.containsBean("mcpCredentialCipher"));
        assertFalse(context.containsBean("jdbcMcpConfigurationStore"));
        assertFalse(context.containsBean("mcpToolRegistry"));
        assertFalse(context.containsBean("mcpConfigurationManager"));
        assertFalse(context.containsBean("mcpAdminController"));

        assertFalse(context.getBeanProvider(RemoteMcpClient.class).getIfAvailable() != null);
        assertFalse(context.getBeanProvider(McpConfigurationStore.class).getIfAvailable() != null);
        assertFalse(context.getBeanProvider(McpCredentialCipher.class).getIfAvailable() != null);
        assertFalse(context.getBeanProvider(McpToolRegistry.class).getIfAvailable() != null);
        assertFalse(context.getBeanProvider(McpConfigurationManager.class).getIfAvailable() != null);
    }
}
