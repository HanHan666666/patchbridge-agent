package io.patchbridge.agent.starter.audit;

import io.patchbridge.agent.core.audit.AuditEvent;
import io.patchbridge.agent.core.audit.AuditInvocationType;
import io.patchbridge.agent.core.audit.AuditRedactor;
import io.patchbridge.agent.core.audit.AuditSink;
import io.patchbridge.agent.core.user.UserContext;
import io.patchbridge.agent.starter.PatchBridgeAgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * 审计事件组装器：为 Starter 的各端点提供统一的“记录一次调用”入口。
 *
 * <p>职责：
 * <ul>
 *   <li>生成无节点状态的 invocationId，并根据耗时还原调用开始时间；</li>
 *   <li>按 payload-mode 决定是否携带请求/响应摘要（full 才记录，metadata-only 只记元数据）；</li>
 *   <li>序列化 + 截断 + 交给 AuditRedactor 脱敏后落库。</li>
 * </ul>
 * 审计写入失败由 AuditSink 自己记录日志，这里不再中断业务。
 */
public class AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);
    private final AuditSink sink;
    private final AuditRedactor redactor;
    private final ObjectMapper objectMapper;
    private final PatchBridgeAgentProperties.Audit config;

    public AuditRecorder(AuditSink sink, AuditRedactor redactor, ObjectMapper objectMapper,
                         PatchBridgeAgentProperties.Audit config) {
        this.sink = sink;
        this.redactor = redactor;
        this.objectMapper = objectMapper;
        this.config = config;
    }

    /**
     * 记录一次模型转发或 Tool 调用。
     *
     * @param type MODEL / TOOL / MCP_TOOL
     * @param source MODEL / LOCAL / OPENAPI / MCP
     */
    public void record(String traceId, String conversationId, UserContext user,
                       AuditInvocationType type, String source, String name,
                       boolean success, String errorCode, String errorMessage,
                       long durationMs, Object requestPayload, Object responsePayload) {
        if (!config.isEnabled()) {
            return;
        }
        try {
            AuditEvent partial = AuditEvent.builder()
                    .traceId(traceId).conversationId(conversationId)
                    .userId(user == null ? null : user.getUserId())
                    .username(user == null ? null : user.getUsername())
                    .tenantId(user == null ? null : user.getTenantId())
                    .type(type).source(source).name(name)
                    .success(success).build();

            // record 在调用终止时执行，startedAt 必须回推到真实开始时间，
            // 多实例部署即可直接按数据库时间排序，不需要 JVM 内 trace 序号状态。
            long completedAt = System.currentTimeMillis();
            java.util.Date startedAt = new java.util.Date(
                    completedAt - Math.max(0L, durationMs));
            AuditEvent event = AuditEvent.builder()
                    .invocationId(newId())
                    .traceId(partial.getTraceId()).conversationId(partial.getConversationId())
                    .userId(partial.getUserId()).username(partial.getUsername())
                    .tenantId(partial.getTenantId())
                    .type(type).source(source).name(name)
                    .success(success).errorCode(errorCode)
                    .errorMessage(truncate(errorMessage, 1024))
                    .startedAt(startedAt)
                    .durationMs(durationMs)
                    .requestSummary(summarize(partial, requestPayload))
                    .responseSummary(summarize(partial, responsePayload))
                    .build();
            sink.write(event);
        } catch (Exception e) {
            log.error("审计事件组装失败 traceId={} name={}", traceId, name, e);
        }
    }

    private String summarize(AuditEvent context, Object payload) {
        if (payload == null || !"full".equalsIgnoreCase(config.getPayloadMode())) {
            return null;
        }
        try {
            String serialized = payload instanceof String
                    ? (String) payload : objectMapper.writeValueAsString(payload);
            return redactor.redact(context, truncate(serialized, config.getSummaryMaxLength()));
        } catch (Exception e) {
            return "<unserializable>";
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
