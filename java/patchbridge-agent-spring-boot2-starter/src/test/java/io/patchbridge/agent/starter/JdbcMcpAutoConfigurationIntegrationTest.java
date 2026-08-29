package io.patchbridge.agent.starter;

import io.patchbridge.agent.mcp.McpConfigurationStore;
import io.patchbridge.agent.mcp.McpToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JDBC MCP 配置源的 Starter 装配顺序测试。
 *
 * <p>Registry 创建时会立即读取配置版本，因此必须确保 Boot SQL initializer
 * 先创建 agent_mcp_server 表。该测试使用空内存库启动完整上下文，
 * 防止只在手工启动 Demo 时才暴露初始化时序错误。
 */
@SpringBootTest(classes = PatchBridgeAgentStarterIntegrationTest.TestApp.class,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:mcpsourcetest;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:agent-schema-h2.sql",
                "patchbridge-agent.model.context-window-tokens=128000",
                "patchbridge-agent.mcp.source=jdbc",
                "patchbridge-agent.mcp.jdbc.encryption-key="
                        + "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
class JdbcMcpAutoConfigurationIntegrationTest {

    /** 装配后的单一 MCP 配置源。 */
    @Autowired
    private McpConfigurationStore store;

    /** 从 JDBC 配置源完成初始加载的 Registry。 */
    @Autowired
    private McpToolRegistry registry;

    /** 启动后应明确选中可变 JDBC 源，且空快照可正常读取。 */
    @Test
    void jdbcSourceStartsAfterSchemaInitialization() {
        assertEquals("jdbc", store.source());
        assertTrue(store.mutable());
        // 单行 generation 版本源（二次审计 Q-06）：O(1) 读取，不再扫描配置行
        assertTrue(store.version().startsWith("gen:"));
        assertTrue(registry.serverViews().isEmpty());
    }
}
