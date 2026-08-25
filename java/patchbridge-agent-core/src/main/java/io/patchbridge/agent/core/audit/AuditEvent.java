package io.patchbridge.agent.core.audit;

import java.util.Date;

/**
 * 单条审计事件（对应 agent_invocation 一行）。
 *
 * <p>审计目标是回答：谁、何时、在哪个会话、调了什么（模型 / Tool / MCP Tool）、
 * 来自哪个来源、成功与否、耗时多少、挂在哪个 trace 上。
 *
 * <p>requestSummary / responseSummary 必须是脱敏后的摘要；
 * 框架不假设企业脱敏规则；序列化与有界截断由调用方完成，内容去敏交给
 * {@link AuditRedactor}。
 */
public final class AuditEvent {

    private final String invocationId;
    private final String traceId;
    private final String conversationId;
    private final String userId;
    private final String username;
    private final String tenantId;
    private final AuditInvocationType type;
    /** MODEL / LOCAL / OPENAPI / MCP —— Tool 来源或模型本身。 */
    private final String source;
    /** 模型名或 Tool 全名。 */
    private final String name;
    private final boolean success;
    private final String errorCode;
    private final String errorMessage;
    private final Date startedAt;
    private final long durationMs;
    private final Long inputTokens;
    private final Long outputTokens;
    private final String requestSummary;
    private final String responseSummary;

    private AuditEvent(Builder builder) {
        this.invocationId = builder.invocationId;
        this.traceId = builder.traceId;
        this.conversationId = builder.conversationId;
        this.userId = builder.userId;
        this.username = builder.username;
        this.tenantId = builder.tenantId;
        this.type = builder.type;
        this.source = builder.source;
        this.name = builder.name;
        this.success = builder.success;
        this.errorCode = builder.errorCode;
        this.errorMessage = builder.errorMessage;
        this.startedAt = copyDate(builder.startedAt);
        this.durationMs = builder.durationMs;
        this.inputTokens = builder.inputTokens;
        this.outputTokens = builder.outputTokens;
        this.requestSummary = builder.requestSummary;
        this.responseSummary = builder.responseSummary;
    }

    public String getInvocationId() {
        return invocationId;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public String getTenantId() {
        return tenantId;
    }

    public AuditInvocationType getType() {
        return type;
    }

    public String getSource() {
        return source;
    }

    public String getName() {
        return name;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Date getStartedAt() {
        return copyDate(startedAt);
    }

    public long getDurationMs() {
        return durationMs;
    }

    public Long getInputTokens() {
        return inputTokens;
    }

    public Long getOutputTokens() {
        return outputTokens;
    }

    public String getRequestSummary() {
        return requestSummary;
    }

    public String getResponseSummary() {
        return responseSummary;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Date 是可变类型，审计事件必须保留构建时的时间快照。 */
    private static Date copyDate(Date value) {
        return value == null ? null : new Date(value.getTime());
    }

    public static final class Builder {
        private String invocationId;
        private String traceId;
        private String conversationId;
        private String userId;
        private String username;
        private String tenantId;
        private AuditInvocationType type;
        private String source;
        private String name;
        private boolean success;
        private String errorCode;
        private String errorMessage;
        private Date startedAt;
        private long durationMs;
        private Long inputTokens;
        private Long outputTokens;
        private String requestSummary;
        private String responseSummary;

        public Builder invocationId(String v) { this.invocationId = v; return this; }
        public Builder traceId(String v) { this.traceId = v; return this; }
        public Builder conversationId(String v) { this.conversationId = v; return this; }
        public Builder userId(String v) { this.userId = v; return this; }
        public Builder username(String v) { this.username = v; return this; }
        public Builder tenantId(String v) { this.tenantId = v; return this; }
        public Builder type(AuditInvocationType v) { this.type = v; return this; }
        public Builder source(String v) { this.source = v; return this; }
        public Builder name(String v) { this.name = v; return this; }
        public Builder success(boolean v) { this.success = v; return this; }
        public Builder errorCode(String v) { this.errorCode = v; return this; }
        public Builder errorMessage(String v) { this.errorMessage = v; return this; }
        public Builder startedAt(Date v) { this.startedAt = v; return this; }
        public Builder durationMs(long v) { this.durationMs = v; return this; }
        public Builder inputTokens(Long v) { this.inputTokens = v; return this; }
        public Builder outputTokens(Long v) { this.outputTokens = v; return this; }
        public Builder requestSummary(String v) { this.requestSummary = v; return this; }
        public Builder responseSummary(String v) { this.responseSummary = v; return this; }

        public AuditEvent build() {
            return new AuditEvent(this);
        }
    }
}
