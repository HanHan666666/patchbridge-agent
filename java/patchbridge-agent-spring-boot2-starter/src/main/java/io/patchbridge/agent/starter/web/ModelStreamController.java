package io.patchbridge.agent.starter.web;

import io.patchbridge.agent.core.audit.AuditInvocationType;
import io.patchbridge.agent.core.auth.CurrentUserProvider;
import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.error.AgentErrorCode;
import io.patchbridge.agent.core.error.ModelGatewayException;
import io.patchbridge.agent.core.invocation.ModelInvocationPipeline;
import io.patchbridge.agent.core.model.ModelBlockDeltaEvent;
import io.patchbridge.agent.core.model.ModelBlockStartEvent;
import io.patchbridge.agent.core.model.ModelBlockStopEvent;
import io.patchbridge.agent.core.model.ModelCall;
import io.patchbridge.agent.core.model.ModelMessageStopEvent;
import io.patchbridge.agent.core.model.ModelRequest;
import io.patchbridge.agent.core.model.ModelState;
import io.patchbridge.agent.core.model.ModelStreamEvent;
import io.patchbridge.agent.core.model.ModelStreamListener;
import io.patchbridge.agent.core.model.ModelUsage;
import io.patchbridge.agent.core.user.UserContext;
import io.patchbridge.agent.starter.audit.AuditRecorder;
import io.patchbridge.agent.starter.web.dto.ModelStreamEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Servlet MVC 模型流 Adapter：POST /ai/model/stream。
 *
 * <p>Controller 使用 SseEmitter 连接浏览器，核心 ModelProvider 使用异步回调连接上游， 因而不再为每条长模型流占用一个 MVC
 * 工作线程。客户端断开、超时和发送失败都会通过 ModelCall 取消上游连接。Provider 已把厂商流转换为 Core 结构化事件，Controller 只做 稳定浏览器 JSON
 * 序列化，因此任何厂商 chunk 都不会穿透到前端。
 *
 * <p>已开始的 SSE 无法改变 HTTP 状态码，异步失败以 {"type":"error","error":{"code","message","retryable"}} 数据帧通知浏览器。
 */
@RestController
@ConditionalOnProperty(
        prefix = "patchbridge-agent",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequestMapping("${patchbridge-agent.base-path:/ai}")
public class ModelStreamController {

    /** 模型网关日志；只记录截断摘要，不输出模型状态正文。 */
    private static final Logger log = LoggerFactory.getLogger(ModelStreamController.class);

    /** 统一执行拦截器、Provider 与取消生命周期的应用服务。 */
    private final ModelInvocationPipeline invocationPipeline;

    /** 把宿主认证态解析为可信用户上下文。 */
    private final CurrentUserResolver currentUser;

    /** 模型调用审计记录器。 */
    private final AuditRecorder audit;

    /** 请求未覆盖时用于审计的默认模型名称。 */
    private final String defaultModel;

    /** 只用于框架结构事件序列化的宿主 Jackson 配置。 */
    private final ObjectMapper objectMapper;

    /** 创建 MVC 流式 Adapter。 */
    public ModelStreamController(
            ModelInvocationPipeline invocationPipeline,
            CurrentUserProvider userProvider,
            AuditRecorder audit,
            String defaultModel,
            ObjectMapper objectMapper) {
        this.invocationPipeline = invocationPipeline;
        this.currentUser = new CurrentUserResolver(userProvider);
        this.audit = audit;
        this.defaultModel = defaultModel;
        this.objectMapper = objectMapper;
    }

    /** 校验请求后立即启动异步模型调用并返回 SSE Emitter。 */
    @PostMapping(
            value = "/model/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@RequestBody ModelStreamEnvelope envelope) {
        UserContext user = currentUser.requiredUser();
        if (envelope == null) {
            throw new IllegalArgumentException("模型请求缺少 request");
        }
        envelope.validate();

        String traceId = blankToRandom(envelope.getTraceId());
        String conversationId = envelope.getConversationId();
        ModelRequest request = toModelRequest(envelope.getRequest());
        String modelName = request.getModel() != null ? request.getModel() : defaultModel;
        AiRequestContext context = new AiRequestContext(user, traceId, null, null, conversationId);

        SseEmitter emitter = new SseEmitter(0L);
        StreamSession session =
                new StreamSession(emitter, traceId, conversationId, user, request, modelName);
        emitter.onCompletion(session::cancelFromDownstream);
        emitter.onTimeout(session::cancelFromDownstream);
        emitter.onError(error -> session.cancelFromDownstream());

        try {
            session.attach(invocationPipeline.stream(request, context, session));
        } catch (RuntimeException e) {
            session.onError(e);
        }

        return ResponseEntity.ok()
                .contentType(new MediaType("text", "event-stream", StandardCharsets.UTF_8))
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }

    /** 将浏览器协议 DTO 转换为厂商无关的 Core 请求。 */
    private ModelRequest toModelRequest(ModelStreamEnvelope.Request source) {
        return source.toModelRequest();
    }

    /** 把 Core 结构化事件序列化为唯一浏览器协议，明确排除所有厂商字段。 */
    private String eventFrame(ModelStreamEvent event) {
        try {
            Map<String, Object> frame = new LinkedHashMap<String, Object>();
            frame.put("type", event.getType().getWireValue());
            if (event instanceof ModelBlockStartEvent) {
                writeBlockStart(frame, (ModelBlockStartEvent) event);
            } else if (event instanceof ModelBlockDeltaEvent) {
                writeBlockDelta(frame, (ModelBlockDeltaEvent) event);
            } else if (event instanceof ModelBlockStopEvent) {
                frame.put("index", ((ModelBlockStopEvent) event).getIndex());
            } else if (event instanceof ModelMessageStopEvent) {
                writeMessageStop(frame, (ModelMessageStopEvent) event);
            } else {
                throw new ModelGatewayException("未知模型流事件类型: " + event.getClass().getName(), false);
            }
            return objectMapper.writeValueAsString(frame);
        } catch (Exception e) {
            if (e instanceof ModelGatewayException) {
                throw (ModelGatewayException) e;
            }
            throw new ModelGatewayException("模型事件序列化失败", e, false);
        }
    }

    /** 写入 block-start 的嵌套 block 描述，并省略不适用字段。 */
    private static void writeBlockStart(Map<String, Object> frame, ModelBlockStartEvent event) {
        frame.put("index", event.getIndex());
        Map<String, Object> block = new LinkedHashMap<String, Object>();
        block.put("type", event.getBlock().getType().getWireValue());
        if (event.getBlock().getCallId() != null) {
            block.put("callId", event.getBlock().getCallId());
            block.put("name", event.getBlock().getName());
        }
        frame.put("block", block);
    }

    /** 写入 block-delta 的互斥 text / argumentsDelta 字段。 */
    private static void writeBlockDelta(Map<String, Object> frame, ModelBlockDeltaEvent event) {
        frame.put("index", event.getIndex());
        Map<String, Object> delta = new LinkedHashMap<String, Object>();
        delta.put("type", event.getDelta().getType().getWireValue());
        if (event.getDelta().getText() != null) {
            delta.put("text", event.getDelta().getText());
        } else {
            delta.put("argumentsDelta", event.getDelta().getArgumentsDelta());
        }
        frame.put("delta", delta);
    }

    /** 写入 message-stop，并始终保留 usage/modelState 键表达“厂商未提供”。 */
    private static void writeMessageStop(Map<String, Object> frame, ModelMessageStopEvent event) {
        frame.put("stopReason", event.getStopReason().getWireValue());
        ModelState state = event.getModelState();
        if (state == null) {
            frame.put("modelState", null);
        } else {
            Map<String, Object> stateMap = new LinkedHashMap<String, Object>();
            stateMap.put("format", state.getFormat());
            stateMap.put("data", state.getData());
            frame.put("modelState", stateMap);
        }
        ModelUsage usage = event.getUsage();
        if (usage == null) {
            frame.put("usage", null);
        } else {
            Map<String, Object> usageMap = new LinkedHashMap<String, Object>();
            usageMap.put("inputTokens", usage.getInputTokens());
            usageMap.put("outputTokens", usage.getOutputTokens());
            usageMap.put("totalTokens", usage.getTotalTokens());
            frame.put("usage", usageMap);
        }
    }

    /** 构造浏览器 Runtime 可识别且带明确重试语义的流内错误帧。 */
    private String errorFrame(String message, boolean retryable) {
        try {
            Map<String, Object> error = new LinkedHashMap<String, Object>();
            error.put("code", AgentErrorCode.MODEL_FAILED);
            error.put("message", message == null ? "模型调用失败" : truncate(message, 300));
            error.put("retryable", retryable);
            Map<String, Object> frame = new LinkedHashMap<String, Object>();
            frame.put("type", "error");
            frame.put("error", error);
            return objectMapper.writeValueAsString(frame);
        } catch (Exception e) {
            throw new ModelGatewayException("模型错误帧序列化失败", e, false);
        }
    }

    /** 上游错误摘要截断，避免把超长厂商正文发给浏览器。 */
    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    /** 缺失 traceId 时生成服务端链路标识。 */
    private static String blankToRandom(String value) {
        return value == null || value.trim().isEmpty()
                ? UUID.randomUUID().toString().replace("-", "")
                : value;
    }

    /** 单条浏览器 SSE 会话：统一处理事件写入、一次性终止、审计与上游取消。 AtomicBoolean 防止 Provider 失败与 Servlet 断开回调并发导致重复审计。 */
    private final class StreamSession implements ModelStreamListener {
        /** 当前浏览器长连接。 */
        private final SseEmitter emitter;

        /** 用于日志和审计关联的链路标识。 */
        private final String traceId;

        /** 可选会话标识。 */
        private final String conversationId;

        /** 服务端认证得到的可信用户。 */
        private final UserContext user;

        /** 本次不可变 Core 请求，供审计统计消息数。 */
        private final ModelRequest request;

        /** 实际模型覆盖名或服务端默认名。 */
        private final String modelName;

        /** 会话建立时间，用于计算完整流耗时。 */
        private final long start = System.currentTimeMillis();

        /** 完成、失败、断开和取消只能有一个终止者。 */
        private final AtomicBoolean terminated = new AtomicBoolean(false);

        /**
         * 事件发送与终止切换的串行化边界（二次审计 Q-09）：此前“先读 terminated 再发送”
         * 在两步之间并发终止时，仍可能向浏览器发出终止之后的迟到帧。
         * 锁序约定为 上游管线锁 → 本锁 单向获取；因此本类在持锁期间不得回调 ModelCall.cancel。
         */
        private final Object sessionLock = new Object();

        /** 异步 Provider 取消句柄；支持同步回调早于句柄返回的时序。 */
        private final AtomicReference<ModelCall> call = new AtomicReference<ModelCall>();

        /** 保存完成审计所需的不可变上下文。 */
        private StreamSession(
                SseEmitter emitter,
                String traceId,
                String conversationId,
                UserContext user,
                ModelRequest request,
                String modelName) {
            this.emitter = emitter;
            this.traceId = traceId;
            this.conversationId = conversationId;
            this.user = user;
            this.request = request;
            this.modelName = modelName;
        }

        /** 绑定异步调用句柄；若流已在同步回调阶段终止，立即取消迟到的句柄（不持锁调用，维持锁序单向）。 */
        private void attach(ModelCall modelCall) {
            call.set(modelCall);
            if (terminated.get()) {
                modelCall.cancel();
            }
        }

        /** 终止前已开始发送的事件允许完成；终止之后开始的发送直接丢弃。 */
        @Override
        public void onEvent(ModelStreamEvent event) {
            synchronized (sessionLock) {
                if (terminated.get()) {
                    return;
                }
                try {
                    emitter.send(eventFrame(event), MediaType.APPLICATION_JSON);
                } catch (IOException e) {
                    // 写入失败就是下游断开，必须由会话统一收口终止上游并记录一次审计。
                    cancelFromDownstream();
                }
            }
        }

        /** 正常完成时审计并关闭下游流；终止切换收进锁内，与事件发送线性化。 */
        @Override
        public void onCompleted() {
            synchronized (sessionLock) {
                if (!terminated.compareAndSet(false, true)) {
                    return;
                }
                recordAudit(true, null, null);
                emitter.complete();
            }
        }

        /** 异步失败时尽力发送错误帧，随后审计并关闭下游流；终止切换同样收进锁内。 */
        @Override
        public void onError(Throwable error) {
            synchronized (sessionLock) {
                if (!terminated.compareAndSet(false, true)) {
                    return;
                }
                String message = error == null ? "模型调用失败" : error.getMessage();
                boolean retryable =
                        error instanceof ModelGatewayException
                                && ((ModelGatewayException) error).isRetryable();
                try {
                    emitter.send(errorFrame(message, retryable), MediaType.APPLICATION_JSON);
                } catch (Exception ignored) {
                    // 浏览器已经断开时无法再传递错误帧，取消与审计仍继续执行。
                }
                recordAudit(false, AgentErrorCode.MODEL_FAILED, message);
                log.warn("模型流转发失败 traceId={}: {}", traceId, message);
                emitter.complete();
            }
        }

        /**
         * Servlet 检测到断开或超时时取消上游，并记录为主动中止。
         * 终止切换在锁内完成以关闭事件入口；ModelCall.cancel 必须在锁外执行——
         * 上游取消会经管线锁收口，持本锁再等上游会形成锁序回环。
         */
        private void cancelFromDownstream() {
            synchronized (sessionLock) {
                if (!terminated.compareAndSet(false, true)) {
                    return;
                }
            }
            ModelCall modelCall = call.get();
            if (modelCall != null) {
                modelCall.cancel();
            }
            recordAudit(false, AgentErrorCode.ABORTED, "浏览器连接已结束");
        }

        /** 统一写入模型调用审计。 */
        private void recordAudit(boolean success, String errorCode, String errorMessage) {
            audit.record(
                    traceId,
                    conversationId,
                    user,
                    AuditInvocationType.MODEL,
                    "MODEL",
                    modelName,
                    success,
                    errorCode,
                    errorMessage,
                    System.currentTimeMillis() - start,
                    request.getMessages() == null
                            ? null
                            : Integer.valueOf(request.getMessages().size()),
                    null);
        }
    }
}
