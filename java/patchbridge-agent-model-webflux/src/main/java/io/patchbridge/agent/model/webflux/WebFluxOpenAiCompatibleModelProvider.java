package io.patchbridge.agent.model.webflux;

import io.patchbridge.agent.core.error.ModelGatewayException;
import io.patchbridge.agent.core.model.ModelCall;
import io.patchbridge.agent.core.model.ModelProvider;
import io.patchbridge.agent.core.model.ModelRequest;
import io.patchbridge.agent.core.model.ModelStreamListener;
import io.patchbridge.agent.model.openai.OpenAiChatProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 使用 Spring WebClient 非阻塞消费 OpenAI-compatible SSE 的模型 Adapter。
 *
 * <p>该实现没有 Spring Bean 注解与自动配置，也不依赖 MVC Starter。宿主显式传入 已按自身网络策略配置的 WebClient，并将本类注册为
 * ModelProvider，才能启用该适配器。 Reactor 类型不会穿透 Core SPI，取消操作也只通过 ModelCall 暴露。
 */
public final class WebFluxOpenAiCompatibleModelProvider implements ModelProvider {

    /** Spring 解析 SSE data 时需要保留的泛型类型。 */
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<ServerSentEvent<String>>() {};

    /** 宿主提供的非阻塞 HTTP 客户端，承载其代理、TLS、连接池和超时策略。 */
    private final WebClient webClient;

    /** 构造阶段固定的上游模型协议配置。 */
    private final WebFluxModelProviderConfig config;

    /** 厂商请求编码与结构化流解码的唯一边界。 */
    private final OpenAiChatProtocol protocol = new OpenAiChatProtocol(new ObjectMapper());

    /**
     * 创建不会修改宿主 WebApplicationType 的模型 Provider。
     *
     * @param webClient 宿主显式构造的 WebClient
     * @param config 不可变上游配置
     */
    public WebFluxOpenAiCompatibleModelProvider(
            WebClient webClient, WebFluxModelProviderConfig config) {
        if (webClient == null) {
            throw new IllegalArgumentException("webClient 不可为空");
        }
        if (config == null) {
            throw new IllegalArgumentException("config 不可为空");
        }
        this.webClient = webClient;
        this.config = config;
    }

    /**
     * 订阅上游 SSE 并立即返回可取消句柄。
     *
     * <p>每条厂商 data 在 Provider 内转换为结构化 Core 事件，结束标记不会透传。 取消调用只终止上游订阅，不发送容易被误判为模型故障的终止回调。
     */
    @Override
    public ModelCall stream(ModelRequest request, ModelStreamListener listener) {
        if (request == null) {
            throw new ModelGatewayException("模型请求不可为空", false);
        }
        if (listener == null) {
            throw new ModelGatewayException("模型流监听器不可为空", false);
        }

        String model = isBlank(request.getModel()) ? config.getDefaultModel() : request.getModel();
        OpenAiChatProtocol.PreparedRequest prepared = protocol.prepare(request, model);
        OpenAiChatProtocol.Decoder decoder =
                protocol.decoder(request, prepared.getRetainedReasoning());
        Flux<String> dataEvents =
                requestEvents(prepared.getBody())
                        .map(ServerSentEvent::data)
                        .filter(data -> data != null)
                        .takeUntil("[DONE]"::equals)
                        .onErrorMap(this::asGatewayException);

        ReactorModelCall call = new ReactorModelCall(listener, decoder);
        call.subscribe(dataEvents);
        return call;
    }

    /** 构造 HTTP 请求，并按状态码选择 SSE 解析或错误正文处理。 */
    private Flux<ServerSentEvent<String>> requestEvents(Map<String, Object> body) {
        WebClient.RequestBodySpec requestSpec =
                webClient
                        .post()
                        .uri(config.getCompletionsUri())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON);
        if (!isBlank(config.getApiKey())) {
            requestSpec.header(HttpHeaders.AUTHORIZATION, "Bearer " + config.getApiKey());
        }
        return requestSpec.bodyValue(body).exchangeToFlux(this::decodeResponse);
    }

    /**
     * 对成功响应进行 SSE 解码；非成功响应先释放正文，再只公开 HTTP 状态。
     *
     * <p>上游正文可能含凭据、请求片段或内部诊断，不能进入 Browser SSE、审计或日志。
     */
    private Flux<ServerSentEvent<String>> decodeResponse(ClientResponse response) {
        if (response.statusCode().is2xxSuccessful()) {
            return response.bodyToFlux(SSE_TYPE);
        }
        int statusCode = response.rawStatusCode();
        return response.releaseBody()
                .thenMany(Flux.<ServerSentEvent<String>>error(httpFailure(statusCode)));
    }

    /** 创建不携带上游正文的 HTTP 网关异常。 */
    private ModelGatewayException httpFailure(int statusCode) {
        return new ModelGatewayException("模型网关上游 HTTP " + statusCode);
    }

    /** 保留已归一化的网关异常，其余连接或解码异常统一封装。 */
    private Throwable asGatewayException(Throwable error) {
        if (error instanceof ModelGatewayException) {
            return error;
        }
        String message =
                error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return new ModelGatewayException("模型流式调用失败: " + message, error);
    }

    /** 判断可选配置文本是否缺失。 */
    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** 把 Reactor Disposable 封装为 Core ModelCall，并集中保证终止回调至多一次。 */
    private static final class ReactorModelCall implements ModelCall {

        /** 下游模型事件监听器。 */
        private final ModelStreamListener listener;

        /** 与本次请求绑定的厂商流解码器。 */
        private final OpenAiChatProtocol.Decoder decoder;

        /** 同时处理完成、失败和取消竞争，防止重复终止。 */
        private final AtomicBoolean terminated = new AtomicBoolean(false);

        /** 支持订阅建立前后的统一取消，避免同步 Publisher 产生时序缺口。 */
        private final Disposable.Swap subscription = Disposables.swap();

        /** 保存本次调用的监听器。 */
        private ReactorModelCall(ModelStreamListener listener, OpenAiChatProtocol.Decoder decoder) {
            this.listener = listener;
            this.decoder = decoder;
        }

        /** 建立唯一 Reactor 订阅，并绑定事件及终止信号。 */
        private void subscribe(Flux<String> events) {
            Disposable actual = events.subscribe(this::onEvent, this::onError, this::onCompleted);
            subscription.update(actual);
        }

        /** 只在调用仍有效时解码厂商 data；结束标记仅驱动协议完成。 */
        private void onEvent(String data) {
            if (!terminated.get()) {
                if ("[DONE]".equals(data)) {
                    decoder.finish(listener);
                } else {
                    decoder.accept(data, listener);
                }
            }
        }

        /** 将异步异常作为唯一终止信号下发。 */
        private void onError(Throwable error) {
            if (terminated.compareAndSet(false, true)) {
                listener.onError(error);
            }
        }

        /** 将正常流结束作为唯一终止信号下发。 */
        private void onCompleted() {
            if (terminated.compareAndSet(false, true)) {
                try {
                    decoder.finish(listener);
                    listener.onCompleted();
                } catch (RuntimeException e) {
                    listener.onError(e);
                }
            }
        }

        /** 幂等停止订阅；取消是调用方行为，因此不会伪造完成或错误回调。 */
        @Override
        public void cancel() {
            terminated.compareAndSet(false, true);
            subscription.dispose();
        }
    }
}
