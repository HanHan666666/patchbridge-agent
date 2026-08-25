package io.patchbridge.agent.demo.sql;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.patchbridge.agent.mcp.McpServerConfig;
import io.patchbridge.agent.mcp.McpServerConfigValidator;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 demo-mcp-seed.sql 的播种语义：只对空表写入、重复执行幂等、
 * 行内容与 JdbcMcpConfigurationStore.create 的写入路径逐字段一致，
 * 且能通过配置校验器（mapRecord 读取时会重新校验，坏行会阻断启动）。
 */
class DemoMcpSeedSqlTest {

    /** 建一个内存 H2 并按需执行 schema 与种子脚本。 */
    private DataSource freshDatabase(boolean withForeignRow) throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime()
                // DB_CLOSE_DELAY=-1：内存库默认随最后一个连接关闭而销毁，而断言阶段
                // 会另开连接读取，必须让库跨连接存活；库名带时间戳避免用例间串库。
                + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("agent-schema-h2.sql"));
            if (withForeignRow) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("INSERT INTO agent_mcp_server "
                            + "(server_name, url, transport, enabled, timeout_ms, cache_ttl_ms, "
                            + "auth_type, encrypted_credentials, tools_json, revision, created_at, updated_at) "
                            + "VALUES ('existing', 'https://existing.example.com/mcp', 'streamable-http', "
                            + "FALSE, 10000, 300000, 'none', NULL, '{}', 0, 0, 0)");
                }
            }
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("demo-mcp-seed.sql"));
        }
        return dataSource;
    }

    /** 读取唯一 mcd 行的关键列。 */
    private LinkedHashMap<String, Object> readMcdRow(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT url, transport, enabled, timeout_ms, "
                     + "cache_ttl_ms, auth_type, encrypted_credentials, tools_json, revision, "
                     + "created_at, updated_at FROM agent_mcp_server WHERE server_name = 'mcd'")) {
            if (!rs.next()) {
                return null;
            }
            LinkedHashMap<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("url", rs.getString("url"));
            row.put("transport", rs.getString("transport"));
            row.put("enabled", rs.getBoolean("enabled"));
            row.put("timeout_ms", rs.getLong("timeout_ms"));
            row.put("cache_ttl_ms", rs.getLong("cache_ttl_ms"));
            row.put("auth_type", rs.getString("auth_type"));
            row.put("encrypted_credentials", rs.getString("encrypted_credentials"));
            row.put("tools_json", rs.getString("tools_json"));
            row.put("revision", rs.getLong("revision"));
            row.put("created_at", rs.getLong("created_at"));
            row.put("updated_at", rs.getLong("updated_at"));
            return row;
        }
    }

    /** 统计配置行总数。 */
    private int rowCount(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM agent_mcp_server")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** 读取当前 generation。 */
    private long generation(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT generation FROM agent_mcp_config_generation WHERE id = 1")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void 空表播种无凭据停用的示例行且字段与代码路径一致() throws Exception {
        DataSource dataSource = freshDatabase(false);
        LinkedHashMap<String, Object> row = readMcdRow(dataSource);
        assertTrue(row != null, "空表应播种 mcd");
        assertEquals("https://mcp.mcd.cn", row.get("url"));
        assertEquals("streamable-http", row.get("transport"));
        assertEquals(Boolean.FALSE, row.get("enabled"), "凭据未配置前必须停用");
        assertEquals(10_000L, row.get("timeout_ms"));
        assertEquals(300_000L, row.get("cache_ttl_ms"));
        assertEquals("none", row.get("auth_type"));
        assertNull(row.get("encrypted_credentials"), "none 认证不得携带密文");
        assertEquals(0L, row.get("revision"), "与 store.create 一致的初始 revision");
        assertTrue((Long) row.get("created_at") > 1_000_000_000_000L, "时间戳应为毫秒纪元");
        assertEquals(1, rowCount(dataSource));
        assertEquals(1L, generation(dataSource), "播种应递增 generation");
    }

    @Test
    void 种子行能被校验器接受() throws Exception {
        DataSource dataSource = freshDatabase(false);
        LinkedHashMap<String, Object> row = readMcdRow(dataSource);
        McpServerConfig config = new McpServerConfig();
        config.setUrl((String) row.get("url"));
        config.setTransport((String) row.get("transport"));
        config.setEnabled((Boolean) row.get("enabled"));
        config.setTimeoutMs((Long) row.get("timeout_ms"));
        config.setCacheTtlMs((Long) row.get("cache_ttl_ms"));
        config.setTools(new ObjectMapper().readValue((String) row.get("tools_json"),
                McpServerConfig.ToolsFilter.class));
        // mapRecord 在每次读取时都会执行同一校验；这里等价复现，坏数据会在启动期立刻失败。
        McpServerConfigValidator.validate("mcd", config);
    }

    @Test
    void 重复执行脚本不产生第二行() throws Exception {
        DataSource dataSource = freshDatabase(false);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("demo-mcp-seed.sql"));
        }
        assertEquals(1, rowCount(dataSource), "守卫应保证幂等");
    }

    @Test
    void 表非空时不播种也不递增版本() throws Exception {
        DataSource dataSource = freshDatabase(true);
        assertNull(readMcdRow(dataSource), "已有配置时不得播种 mcd");
        assertEquals(1, rowCount(dataSource));
        assertEquals(0L, generation(dataSource), "未写入就不应触碰 generation");
    }
}