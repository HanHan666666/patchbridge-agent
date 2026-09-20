package io.patchbridge.agent.starter;

import io.patchbridge.agent.mcp.McpConfigurationStore;
import io.patchbridge.agent.mcp.McpServerConfig;
import io.patchbridge.agent.mcp.McpServerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 宿主自定义 MCP 配置源的自动装配让位测试。
 *
 * <p>source=jdbc 只表示默认 Adapter 选择；宿主已提供 McpConfigurationStore 时，
 * Starter 不得创建 AES 密码器或强制要求默认 JDBC encryption-key。
 */
@SpringBootTest(classes = {
        PatchBridgeAgentStarterIntegrationTest.TestApp.class,
        CustomMcpStoreBackoffIntegrationTest.CustomStoreConfig.class
}, properties = {
        "spring.datasource.url=jdbc:h2:mem:custommcpstore;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:agent-schema-h2.sql",
        "patchbridge-agent.model.context-window-tokens=128000",
        "patchbridge-agent.mcp.tool-version-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "patchbridge-agent.mcp.source=jdbc"
})
class CustomMcpStoreBackoffIntegrationTest {

    /** 宿主提供的配置源应保持为唯一实现。 */
    @Autowired
    private McpConfigurationStore store;

    /** 不配置默认 AES key 时上下文仍应成功启动。 */
    @Test
    void customStoreDoesNotRequireDefaultJdbcEncryptionKey() {
        assertEquals("custom", store.source());
    }

    /** 注册宿主自定义的空配置源。 */
    @Configuration
    static class CustomStoreConfig {

        /** 提供明确的宿主 Store Bean，触发 Starter 默认 JDBC/AES Adapter 让位。 */
        @Bean
        McpConfigurationStore customMcpConfigurationStore() {
            return new McpConfigurationStore() {
                @Override public String source() { return "custom"; }
                @Override public boolean mutable() { return false; }
                @Override public String version() { return "custom-v1"; }
                @Override public List<McpServerRecord> findAll() { return Collections.emptyList(); }
                @Override public McpServerRecord find(String name) { return null; }
                @Override public McpServerRecord create(String name, McpServerConfig config) {
                    throw new UnsupportedOperationException();
                }
                @Override public McpServerRecord update(String name, McpServerConfig config,
                                                        long expectedRevision) {
                    throw new UnsupportedOperationException();
                }
                @Override public void delete(String name, long expectedRevision) {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }
}
