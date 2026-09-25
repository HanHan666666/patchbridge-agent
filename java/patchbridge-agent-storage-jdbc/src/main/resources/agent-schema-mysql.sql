-- PatchBridge Agent v0.1 MySQL 建表脚本。
-- 与 agent-schema-h2.sql 字段一一对应，仅类型按 MySQL 习惯调整。

CREATE TABLE IF NOT EXISTS agent_conversation (
    conversation_id VARCHAR(64) PRIMARY KEY,
    owner_key       VARCHAR(512) NOT NULL,
    title           VARCHAR(256),
    revision        BIGINT NOT NULL DEFAULT 0,
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    model_target_json LONGTEXT NOT NULL,
    model_context_json LONGTEXT NOT NULL,
    created_at      DATETIME NOT NULL,
    updated_at      DATETIME NOT NULL,
    KEY idx_agent_conv_owner (owner_key, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS agent_message (
    message_id      VARCHAR(64) PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    seq             INT NOT NULL,
    role            VARCHAR(16) NOT NULL,
    blocks_json     LONGTEXT NOT NULL,
    created_at      DATETIME NOT NULL,
    UNIQUE KEY uq_agent_msg_seq (conversation_id, seq),
    KEY idx_agent_msg_conv (conversation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS agent_invocation (
    invocation_id    VARCHAR(64) PRIMARY KEY,
    trace_id         VARCHAR(64) NOT NULL,
    conversation_id  VARCHAR(64),
    user_id          VARCHAR(128),
    username         VARCHAR(128),
    tenant_id        VARCHAR(64),
    type             VARCHAR(16) NOT NULL,
    source           VARCHAR(16),
    name             VARCHAR(256),
    success          TINYINT NOT NULL,
    error_code       VARCHAR(64),
    error_message    VARCHAR(1024),
    started_at       DATETIME(3) NOT NULL,
    duration_ms      BIGINT NOT NULL,
    input_tokens     BIGINT,
    output_tokens    BIGINT,
    request_summary  LONGTEXT,
    response_summary LONGTEXT,
    KEY idx_agent_inv_trace (trace_id, started_at),
    KEY idx_agent_inv_time (started_at),
    KEY idx_agent_inv_user (user_id, started_at),
    KEY idx_agent_inv_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Global MCP Server 配置；凭据对象只以 AES-GCM 密文存储。
CREATE TABLE IF NOT EXISTS agent_mcp_server (
    server_name           VARCHAR(64) PRIMARY KEY,
    url                   VARCHAR(2048) NOT NULL,
    transport             VARCHAR(32) NOT NULL,
    enabled               TINYINT(1) NOT NULL,
    timeout_ms            BIGINT NOT NULL,
    cache_ttl_ms          BIGINT NOT NULL,
    auth_type             VARCHAR(32) NOT NULL,
    encrypted_credentials LONGTEXT,
    tools_json            LONGTEXT NOT NULL,
    revision              BIGINT NOT NULL DEFAULT 0,
    created_at            BIGINT NOT NULL,
    updated_at            BIGINT NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
-- MCP 配置版本源：单行 generation，由 create/update/delete 在同一事务内递增，
-- 稳态版本读取不扫描配置行、不触碰凭据密文（二次审计 Q-06）。
CREATE TABLE IF NOT EXISTS agent_mcp_config_generation (
    id          SMALLINT PRIMARY KEY,
    generation  BIGINT NOT NULL
);
INSERT INTO agent_mcp_config_generation (id, generation)
SELECT 1, 0 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM agent_mcp_config_generation WHERE id = 1);
