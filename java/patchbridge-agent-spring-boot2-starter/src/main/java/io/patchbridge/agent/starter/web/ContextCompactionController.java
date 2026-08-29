package io.patchbridge.agent.starter.web;

import io.patchbridge.agent.core.audit.AuditInvocationType;
import io.patchbridge.agent.core.auth.CurrentUserProvider;
import io.patchbridge.agent.core.compaction.ContextCompactionInvocation;
import io.patchbridge.agent.core.compaction.ContextCompactionProvider;
import io.patchbridge.agent.core.compaction.ContextCompactionRequest;
import io.patchbridge.agent.core.compaction.ContextCompactionResult;
import io.patchbridge.agent.core.compaction.ContextCompactionSettings;
import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.error.AgentErrorCode;
import io.patchbridge.agent.core.model.ModelState;
import io.patchbridge.agent.core.model.ModelUsage;
import io.patchbridge.agent.core.user.UserContext;
import io.patchbridge.agent.starter.audit.AuditRecorder;
import io.patchbridge.agent.starter.web.dto.ContextCompactionEnvelope;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模型上下文配置与压缩 HTTP Adapter。
 *
 * <p>GET 只公开窗口派生值，不公开凭据或 Provider 配置；POST 使用当前登录用户和当前
 * ModelGateway 生成摘要。Servlet 断开、超时或错误会取消同一次真实模型调用。
 */
@RestController
@ConditionalOnProperty(
        prefix = "patchbridge-agent",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequestMapping("${patchbridge-agent.base-path:/ai}")
public class ContextCompactionController {

    /** 服务端唯一窗口配置。 */
    private final ContextCompactionSettings settings;
    /** 当前模型摘要应用端口。 */
    private final ContextCompactionProvider provider;
    /** 当前可信用户解析器。 */
    private final CurrentUserResolver currentUser;
    /** 压缩调用审计入口。 */
    private final AuditRecorder audit;
    /** 审计使用的当前默认模型名。 */
    private final String defaultModel;

    /** 创建上下文压缩 Controller。 */
    public ContextCompactionController(
            ContextCompactionSettings settings,
            ContextCompactionProvider provider,
            CurrentUserProvider userProvider,
            AuditRecorder audit,
            String defaultModel) {
        this.settings = settings;
        this.provider = provider;
        this.currentUser = new CurrentUserResolver(userProvider);
        this.audit = audit;
        this.defaultModel = defaultModel;
    }

    /** 返回 Browser 自动和手动压缩共用的模型窗口参数。 */
    @GetMapping(value = "/model/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> configuration() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("contextWindowTokens", settings.getContextWindowTokens());
        body.put("automaticThresholdTokens", settings.getAutomaticThresholdTokens());
        body.put("keepRecentTokens", settings.getKeepRecentTokens());
        return body;
    }

    /** 启动异步摘要调用并返回与 Servlet 生命周期绑定的结果。 */
    @PostMapping(
            value = "/model/compact",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public DeferredResult<Map<String, Object>> compact(
            @RequestBody ContextCompactionEnvelope envelope) {
        if (envelope == null) {
            throw new IllegalArgumentException("缺少上下文压缩请求体");
        }
        UserContext user = currentUser.requiredUser();
        ContextCompactionRequest request = envelope.toDomain();
        String traceId = blankToRandom(envelope.getTraceId());
        AiRequestContext context =
                new AiRequestContext(
                        user, traceId, null, null, envelope.getConversationId());
        long startedAt = System.currentTimeMillis();
        ContextCompactionInvocation invocation;
        try {
            invocation = provider.compact(request, context);
        } catch (RuntimeException e) {
            // 状态投影或自定义 Provider 可以在返回异步句柄前同步失败；该路径同样是一笔
            // 已发生的压缩模型意图，必须进入同一审计入口，不能只审计异步失败。
            recordAudit(
                    traceId,
                    envelope.getConversationId(),
                    user,
                    request,
                    startedAt,
                    false,
                    AgentErrorCode.MODEL_FAILED,
                    e.getMessage());
            throw e;
        }
        DeferredResult<Map<String, Object>> deferred = new DeferredResult<Map<String, Object>>(0L);
        AtomicBoolean terminal = new AtomicBoolean(false);

        deferred.onTimeout(() -> cancel(invocation, terminal));
        deferred.onError(error -> cancel(invocation, terminal));
        deferred.onCompletion(() -> cancel(invocation, terminal));
        invocation.result().whenComplete(
                (result, failure) -> {
                    if (!terminal.compareAndSet(false, true)) {
                        return;
                    }
                    if (failure != null) {
                        Throwable cause = unwrap(failure);
                        recordAudit(
                                traceId,
                                envelope.getConversationId(),
                                user,
                                request,
                                startedAt,
                                false,
                                AgentErrorCode.MODEL_FAILED,
                                cause.getMessage());
                        deferred.setErrorResult(cause);
                        return;
                    }
                    recordAudit(
                            traceId,
                            envelope.getConversationId(),
                            user,
                            request,
                            startedAt,
                            true,
                            null,
                            null);
                    deferred.setResult(toView(result));
                });
        return deferred;
    }

    /** 只让第一个 Servlet 终止信号取消真实上游。 */
    private static void cancel(
            ContextCompactionInvocation invocation, AtomicBoolean terminal) {
        if (terminal.compareAndSet(false, true)) {
            invocation.cancel();
        }
    }

    /** 把 Core 结果转换为 Browser 精确协议。 */
    private static Map<String, Object> toView(ContextCompactionResult result) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("summary", result.getSummary());
        ModelUsage usage = result.getUsage();
        Map<String, Object> usageView = new LinkedHashMap<String, Object>();
        usageView.put("inputTokens", usage.getInputTokens());
        usageView.put("outputTokens", usage.getOutputTokens());
        usageView.put("totalTokens", usage.getTotalTokens());
        body.put("usage", usageView);
        ModelState state = result.getModelState();
        if (state == null) {
            body.put("modelState", null);
        } else {
            Map<String, Object> stateView = new LinkedHashMap<String, Object>();
            stateView.put("format", state.getFormat());
            stateView.put("data", state.getData());
            body.put("modelState", stateView);
        }
        return body;
    }

    /** 写入一次上下文压缩模型调用审计。 */
    private void recordAudit(
            String traceId,
            String conversationId,
            UserContext user,
            ContextCompactionRequest request,
            long startedAt,
            boolean success,
            String errorCode,
            String errorMessage) {
        audit.record(
                traceId,
                conversationId,
                user,
                AuditInvocationType.COMPACTION,
                "MODEL",
                defaultModel,
                success,
                errorCode,
                errorMessage,
                System.currentTimeMillis() - startedAt,
                Integer.valueOf(request.getMessagesToSummarize().size()),
                null);
    }

    /** CompletionStage 包装异常不属于对外错误语义。 */
    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
    }

    /** 缺失 traceId 时生成服务端链路标识。 */
    private static String blankToRandom(String value) {
        return value == null || value.trim().isEmpty()
                ? UUID.randomUUID().toString().replace("-", "")
                : value;
    }
}
