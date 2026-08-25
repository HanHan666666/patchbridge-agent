package io.patchbridge.agent.core.audit;

/**
 * 审计统计聚合（Admin 总览页）。
 *
 * <p>token 用量“可获得时”才有值：v0.1 模型网关对 SSE 做原样转发，
 * 不解析模型输出，因此 token 列默认为空，属于已声明的边界而不是缺陷。
 */
public final class AuditStats {

    private final long totalInvocations;
    private final long modelCalls;
    private final long toolCalls;
    private final long mcpToolCalls;
    private final long successCount;
    private final long failedCount;
    private final long p50DurationMs;
    private final long p95DurationMs;
    private final Long totalInputTokens;
    private final Long totalOutputTokens;

    public AuditStats(long totalInvocations, long modelCalls, long toolCalls, long mcpToolCalls,
                      long successCount, long failedCount, long p50DurationMs, long p95DurationMs,
                      Long totalInputTokens, Long totalOutputTokens) {
        this.totalInvocations = totalInvocations;
        this.modelCalls = modelCalls;
        this.toolCalls = toolCalls;
        this.mcpToolCalls = mcpToolCalls;
        this.successCount = successCount;
        this.failedCount = failedCount;
        this.p50DurationMs = p50DurationMs;
        this.p95DurationMs = p95DurationMs;
        this.totalInputTokens = totalInputTokens;
        this.totalOutputTokens = totalOutputTokens;
    }

    public long getTotalInvocations() { return totalInvocations; }
    public long getModelCalls() { return modelCalls; }
    public long getToolCalls() { return toolCalls; }
    public long getMcpToolCalls() { return mcpToolCalls; }
    public long getSuccessCount() { return successCount; }
    public long getFailedCount() { return failedCount; }
    public long getP50DurationMs() { return p50DurationMs; }
    public long getP95DurationMs() { return p95DurationMs; }
    public Long getTotalInputTokens() { return totalInputTokens; }
    public Long getTotalOutputTokens() { return totalOutputTokens; }
}
