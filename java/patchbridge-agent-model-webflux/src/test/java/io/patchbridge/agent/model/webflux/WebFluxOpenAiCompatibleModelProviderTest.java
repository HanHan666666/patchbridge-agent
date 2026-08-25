package io.patchbridge.agent.model.webflux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.patchbridge.agent.core.error.ModelGatewayException;
import io.patchbridge.agent.core.error.ModelInvocationTimeoutException;
import io.patchbridge.agent.core.invocation.DefaultModelGateway;
import io.patchbridge.agent.core.invocation.ModelInvocation;
import io.patchbridge.agent.core.invocation.ModelInvocationPipeline;
import io.patchbridge.agent.core.model.AgentMessage;
import io.patchbridge.agent.core.model.ContentBlock;
import io.patchbridge.agent.core.model.MessageRole;
import io.patchbridge.agent.core.model.ModelBlockDeltaEvent;
import io.patchbridge.agent.core.model.ModelBlockStartEvent;
import io.patchbridge.agent.core.model.ModelCall;
import io.patchbridge.agent.core.model.ModelMessageStopEvent;
import io.patchbridge.agent.core.model.ModelRequest;
import io.patchbridge.agent.core.model.ModelResponse;
import io.patchbridge.agent.core.model.ModelState;
import io.patchbridge.agent.core.model.ModelStopReason;
import io.patchbridge.agent.core.model.ModelStreamEvent;
import io.patchbridge.agent.core.model.ModelStreamListener;
import io.patchbridge.agent.core.model.ReasoningBlock;
import io.patchbridge.agent.core.model.TextBlock;
import io.patchbridge.agent.core.model.ToolCallBlock;
import io.patchbridge.agent.model.openai.OpenAiChatProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WebFlux 模型 Adapter 的无网络单元测试。
 *
 * <p>覆盖结构化流转换、厂商请求隔离、状态校验、HTTP 失败与取消竞争；测试通过 ExchangeFunction 注入可控响应，不启动端口，也不依赖真实模型服务。
 */
class WebFluxOpenAiCompatibleModelProviderTest {

    /** 厂商 SSE 必须转换为 Core 事件，并按 responseMessageId 产生下一份 reasoning 状态。 */
    @Test
    void shouldProduceStructuredEventsAndCompleteAfterDone() throws InterruptedException {
        AtomicReference<ClientRequest> captured = new AtomicReference<ClientRequest>();
        WebClient client =
                client(
                        request -> {
                            captured.set(request);
                            return Mono.just(
                                    sseResponse(
                                            "data:"
                                                + " {\"choices\":[{\"delta\":{\"reasoning_content\":\"计划\"}}]}\n\n"
                                                + "data:"
                                                + " {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"function\":{\"name\":\"local.echo\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n"
                                                + "data: [DONE]\n\n"));
                        });
        RecordingListener listener = new RecordingListener();

        provider(client).stream(simpleRequest(), listener);

        assertTrue(listener.awaitTerminal());
        assertEquals(7, listener.events.size());
        assertEquals(1L, countMessageStops(listener.events));
        assertTrue(listener.events.get(0) instanceof ModelBlockStartEvent);
        assertTrue(listener.events.get(1) instanceof ModelBlockDeltaEvent);
        ModelMessageStopEvent stop = (ModelMessageStopEvent) listener.events.get(6);
        Map<?, ?> stateData = (Map<?, ?>) stop.getModelState().getData();
        assertEquals("计划", ((Map<?, ?>) stateData.get("reasoningByMessageId")).get("response-1"));
        assertEquals(1, listener.completionCount.get());
        assertEquals(0, listener.errorCount.get());
        assertEquals("https://model.example/v1/chat/completions", captured.get().url().toString());
        assertEquals("Bearer secret", captured.get().headers().getFirst(HttpHeaders.AUTHORIZATION));
    }

    /** WebFlux Provider 的真实协议输出必须经调用管线聚合为与默认实现一致的厂商中立响应。 */
    @Test
    void shouldAggregateProviderOutputThroughModelGateway() {
        AtomicReference<ClientRequest> captured = new AtomicReference<ClientRequest>();
        WebClient client =
                client(
                        request -> {
                            captured.set(request);
                            return Mono.just(
                                    sseResponse(
                                            "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"先分析\"}}]}\n\n"
                                                + "data: {\"choices\":[{\"delta\":{\"content\":\"答案\"}}]}\n\n"
                                                + "data: {\"choices\":[{\"delta\":{\"content\":\"完成\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":4,\"total_tokens\":11}}\n\n"
                                                + "data: [DONE]\n\n"));
                        });
        ModelRequest request = simpleRequest();
        DefaultModelGateway gateway =
                new DefaultModelGateway(
                        new ModelInvocationPipeline(provider(client), Collections.emptyList()));

        ModelResponse response = gateway.invoke(request).await(Duration.ofSeconds(2));

        assertTrue(request.getTools().isEmpty());
        assertEquals("response-1", response.getMessage().getId());
        assertEquals(MessageRole.ASSISTANT, response.getMessage().getRole());
        assertEquals(2, response.getMessage().getBlocks().size());
        assertTrue(response.getMessage().getBlocks().get(0) instanceof ReasoningBlock);
        assertEquals(
                "先分析",
                ((ReasoningBlock) response.getMessage().getBlocks().get(0)).getText());
        assertTrue(response.getMessage().getBlocks().get(1) instanceof TextBlock);
        assertEquals(
                "答案完成",
                ((TextBlock) response.getMessage().getBlocks().get(1)).getText());
        assertEquals("答案完成", response.getText());
        assertEquals(ModelStopReason.END_TURN, response.getStopReason());
        assertEquals(7L, response.getUsage().getInputTokens());
        assertEquals(4L, response.getUsage().getOutputTokens());
        assertEquals(11L, response.getUsage().getTotalTokens());
        assertNull(response.getModelState());
        assertEquals("https://model.example/v1/chat/completions", captured.get().url().toString());
    }

    /** Gateway 等待超时必须 dispose 真实 WebFlux 订阅，并固定唯一超时终态。 */
    @Test
    void shouldDisposeWebFluxSubscriptionWhenModelGatewayTimesOut() throws Exception {
        AtomicInteger cancellationCount = new AtomicInteger();
        CountDownLatch subscribed = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        Flux<DataBuffer> pendingBody =
                Flux.<DataBuffer>never()
                        .doOnSubscribe(value -> subscribed.countDown())
                        .doOnCancel(
                                () -> {
                                    cancellationCount.incrementAndGet();
                                    cancelled.countDown();
                                });
        WebClient client =
                client(
                        request ->
                                Mono.just(
                                        ClientResponse.create(HttpStatus.OK)
                                                .header(
                                                        HttpHeaders.CONTENT_TYPE,
                                                        MediaType.TEXT_EVENT_STREAM_VALUE)
                                                .body(pendingBody)
                                                .build()));
        DefaultModelGateway gateway =
                new DefaultModelGateway(
                        new ModelInvocationPipeline(provider(client), Collections.emptyList()));

        ModelInvocation invocation = gateway.invoke(simpleRequest());
        assertTrue(subscribed.await(2, TimeUnit.SECONDS));
        ModelInvocationTimeoutException timeout =
                assertThrows(
                        ModelInvocationTimeoutException.class,
                        () -> invocation.await(Duration.ofMillis(50)));

        assertTrue(cancelled.await(2, TimeUnit.SECONDS));
        invocation.cancel();
        invocation.cancel();
        assertEquals(1, cancellationCount.get());
        CompletionException resultFailure =
                assertThrows(
                        CompletionException.class,
                        () -> invocation.result().toCompletableFuture().join());
        assertSame(timeout, resultFailure.getCause());
    }

    /** WebFlux 协议编码也必须按消息 ID 恢复 reasoning，并剥离全部框架 ID。 */
    @Test
    @SuppressWarnings("unchecked")
    void shouldEncodeReasoningStateWithoutLeakingFrameworkIds() {
        OpenAiChatProtocol protocol = new OpenAiChatProtocol(new ObjectMapper());
        OpenAiChatProtocol.PreparedRequest prepared =
                protocol.prepare(requestWithState(), "gpt-test");
        List<Map<String, Object>> messages =
                (List<Map<String, Object>>) prepared.getBody().get("messages");

        assertEquals("历史思考", messages.get(1).get("reasoning_content"));
        assertFalse(prepared.getBody().toString().contains("assistant-prev"));
        assertFalse(prepared.getBody().toString().contains("response-new"));
        Map<String, Object> call =
                ((List<Map<String, Object>>) messages.get(1).get("tool_calls")).get(0);
        Map<String, Object> function = (Map<String, Object>) call.get("function");
        assertEquals("{\"value\":1}", function.get("arguments"));
    }

    /** 状态格式不匹配必须在建立 Reactor 订阅前失败且不可重试。 */
    @Test
    void shouldRejectIncompatibleStateBeforeSubscription() {
        AtomicBoolean requested = new AtomicBoolean(false);
        WebClient client =
                client(
                        request -> {
                            requested.set(true);
                            return Mono.just(sseResponse(""));
                        });
        ModelRequest base = simpleRequest();
        ModelRequest incompatible =
                new ModelRequest(
                        base.getResponseMessageId(),
                        null,
                        base.getMessages(),
                        base.getTools(),
                        new ModelState("other/v1", Collections.singletonMap("x", "y")),
                        null,
                        null);

        ModelGatewayException failure =
                assertThrows(
                        ModelGatewayException.class,
                        () -> provider(client).stream(incompatible, new RecordingListener()));

        assertFalse(failure.isRetryable());
        assertFalse(requested.get());
    }

    /** 首个厂商事件前的正文网络失败必须只产生一次错误终态。 */
    @Test
    void shouldReportTransportFailureBeforeFirstEvent() throws InterruptedException {
        Flux<DataBuffer> failedBody =
                Flux.error(new IOException("connection reset before first event"));
        WebClient client = client(request -> Mono.just(sseResponse(failedBody)));
        RecordingListener listener = new RecordingListener();

        provider(client).stream(simpleRequest(), listener);

        assertTrue(listener.awaitTerminal());
        assertTrue(listener.events.isEmpty());
        assertEquals(0, listener.completionCount.get());
        assertEquals(1, listener.errorCount.get());
        assertInstanceOf(ModelGatewayException.class, listener.error.get());
    }

    /** 部分结构事件交付后的网络失败不得伪造 message-stop 或完成回调。 */
    @Test
    void shouldFailOnceAfterPartialEvents() throws InterruptedException {
        String firstData =
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"半截\"}}]}\n\n";
        Flux<DataBuffer> body =
                Flux.concat(
                        Flux.just(dataBuffer(firstData)),
                        Flux.<DataBuffer>error(new IOException("connection reset after event")));
        WebClient client = client(request -> Mono.just(sseResponse(body)));
        RecordingListener listener = new RecordingListener();

        provider(client).stream(simpleRequest(), listener);

        assertTrue(listener.awaitTerminal());
        assertEquals(2, listener.events.size());
        assertTrue(listener.events.get(0) instanceof ModelBlockStartEvent);
        assertTrue(listener.events.get(1) instanceof ModelBlockDeltaEvent);
        assertFalse(hasMessageStop(listener.events));
        assertEquals(0, listener.completionCount.get());
        assertEquals(1, listener.errorCount.get());
    }

    /** [DONE] 已完成协议后，上游迟到的清理错误不得推翻唯一正常终态。 */
    @Test
    void shouldIgnoreTransportFailureAfterProtocolCompletion() throws InterruptedException {
        String completed =
                "data: {\"choices\":[{\"delta\":{\"content\":\"完成\"},"
                        + "\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n";
        Flux<DataBuffer> body =
                Flux.concat(
                        Flux.just(dataBuffer(completed)),
                        Flux.<DataBuffer>error(new IOException("late cleanup failure")));
        WebClient client = client(request -> Mono.just(sseResponse(body)));
        RecordingListener listener = new RecordingListener();

        provider(client).stream(simpleRequest(), listener);

        assertTrue(listener.awaitTerminal());
        assertEquals(1L, countMessageStops(listener.events));
        assertEquals(1, listener.completionCount.get());
        assertEquals(0, listener.errorCount.get());
    }

    /** 非 2xx 只报告一次状态码，不能把不受信任的上游正文带入下游。 */
    @Test
    void shouldReportNonSuccessfulResponseOnce() throws InterruptedException {
        WebClient client =
                client(
                        request ->
                                Mono.just(
                                        ClientResponse.create(HttpStatus.UNAUTHORIZED)
                                                .header(
                                                        HttpHeaders.CONTENT_TYPE,
                                                        MediaType.TEXT_PLAIN_VALUE)
                                                .body("invalid credential")
                                                .build()));
        RecordingListener listener = new RecordingListener();

        provider(client).stream(simpleRequest(), listener);

        assertTrue(listener.awaitTerminal());
        assertEquals(0, listener.completionCount.get());
        assertEquals(1, listener.errorCount.get());
        assertInstanceOf(ModelGatewayException.class, listener.error.get());
        assertTrue(listener.error.get().getMessage().contains("HTTP 401"));
        assertFalse(listener.error.get().getMessage().contains("invalid credential"));
    }

    /** Disposable 取消必须幂等且不伪造终止回调。 */
    @Test
    void shouldCancelUpstreamWithoutTerminalCallback() throws InterruptedException {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        CountDownLatch subscribed = new CountDownLatch(1);
        AtomicReference<FluxSink<DataBuffer>> upstream =
                new AtomicReference<FluxSink<DataBuffer>>();
        Flux<DataBuffer> pendingBody =
                Flux.<DataBuffer>create(
                                sink -> {
                                    upstream.set(sink);
                                    sink.onCancel(() -> cancelled.set(true));
                                })
                        .doOnSubscribe(value -> subscribed.countDown());
        WebClient client =
                client(
                        request ->
                                Mono.just(
                                        ClientResponse.create(HttpStatus.OK)
                                                .header(
                                                        HttpHeaders.CONTENT_TYPE,
                                                        MediaType.TEXT_EVENT_STREAM_VALUE)
                                                .body(pendingBody)
                                                .build()));
        RecordingListener listener = new RecordingListener();

        ModelCall call = provider(client).stream(simpleRequest(), listener);
        assertTrue(subscribed.await(2, TimeUnit.SECONDS));
        call.cancel();
        call.cancel();
        upstream.get().error(new IOException("取消后的迟到错误"));

        assertTrue(cancelled.get());
        assertTrue(listener.events.isEmpty());
        assertEquals(0, listener.completionCount.get());
        assertEquals(0, listener.errorCount.get());
        assertFalse(listener.terminal.await(50, TimeUnit.MILLISECONDS));
    }

    /** 不可变配置拒绝相对地址、URL 内嵌凭据、查询参数与缺失模型名。 */
    @Test
    void shouldRejectInvalidConfiguration() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WebFluxModelProviderConfig("/v1", null, "gpt-test"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WebFluxModelProviderConfig(
                                "https://user:secret@model.example/v1", null, "gpt-test"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WebFluxModelProviderConfig(
                                "https://model.example/v1?tenant=a", null, "gpt-test"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WebFluxModelProviderConfig("https://model.example", null, " "));
    }

    /** 创建使用可控 ExchangeFunction 的 WebClient。 */
    private static WebClient client(
            org.springframework.web.reactive.function.client.ExchangeFunction exchangeFunction) {
        return WebClient.builder()
                .clientConnector(
                        (method, uri, callback) -> Mono.error(new AssertionError("测试不应访问真实网络")))
                .exchangeFunction(exchangeFunction)
                .build();
    }

    /** 创建测试 Provider。 */
    private static WebFluxOpenAiCompatibleModelProvider provider(WebClient client) {
        return new WebFluxOpenAiCompatibleModelProvider(
                client,
                new WebFluxModelProviderConfig("https://model.example/v1/", "secret", "gpt-test"));
    }

    /** 创建最小领域请求。 */
    private static ModelRequest simpleRequest() {
        AgentMessage user =
                new AgentMessage(
                        "user-1",
                        MessageRole.USER,
                        Collections.<ContentBlock>singletonList(new TextBlock("hi")));
        return new ModelRequest(
                "response-1",
                null,
                Collections.singletonList(user),
                Collections.emptyList(),
                null,
                null,
                null);
    }

    /** 创建含 assistant tool-call 及对应 reasoning 状态的请求。 */
    private static ModelRequest requestWithState() {
        AgentMessage user =
                new AgentMessage(
                        "user-1",
                        MessageRole.USER,
                        Collections.<ContentBlock>singletonList(new TextBlock("hi")));
        AgentMessage assistant =
                new AgentMessage(
                        "assistant-prev",
                        MessageRole.ASSISTANT,
                        Collections.<ContentBlock>singletonList(
                                new ToolCallBlock(
                                        "call-1",
                                        "local.x",
                                        Collections.<String, Object>singletonMap("value", 1))));
        Map<String, Object> stateData = new LinkedHashMap<String, Object>();
        stateData.put(
                "reasoningByMessageId",
                Collections.<String, Object>singletonMap("assistant-prev", "历史思考"));
        return new ModelRequest(
                "response-new",
                null,
                Arrays.asList(user, assistant),
                Collections.emptyList(),
                new ModelState(OpenAiChatProtocol.STATE_FORMAT, stateData),
                null,
                null);
    }

    /** 创建内存 SSE 响应。 */
    private static ClientResponse sseResponse(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                .body(body)
                .build();
    }

    /** 创建由测试精确控制完成或失败时机的 SSE 响应。 */
    private static ClientResponse sseResponse(Flux<DataBuffer> body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                .body(body)
                .build();
    }

    /** 把 SSE 文本编码为不依赖 Netty 的堆内 DataBuffer。 */
    private static DataBuffer dataBuffer(String value) {
        return DefaultDataBufferFactory.sharedInstance.wrap(
                value.getBytes(StandardCharsets.UTF_8));
    }

    /** 判断部分失败流是否错误地产生了稳定消息终点。 */
    private static boolean hasMessageStop(List<ModelStreamEvent> events) {
        return countMessageStops(events) > 0L;
    }

    /** 统计 message-stop，证明传输竞争只产生一个稳定协议终态。 */
    private static long countMessageStops(List<ModelStreamEvent> events) {
        long count = 0L;
        for (ModelStreamEvent event : events) {
            if (event instanceof ModelMessageStopEvent) {
                count += 1L;
            }
        }
        return count;
    }

    /** 线程安全记录结构事件与唯一终止信号。 */
    private static final class RecordingListener implements ModelStreamListener {
        /** 已收到结构化事件。 */
        private final List<ModelStreamEvent> events = new CopyOnWriteArrayList<ModelStreamEvent>();

        /** 正常完成次数。 */
        private final AtomicInteger completionCount = new AtomicInteger();

        /** 错误次数。 */
        private final AtomicInteger errorCount = new AtomicInteger();

        /** 最后错误。 */
        private final AtomicReference<Throwable> error = new AtomicReference<Throwable>();

        /** 任一终止信号。 */
        private final CountDownLatch terminal = new CountDownLatch(1);

        /** 记录事件。 */
        @Override
        public void onEvent(ModelStreamEvent event) {
            events.add(event);
        }

        /** 记录完成。 */
        @Override
        public void onCompleted() {
            completionCount.incrementAndGet();
            terminal.countDown();
        }

        /** 记录错误。 */
        @Override
        public void onError(Throwable failure) {
            error.set(failure);
            errorCount.incrementAndGet();
            terminal.countDown();
        }

        /** 最多等待两秒。 */
        private boolean awaitTerminal() throws InterruptedException {
            return terminal.await(2, TimeUnit.SECONDS);
        }
    }
}
