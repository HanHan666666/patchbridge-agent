package io.patchbridge.agent.starter.model;

import io.patchbridge.agent.core.error.ModelGatewayException;
import io.patchbridge.agent.core.model.ModelCall;
import io.patchbridge.agent.core.model.ModelProvider;
import io.patchbridge.agent.core.model.ModelRequest;
import io.patchbridge.agent.core.model.ModelState;
import io.patchbridge.agent.core.model.ModelStateProjector;
import io.patchbridge.agent.core.model.ModelStreamListener;
import io.patchbridge.agent.core.model.AgentMessage;
import io.patchbridge.agent.model.openai.OpenAiChatProtocol;
import io.patchbridge.agent.starter.PatchBridgeAgentProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;

import java.util.Map;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenAI-compatible 异步模型 Provider。
 *
 * <p>使用 OkHttp 官方 EventSource 的异步 API，调用线程不等待模型长连接； 返回的 ModelCall 直接取消 EventSource。Provider 通过
 * {@link OpenAiChatProtocol} 独占厂商请求编码、SSE 解码和 reasoning 连续状态；向 Core 与浏览器只发布结构化事件。
 *
 * <p>不使用类型化厂商 SDK，是为了完整保留 compatible 网关的扩展字段； 默认选用 OkHttp 3.14 是为了继续支持 Java 8。需要 Reactor Netty
 * 的宿主可通过 独立 WebFlux Adapter 提供同一 ModelProvider SPI，而无需改变 Core 或浏览器协议。
 */
public class OpenAiCompatibleModelProvider implements ModelProvider, ModelStateProjector {

    /** 线程安全 JSON 编解码器，供协议边界并发复用。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 上游请求媒体类型。 */
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");

    /** Starter 模型端点、凭据和超时配置。 */
    private final PatchBridgeAgentProperties.Model config;

    /** 构造期校验并固定的 chat/completions 绝对地址，避免每次请求重复拼接解析。 */
    private final String chatCompletionsUrl;

    /** Java 8 兼容的异步 SSE 客户端。 */
    private final OkHttpClient client;

    /** 无状态协议边界，可安全复用于并发模型调用。 */
    private final OpenAiChatProtocol protocol = new OpenAiChatProtocol(JSON);

    /** 校验必需配置并创建可复用的异步 HTTP Client。 */
    public OpenAiCompatibleModelProvider(PatchBridgeAgentProperties.Model config) {
        if (config == null || isBlank(config.getBaseUrl()) || isBlank(config.getModel())) {
            throw new IllegalStateException(
                    "patchbridge-agent.model.base-url / model 未配置（api-key 可为空，取决于网关）");
        }
        this.config = config;
        // 与 WebFlux Adapter 的早失败语义对齐：URL 在构造期一次校验并固定，
        // 之后每次 stream 不再重复正则拼接，配置错误也在装配期暴露。
        this.chatCompletionsUrl = resolveChatCompletionsUrl(config.getBaseUrl());
        this.client =
                new OkHttpClient.Builder()
                        .connectTimeout(config.getConnectTimeoutMs(), TimeUnit.MILLISECONDS)
                        .readTimeout(config.getReadTimeoutMs(), TimeUnit.MILLISECONDS)
                        .build();
    }

    /**
     * 校验 base-url 并固定 /chat/completions 完整地址。
     *
     * <p>只接受不含 userInfo / query / fragment 的绝对 HTTP(S) 地址；
     * 这些成分不会进入模型请求，携带它们几乎必然是配置错误。
     */
    private static String resolveChatCompletionsUrl(String baseUrl) {
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        java.net.URI uri;
        try {
            uri = new java.net.URI(trimmed);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(
                    "patchbridge-agent.model.base-url 不是合法 URI: " + baseUrl, e);
        }
        String scheme = uri.getScheme();
        if (!uri.isAbsolute() || uri.getHost() == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalStateException(
                    "patchbridge-agent.model.base-url 必须是绝对 HTTP(S) 地址: " + baseUrl);
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalStateException(
                    "patchbridge-agent.model.base-url 不得包含用户信息、查询参数或片段: "
                            + baseUrl);
        }
        return trimmed + "/chat/completions";
    }

    /** 发起非阻塞 EventSource，并把取消能力作为 Core ModelCall 返回。 */
    @Override
    public ModelCall stream(ModelRequest request, ModelStreamListener listener) {
        if (request == null) {
            throw new ModelGatewayException("模型请求不可为空", false);
        }
        if (listener == null) {
            throw new ModelGatewayException("模型流监听器不可为空", false);
        }
        String model = isBlank(request.getModel()) ? config.getModel() : request.getModel();
        OpenAiChatProtocol.PreparedRequest prepared = protocol.prepare(request, model);
        OpenAiChatProtocol.Decoder decoder =
                protocol.decoder(request, prepared.getRetainedReasoning());

        Request.Builder http =
                new Request.Builder()
                        .url(chatCompletionsUrl)
                        .post(RequestBody.create(JSON_MEDIA_TYPE, encodeBody(prepared.getBody())))
                        .header("Accept", "text/event-stream");
        if (!isBlank(config.getApiKey())) {
            http.header("Authorization", "Bearer " + config.getApiKey());
        }

        AtomicBoolean terminated = new AtomicBoolean(false);
        EventSource eventSource =
                EventSources.createFactory(client)
                        .newEventSource(
                                http.build(),
                                new EventSourceListener() {
                                    /** 解码单条厂商事件；结束标记只终止协议，不得透传到浏览器。 */
                                    @Override
                                    public void onEvent(
                                            EventSource source,
                                            String id,
                                            String type,
                                            String data) {
                                        if (terminated.get()) {
                                            return;
                                        }
                                        try {
                                            if ("[DONE]".equals(data)) {
                                                decoder.finish(listener);
                                                if (terminated.compareAndSet(false, true)) {
                                                    source.cancel();
                                                    listener.onCompleted();
                                                }
                                            } else {
                                                decoder.accept(data, listener);
                                            }
                                        } catch (RuntimeException e) {
                                            if (terminated.compareAndSet(false, true)) {
                                                source.cancel();
                                                listener.onError(e);
                                            }
                                        }
                                    }

                                    /** 无结束标记但带完整 finish_reason 的正常关闭仍完成结构化消息。 */
                                    @Override
                                    public void onClosed(EventSource source) {
                                        if (terminated.get()) {
                                            return;
                                        }
                                        try {
                                            decoder.finish(listener);
                                            if (terminated.compareAndSet(false, true)) {
                                                listener.onCompleted();
                                            }
                                        } catch (RuntimeException e) {
                                            if (terminated.compareAndSet(false, true)) {
                                                listener.onError(e);
                                            }
                                        }
                                    }

                                    /** HTTP 非成功、网络失败与协议失败统一转换为异步模型错误。 */
                                    @Override
                                    public void onFailure(
                                            EventSource source,
                                            Throwable throwable,
                                            Response response) {
                                        if (!terminated.compareAndSet(false, true)) {
                                            return;
                                        }
                                        listener.onError(toGatewayException(throwable, response));
                                    }
                                });
        return new ModelCall() {
            /** 取消前先关闭事件通道，避免 EventSource 竞争回调穿透取消边界。 */
            @Override
            public void cancel() {
                if (terminated.compareAndSet(false, true)) {
                    eventSource.cancel();
                }
            }
        };
    }

    /** 由 OpenAI Chat 协议内核严格投影 reasoningByMessageId。 */
    @Override
    public ModelState project(ModelState state, List<AgentMessage> retainedMessages) {
        return protocol.projectState(state, retainedMessages);
    }

    /** 把协议内核产出的 Map 请求体序列化为上游 JSON（协议字段由内核独占）。 */
    private static String encodeBody(Map<String, Object> body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new ModelGatewayException("模型请求体序列化失败", e, false);
        }
    }

    /**
     * 把异步 EventSource 失败转换成稳定网关异常。
     *
     * <p>上游错误正文不跨越服务端信任边界：供应商或代理可能在其中返回凭据、
     * 请求片段或内部诊断，只公开 HTTP 状态即可定位协议层失败。
     */
    private static ModelGatewayException toGatewayException(
            Throwable throwable, Response response) {
        if (response != null) {
            return new ModelGatewayException(
                    "模型网关上游 HTTP " + response.code(), throwable);
        }
        String message = throwable == null ? "未知网络错误" : throwable.getMessage();
        return new ModelGatewayException("模型流式调用失败: " + message, throwable);
    }

    /** 判断配置文本是否缺失。 */
    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
