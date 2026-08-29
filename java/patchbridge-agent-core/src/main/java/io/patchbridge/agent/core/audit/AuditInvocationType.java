package io.patchbridge.agent.core.audit;

/**
 * 审计记录的调用类型：模型转发、上下文压缩、本地/REST Tool 或 MCP 代理调用。
 */
public enum AuditInvocationType {

    MODEL,

    /** 使用当前模型生成上下文检查点。 */
    COMPACTION,

    TOOL,

    MCP_TOOL
}
