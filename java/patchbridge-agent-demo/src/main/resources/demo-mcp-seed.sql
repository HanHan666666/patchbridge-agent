-- Demo 首启播种：麦当劳 MCP 示例配置。
--
-- 语义：仅当 agent_mcp_server 为空时写入一行"无凭据、默认停用"的示例；
-- 库非空绝不写入；用户删除全部配置后重启，空库按同一语义重新预置。
-- 该行为与 spring.sql.init 的每次启动执行配合，由 WHERE NOT EXISTS 守卫保证幂等。
--
-- Token 预留：认证固定 none（框架要求非 none 凭据必须一次性完整提供，
-- 因此数据中不预置任何令牌）；使用者到 /ai-admin → MCP 管理编辑 mcd，
-- 认证切 bearer 并经凭据 REPLACE 写入令牌后启用。
--
-- 行内容与 JdbcMcpConfigurationStore.create 的写入路径逐字段一致：
-- revision=0、none 认证不带密文、tools_json 为默认 ToolsFilter 序列化、
-- generation 在同一脚本内递增（多实例部署下其他实例可感知本次变更）。

-- 先递增 generation：守卫条件与插入语句相同，且必须在插入前评估空表状态。
UPDATE agent_mcp_config_generation SET generation = generation + 1
WHERE id = 1 AND NOT EXISTS (SELECT 1 FROM agent_mcp_server);

INSERT INTO agent_mcp_server
    (server_name, url, transport, enabled, timeout_ms, cache_ttl_ms,
     auth_type, encrypted_credentials, tools_json, revision, created_at, updated_at)
SELECT 'mcd', 'https://mcp.mcd.cn', 'streamable-http', FALSE, 10000, 300000,
       'none', NULL, '{"include":null,"exclude":null,"permissions":{}}', 0,
       DATEDIFF('millisecond', TIMESTAMP '1970-01-01 00:00:00', CURRENT_TIMESTAMP),
       DATEDIFF('millisecond', TIMESTAMP '1970-01-01 00:00:00', CURRENT_TIMESTAMP)
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM agent_mcp_server);