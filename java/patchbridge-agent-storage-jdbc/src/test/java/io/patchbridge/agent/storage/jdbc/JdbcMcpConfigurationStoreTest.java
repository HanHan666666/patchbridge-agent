package io.patchbridge.agent.storage.jdbc;

import io.patchbridge.agent.mcp.McpConfigurationConflictException;
import io.patchbridge.agent.mcp.McpServerConfig;
import io.patchbridge.agent.mcp.McpServerRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JDBC MCP 配置源的安全存储、乐观锁与恢复测试。
 *
 * <p>测试不只校验对象往返，还直接读取数据库列，确保 token
 * 未出现在任何明文持久化字段中。
 */
class JdbcMcpConfigurationStoreTest {

    /** 每个测试独立的 H2 数据源。 */
    private DataSource dataSource;

    /** 被测 JDBC 配置源。 */
    private JdbcMcpConfigurationStore store;

    /** 创建随机内存库并执行公共 H2 schema。 */
    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:mcp-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        this.dataSource = ds;
        try (Connection connection = ds.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("agent-schema-h2.sql"));
        }
        byte[] key = "0123456789abcdef0123456789abcdef" // gitleaks:allow 固定测试密钥，仅验证加密存储
                .getBytes(StandardCharsets.UTF_8);
        this.store = new JdbcMcpConfigurationStore(ds, new ObjectMapper(),
                new AesGcmMcpCredentialCipher(Base64.getEncoder().encodeToString(key)));
    }

    /** 凭据应可恢复给运行时，但不得以明文出现在表中。 */
    @Test
    void credentialsAreEncryptedAtRestAndRestoredForRuntime() {
        McpServerConfig config = bearerConfig("secret-token-value");

        McpServerRecord created = store.create("inventory", config);
        McpServerRecord restored = store.find("inventory");

        assertEquals(0L, created.getRevision());
        assertTrue(restored.isCredentialConfigured());
        assertEquals("secret-token-value", restored.getConfig().getAuth().getToken());

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String ciphertext = jdbc.queryForObject(
                "SELECT encrypted_credentials FROM agent_mcp_server WHERE server_name = ?",
                String.class, "inventory");
        String rowText = jdbc.queryForObject(
                "SELECT url || auth_type || tools_json || encrypted_credentials "
                        + "FROM agent_mcp_server WHERE server_name = ?",
                String.class, "inventory");
        assertNotEquals("secret-token-value", ciphertext);
        assertFalse(rowText.contains("secret-token-value"));
    }

    /** 旧 revision 不能覆盖或删除更新后的配置。 */
    @Test
    void revisionPreventsLostUpdatesAndDeletes() {
        McpServerRecord created = store.create("inventory", bearerConfig("first"));
        McpServerConfig changed = created.getConfig();
        changed.setCacheTtlMs(6000L);

        McpServerRecord updated = store.update("inventory", changed, created.getRevision());

        assertEquals(1L, updated.getRevision());
        assertThrows(McpConfigurationConflictException.class,
                () -> store.update("inventory", changed, created.getRevision()));
        assertThrows(McpConfigurationConflictException.class,
                () -> store.delete("inventory", created.getRevision()));
        store.delete("inventory", updated.getRevision());
        assertEquals(null, store.find("inventory"));
    }

    /** GCM AAD 应阻止将一个 Server 的密文复制给另一个 Server。 */
    @Test
    void encryptedCredentialIsBoundToServerName() {
        store.create("inventory", bearerConfig("inventory-token"));
        store.create("ops", bearerConfig("ops-token"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String inventoryCiphertext = jdbc.queryForObject(
                "SELECT encrypted_credentials FROM agent_mcp_server WHERE server_name = ?",
                String.class, "inventory");
        jdbc.update("UPDATE agent_mcp_server SET encrypted_credentials = ? WHERE server_name = ?",
                inventoryCiphertext, "ops");

        assertThrows(IllegalStateException.class, () -> store.find("ops"));
    }

    /** 稳态版本稳定；每次成功变更（含同毫秒删除后创建）都产生新版本。 */
    @Test
    void versionIsStableWithoutMutationsAndChangesOnEveryMutation() {
        assertEquals(store.version(), store.version(), "无变更时版本必须稳定");

        store.create("inventory", bearerConfig("inventory-token"));
        String afterCreate = store.version();
        assertNotEquals("gen:0", afterCreate);

        store.create("ops", bearerConfig("ops-token"));
        String afterSecondCreate = store.version();
        assertNotEquals(afterCreate, afterSecondCreate);

        McpServerRecord record = store.find("inventory");
        store.update("inventory", bearerConfig("rotated"), record.getRevision());
        assertNotEquals(afterSecondCreate, store.version());

        store.delete("ops", 0L);
        String afterDelete = store.version();
        assertNotEquals(afterSecondCreate, afterDelete);
    }

    /** 失败的变更（revision 冲突）必须整体回滚，不允许只留下 generation 递增。 */
    @Test
    void failedMutationDoesNotBumpVersion() {
        McpServerRecord created = store.create("inventory", bearerConfig("inventory-token"));
        String before = store.version();

        assertThrows(McpConfigurationConflictException.class, () ->
                store.update("inventory", bearerConfig("stale"), created.getRevision() + 100));
        assertThrows(McpConfigurationConflictException.class, () ->
                store.delete("inventory", created.getRevision() + 100));

        assertEquals(before, store.version(), "冲突失败的变更不得改变配置版本");
        assertEquals("inventory-token", store.find("inventory").getConfig().getAuth().getToken());
    }

    /** 创建一个具备最小有效 Bearer 认证的 Streamable HTTP 配置。 */
    private static McpServerConfig bearerConfig(String token) {
        McpServerConfig config = new McpServerConfig();
        config.setUrl("https://mcp.example.test/mcp");
        config.setCacheTtlMs(5000L);
        config.getAuth().setType(McpServerConfig.Auth.BEARER);
        config.getAuth().setToken(token);
        return config;
    }
}
