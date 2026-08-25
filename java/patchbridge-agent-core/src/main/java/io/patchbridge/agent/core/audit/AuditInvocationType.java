package io.patchbridge.agent.core.audit;

/**
 * 审计记录的调用类型：一次模型转发、一次本地/REST Tool 调用、一次 MCP 代理调用。
 */
public enum AuditInvocationType {

    MODEL,

    TOOL,

    MCP_TOOL
}
