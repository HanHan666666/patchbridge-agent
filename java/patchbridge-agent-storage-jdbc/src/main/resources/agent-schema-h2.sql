-- PatchBridge Agent v0.1 H2 建表脚本（Demo / 开发默认）。
-- 会话与审计分表保存：Conversation 是可恢复的业务数据，
-- agent_invocation 是安全审计事实，两者生命周期与治理策略不同（设计文档 21.8 / 21.9）。

CREATE TABLE IF NOT EXISTS agent_conversation (
    conversation_id VARCHAR(64) PRIMARY KEY,
    owner_key       VARCHAR(512) NOT NULL,
    title           VARCHAR(256),
    revision        BIGINT NOT NULL DEFAULT 0,
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    model_state_json CLOB,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_agent_conv_owner ON agent_conversation (owner_key, updated_at);

CREATE TABLE IF NOT EXISTS agent_message (
    message_id      VARCHAR(64) PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    seq             INT NOT NULL,
    role            VARCHAR(16) NOT NULL,
    blocks_json     CLOB NOT NULL,
    created_at      TIMESTAMP NOT NULL,
    CONSTRAINT uq_agent_msg_seq UNIQUE (conversation_id, seq)
);
CREATE INDEX IF NOT EXISTS idx_agent_msg_conv ON agent_message (conversation_id);

CREATE TABLE IF NOT EXISTS agent_invocation (
    invocation_id   VARCHAR(64) PRIMARY KEY,
    trace_id        VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(64),
    user_id         VARCHAR(128),
    username        VARCHAR(128),
    tenant_id       VARCHAR(64),
    type            VARCHAR(16) NOT NULL,
    source          VARCHAR(16),
    name            VARCHAR(256),
    success         TINYINT NOT NULL,
    error_code      VARCHAR(64),
    error_message   VARCHAR(1024),
    started_at      TIMESTAMP(3) NOT NULL,
    duration_ms     BIGINT NOT NULL,
    input_tokens    BIGINT,
    output_tokens   BIGINT,
    request_summary CLOB,
    response_summary CLOB
);
CREATE INDEX IF NOT EXISTS idx_agent_inv_trace ON agent_invocation (trace_id, started_at);
CREATE INDEX IF NOT EXISTS idx_agent_inv_time ON agent_invocation (started_at);
CREATE INDEX IF NOT EXISTS idx_agent_inv_user ON agent_invocation (user_id, started_at);
CREATE INDEX IF NOT EXISTS idx_agent_inv_name ON agent_invocation (name);

-- Global MCP Server 配置。认证对象只允许以 AES-GCM 密文落库；
-- auth_type 仅用于脱敏展示和密文一致性校验，不包含任何凭据值。
CREATE TABLE IF NOT EXISTS agent_mcp_server (
    server_name           VARCHAR(64) PRIMARY KEY,
    url                   VARCHAR(2048) NOT NULL,
    transport             VARCHAR(32) NOT NULL,
    enabled               BOOLEAN NOT NULL,
    timeout_ms            BIGINT NOT NULL,
    cache_ttl_ms          BIGINT NOT NULL,
    auth_type             VARCHAR(32) NOT NULL,
    encrypted_credentials CLOB,
    tools_json            CLOB NOT NULL,
    revision              BIGINT NOT NULL DEFAULT 0,
    created_at            BIGINT NOT NULL,
    updated_at            BIGINT NOT NULL
);
-- MCP 配置版本源：单行 generation，由 create/update/delete 在同一事务内递增，
-- 稳态版本读取不扫描配置行、不触碰凭据密文（二次审计 Q-06）。
CREATE TABLE IF NOT EXISTS agent_mcp_config_generation (
    id          SMALLINT PRIMARY KEY,
    generation  BIGINT NOT NULL
);
INSERT INTO agent_mcp_config_generation (id, generation)
SELECT 1, 0 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM agent_mcp_config_generation WHERE id = 1);

