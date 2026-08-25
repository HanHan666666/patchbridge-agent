package io.patchbridge.agent.storage.jdbc;

import io.patchbridge.agent.core.audit.AuditEvent;
import io.patchbridge.agent.core.audit.AuditSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;

/**
 * AuditSink 的 JDBC 默认实现：一行事件对应 agent_invocation 一行。
 *
 * <p>审计写入失败不中断业务调用（用户不该因为审计库抖动而无法工作），
 * 但必须记入应用日志——审计失败静默等同于审计缺失。
 */
public class JdbcAuditSink implements AuditSink {

    private static final Logger log = LoggerFactory.getLogger(JdbcAuditSink.class);

    private final JdbcTemplate jdbc;

    public JdbcAuditSink(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void write(AuditEvent event) {
        try {
            jdbc.update("INSERT INTO agent_invocation (invocation_id, trace_id, conversation_id, "
                            + "user_id, username, tenant_id, type, source, name, success, "
                            + "error_code, error_message, started_at, duration_ms, input_tokens, "
                            + "output_tokens, request_summary, response_summary) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    event.getInvocationId(), event.getTraceId(), event.getConversationId(),
                    event.getUserId(), event.getUsername(), event.getTenantId(),
                    event.getType().name(), event.getSource(), event.getName(),
                    event.isSuccess() ? 1 : 0,
                    event.getErrorCode(), truncate(event.getErrorMessage(), 1024),
                    event.getStartedAt() == null ? null : new Timestamp(event.getStartedAt().getTime()),
                    event.getDurationMs(), event.getInputTokens(), event.getOutputTokens(),
                    event.getRequestSummary(), event.getResponseSummary());
        } catch (DataAccessException e) {
            log.error("审计事件写入失败 traceId={} type={} name={}",
                    event.getTraceId(), event.getType(), event.getName(), e);
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
