package io.patchbridge.agent.storage.jdbc;

import io.patchbridge.agent.mcp.McpConfigurationConflictException;
import io.patchbridge.agent.mcp.McpConfigurationStore;
import io.patchbridge.agent.mcp.McpCredentialCipher;
import io.patchbridge.agent.mcp.McpException;
import io.patchbridge.agent.mcp.McpServerConfig;
import io.patchbridge.agent.mcp.McpServerConfigValidator;
import io.patchbridge.agent.mcp.McpServerRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Global MCP Server 配置的 JDBC 持久化 Adapter。
 *
 * <p>URL、超时、Tool 筛选等非敏感运行字段直接存储；认证对象整体以
 * AES-GCM 密文存储。查询结果只在服务端内存中解密成运行配置，
 * Admin Controller 不能直接序列化本 Adapter 返回的 Record。
 */
public final class JdbcMcpConfigurationStore implements McpConfigurationStore {

    /** 执行参数化 SQL 的 Spring JDBC 入口。 */
    private final JdbcTemplate jdbc;

    /** 复用宿主 Jackson 配置序列化 Tool 规则和认证对象。 */
    private final ObjectMapper objectMapper;

    /** 将认证对象与 Server 名称绑定加密的端口。 */
    private final McpCredentialCipher cipher;

    /**
     * 配置写入事务模板：数据变更与 generation 递增必须在同一事务内提交，
     * 保证“版本变化”与“数据变化”严格同步（二次审计 Q-06）。
     */
    private final TransactionTemplate writes;

    /** 使用宿主 DataSource、ObjectMapper 和显式密码器创建 JDBC 配置源。 */
    public JdbcMcpConfigurationStore(DataSource dataSource, ObjectMapper objectMapper,
                                     McpCredentialCipher cipher) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.objectMapper = objectMapper;
        this.cipher = cipher;
        this.writes = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    /** 返回 JDBC 配置源标识。 */
    @Override
    public String source() { return "jdbc"; }

    /** JDBC Adapter 支持 Admin 在线变更。 */
    @Override
    public boolean mutable() { return true; }

    /**
     * O(1) 版本读取：单行 generation，稳态请求不再全表扫描配置行，
     * 也不再读取凭据密文（二次审计 Q-06）。
     *
     * <p>generation 由 create/update/delete 在同一事务内递增，任意两次变更
     * 产生不同版本，天然覆盖“同一毫秒删除后创建”等时间戳聚合碰撞场景。
     */
    @Override
    public String version() {
        return "gen:" + jdbc.queryForObject(
                "SELECT generation FROM agent_mcp_config_generation WHERE id = 1", Long.class);
    }

    /** 读取全部 Global Server，按名称排序以保证 Admin 与快照顺序稳定。 */
    @Override
    public List<McpServerRecord> findAll() {
        return jdbc.query("SELECT server_name, url, transport, enabled, timeout_ms, "
                        + "cache_ttl_ms, auth_type, encrypted_credentials, tools_json, "
                        + "revision, created_at, updated_at FROM agent_mcp_server ORDER BY server_name",
                (rs, rowNum) -> mapRecord(
                        rs.getString("server_name"), rs.getString("url"),
                        rs.getString("transport"), rs.getBoolean("enabled"),
                        rs.getLong("timeout_ms"), rs.getLong("cache_ttl_ms"),
                        rs.getString("auth_type"), rs.getString("encrypted_credentials"),
                        rs.getString("tools_json"), rs.getLong("revision"),
                        rs.getLong("created_at"), rs.getLong("updated_at")));
    }

    /** 按 Server 名称读取记录，不存在时返回 null。 */
    @Override
    public McpServerRecord find(String name) {
        List<McpServerRecord> rows = jdbc.query(
                "SELECT server_name, url, transport, enabled, timeout_ms, cache_ttl_ms, "
                        + "auth_type, encrypted_credentials, tools_json, revision, created_at, updated_at "
                        + "FROM agent_mcp_server WHERE server_name = ?",
                (rs, rowNum) -> mapRecord(
                        rs.getString("server_name"), rs.getString("url"),
                        rs.getString("transport"), rs.getBoolean("enabled"),
                        rs.getLong("timeout_ms"), rs.getLong("cache_ttl_ms"),
                        rs.getString("auth_type"), rs.getString("encrypted_credentials"),
                        rs.getString("tools_json"), rs.getLong("revision"),
                        rs.getLong("created_at"), rs.getLong("updated_at")), name);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 插入初始 revision=0 的配置，同名键冲突不做更新降级；generation 同事务递增。 */
    @Override
    public McpServerRecord create(String name, McpServerConfig config) {
        McpServerConfigValidator.validate(name, config);
        final long now = System.currentTimeMillis();
        final StoredAuth storedAuth = encodeAuth(name, config.getAuth());
        return writes.execute(tx -> {
            try {
                jdbc.update("INSERT INTO agent_mcp_server "
                                + "(server_name, url, transport, enabled, timeout_ms, cache_ttl_ms, "
                                + "auth_type, encrypted_credentials, tools_json, revision, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)",
                        name, config.getUrl(), config.getTransport(), config.isEnabled(),
                        config.getTimeoutMs(), config.getCacheTtlMs(), storedAuth.type,
                        storedAuth.ciphertext, writeJson(config.getTools()), now, now);
            } catch (DuplicateKeyException e) {
                throw new McpConfigurationConflictException("MCP Server 已存在: " + name);
            }
            bumpGeneration();
            return new McpServerRecord(name, config, 0L, now, now,
                    storedAuth.ciphertext != null);
        });
    }

    /** 使用 WHERE revision 完成单语句乐观锁更新；generation 同事务递增。 */
    @Override
    public McpServerRecord update(String name, McpServerConfig config, long expectedRevision) {
        McpServerConfigValidator.validate(name, config);
        final long now = System.currentTimeMillis();
        final StoredAuth storedAuth = encodeAuth(name, config.getAuth());
        return writes.execute(tx -> {
            int affected = jdbc.update("UPDATE agent_mcp_server SET url = ?, transport = ?, enabled = ?, "
                            + "timeout_ms = ?, cache_ttl_ms = ?, auth_type = ?, encrypted_credentials = ?, "
                            + "tools_json = ?, revision = revision + 1, updated_at = ? "
                            + "WHERE server_name = ? AND revision = ?",
                    config.getUrl(), config.getTransport(), config.isEnabled(),
                    config.getTimeoutMs(), config.getCacheTtlMs(), storedAuth.type,
                    storedAuth.ciphertext, writeJson(config.getTools()), now, name, expectedRevision);
            if (affected == 0) {
                throw conflict(name, expectedRevision);
            }
            McpServerRecord previous = find(name);
            if (previous == null) {
                throw new IllegalStateException("MCP Server 更新成功后无法读取: " + name);
            }
            bumpGeneration();
            return previous;
        });
    }

    /** 使用 revision 防止删除管理员尚未看到的新版配置；generation 同事务递增。 */
    @Override
    public void delete(String name, long expectedRevision) {
        writes.executeWithoutResult(tx -> {
            int affected = jdbc.update(
                    "DELETE FROM agent_mcp_server WHERE server_name = ? AND revision = ?",
                    name, expectedRevision);
            if (affected == 0) {
                throw conflict(name, expectedRevision);
            }
            bumpGeneration();
        });
    }

    /** 将查询列重建为已校验的运行配置记录。 */
    private McpServerRecord mapRecord(String name, String url, String transport,
                                      boolean enabled, long timeoutMs, long cacheTtlMs,
                                      String authType, String encryptedCredentials,
                                      String toolsJson, long revision,
                                      long createdAt, long updatedAt) {
        McpServerConfig config = new McpServerConfig();
        config.setUrl(url);
        config.setTransport(transport);
        config.setEnabled(enabled);
        config.setTimeoutMs(timeoutMs);
        config.setCacheTtlMs(cacheTtlMs);
        config.setAuth(decodeAuth(name, authType, encryptedCredentials));
        config.setTools(readJson(toolsJson, McpServerConfig.ToolsFilter.class));
        McpServerConfigValidator.validate(name, config);
        return new McpServerRecord(name, config, revision, createdAt, updatedAt,
                encryptedCredentials != null && !encryptedCredentials.isEmpty());
    }

    /** 将完整认证对象序列化并绑定 Server 名称加密。 */
    private StoredAuth encodeAuth(String name, McpServerConfig.Auth auth) {
        if (McpServerConfig.Auth.NONE.equals(auth.getType())) {
            return new StoredAuth(McpServerConfig.Auth.NONE, null);
        }
        return new StoredAuth(auth.getType(), cipher.encrypt(name, writeJson(auth)));
    }

    /** 解密认证对象，并校验明文 type 与非敏感索引列一致。 */
    private McpServerConfig.Auth decodeAuth(String name, String authType,
                                            String encryptedCredentials) {
        if (McpServerConfig.Auth.NONE.equals(authType)) {
            if (encryptedCredentials != null && !encryptedCredentials.isEmpty()) {
                throw new IllegalStateException("none 认证的 MCP Server 不应存在凭据密文: " + name);
            }
            return new McpServerConfig.Auth();
        }
        if (encryptedCredentials == null || encryptedCredentials.isEmpty()) {
            throw new IllegalStateException("MCP Server 缺少凭据密文: " + name);
        }
        McpServerConfig.Auth auth = readJson(cipher.decrypt(name, encryptedCredentials),
                McpServerConfig.Auth.class);
        if (!authType.equals(auth.getType())) {
            throw new IllegalStateException("MCP Server 凭据类型与存储索引不一致: " + name);
        }
        return auth;
    }

    /** 将存储对象严格序列化为 JSON。 */
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new McpException("MCP 配置序列化失败", e);
        }
    }

    /** 将数据库 JSON 严格还原为指定配置类型。 */
    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (IOException e) {
            throw new McpException("MCP 配置 JSON 无法解析", e);
        }
    }

    /** 根据当前数据区分“不存在”与“revision 已变更”。 */
    private McpConfigurationConflictException conflict(String name, long expectedRevision) {
        McpServerRecord current = find(name);
        if (current == null) {
            return new McpConfigurationConflictException("MCP Server 不存在: " + name);
        }
        return new McpConfigurationConflictException("MCP Server revision 冲突: " + name
                + "，期望 " + expectedRevision + "，当前 " + current.getRevision());
    }

    /** 在当前写事务内递增配置 generation；种子行缺失视为 schema 漂移，立即失败。 */
    private void bumpGeneration() {
        int affected = jdbc.update(
                "UPDATE agent_mcp_config_generation SET generation = generation + 1 WHERE id = 1");
        if (affected != 1) {
            throw new IllegalStateException(
                    "agent_mcp_config_generation 缺少种子行，请先执行最新 schema 初始化");
        }
    }

    /** 认证类型与凭据密文的存储参数对。 */
    private static final class StoredAuth {

        /** 可用于脱敏查询和一致性校验的认证类型。 */
        private final String type;

        /** 绑定 Server 名称的完整认证对象密文。 */
        private final String ciphertext;

        /** 组合单次 SQL 写入所需的认证字段。 */
        private StoredAuth(String type, String ciphertext) {
            this.type = type;
            this.ciphertext = ciphertext;
        }
    }
}
