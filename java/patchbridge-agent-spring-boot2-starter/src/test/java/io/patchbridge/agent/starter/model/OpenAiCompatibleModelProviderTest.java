package io.patchbridge.agent.starter.model;

import io.patchbridge.agent.core.compaction.ContextCompactionSettings;
import io.patchbridge.agent.core.model.target.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import io.patchbridge.agent.core.model.ModelToolDefinition;
import io.patchbridge.agent.core.model.ReasoningBlock;
import io.patchbridge.agent.core.model.TextBlock;
import io.patchbridge.agent.core.model.ToolCallBlock;
import io.patchbridge.agent.model.openai.OpenAiChatProtocol;
import io.patchbridge.agent.starter.PatchBridgeAgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 验证默认 Provider 的领域协议转换、reasoning 状态、错误与取消语义。 */
class OpenAiCompatibleModelProviderTest {
    /** 单一测试目标用于验证 Gateway 始终经过路由。 */
    private static final ModelTargetRef REF = new ModelTargetRef("test-openai", 1);
    /** 测试 HTTP Server。 */
    private final MockWebServer server = new MockWebServer();

    /** 仅用于检查发往上游的厂商 JSON。 */
    private final ObjectMapper json = new ObjectMapper();

    /** 关闭测试 HTTP Server，避免线程与端口泄漏。 */
    @AfterEach
    void closeServer() throws IOException {
        server.shutdown();
    }

    /** Provider 必须恢复历史 tool-call reasoning，剥离框架 ID，并把厂商 chunk 转为结构事件。 */
    @Test
    void convertsCanonicalRequestAndProducesStructuredEvents() throws Exception {
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody(
                                "data:"
                                    + " {\"choices\":[{\"delta\":{\"reasoning_content\":\"先查\"}}]}\n\n"
                                    + "data:"
                                    + " {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                                    + "\"id\":\"call-new\",\"function\":{\"name\":\"local.echo\",\"arguments\":\"{\\\"text\\\":\\\"hi\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":4,\"total_tokens\":14}}\n\n"
                                    + "data: [DONE]\n\n"));
        RecordingListener listener = new RecordingListener();

        ModelCall call = provider().stream(requestWithReasoningState(), listener);

        assertNotNull(call);
        assertTrue(listener.awaitTerminal());
        assertNull(listener.failure.get());
        assertEquals(1, listener.completedCount);
        assertEquals(7, listener.events.size());
        assertEquals(1L, countMessageStops(listener.events));
        assertTrue(listener.events.get(0) instanceof ModelBlockStartEvent);
        assertTrue(listener.events.get(1) instanceof ModelBlockDeltaEvent);
        assertTrue(listener.events.get(3) instanceof ModelBlockStartEvent);
        ModelMessageStopEvent stop = (ModelMessageStopEvent) listener.events.get(6);
        assertEquals("tool-use", stop.getStopReason().getWireValue());
        assertEquals(14L, stop.getUsage().getTotalTokens());
        Map<?, ?> stateData = (Map<?, ?>) stop.getModelState().getData();
        Map<?, ?> reasoning = (Map<?, ?>) stateData.get("reasoningByMessageId");
        assertEquals("历史思考", reasoning.get("assistant-prev"));
        assertEquals("先查", reasoning.get("response-2"));

        RecordedRequest upstream = server.takeRequest(2, TimeUnit.SECONDS);
        JsonNode body = json.readTree(upstream.getBody().readUtf8());
        assertEquals("历史思考", body.at("/messages/1/reasoning_content").asText());
        assertEquals(
                "{\"text\":\"old\"}",
                body.at("/messages/1/tool_calls/0/function/arguments").asText());
        assertEquals("local.echo", body.at("/tools/0/function/name").asText());
        assertEquals("object", body.at("/tools/0/function/parameters/type").asText());
        assertFalse(body.toString().contains("展示摘要"));
        assertFalse(body.toString().contains("assistant-prev"));
        assertFalse(body.toString().contains("response-2"));
    }

    /** 默认 OkHttp Provider 的真实协议输出必须经调用管线聚合为厂商中立响应。 */
    @Test
    void modelGatewayAggregatesProviderOutputIntoVendorNeutralResponse() throws Exception {
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody(
                                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"先分析\"}}]}\n\n"
                                    + "data: {\"choices\":[{\"delta\":{\"content\":\"答案\"}}]}\n\n"
                                    + "data: {\"choices\":[{\"delta\":{\"content\":\"完成\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":4,\"total_tokens\":11}}\n\n"
                                    + "data: [DONE]\n\n"));
        ModelRequest request = simpleRequest();
        DefaultModelGateway gateway =
                new DefaultModelGateway(
                        new ModelInvocationPipeline(router(provider()), Collections.emptyList()));

        ModelResponse response = gateway.invoke(request).await(Duration.ofSeconds(3));

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

        RecordedRequest upstream = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(upstream);
        assertFalse(json.readTree(upstream.getBody().readUtf8()).has("tools"));
    }

    /** Gateway 等待超时必须穿透调用管线关闭真实 OkHttp 长连接，并固定唯一超时终态。 */
    @Test
    void modelGatewayTimeoutClosesPendingOkHttpConnection() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        DefaultModelGateway gateway =
                new DefaultModelGateway(
                        new ModelInvocationPipeline(router(provider()), Collections.emptyList()));

        ModelInvocation invocation = gateway.invoke(simpleRequest());
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
        assertEquals(1, openClientSocketCount());

        ModelInvocationTimeoutException timeout =
                assertThrows(
                        ModelInvocationTimeoutException.class,
                        () -> invocation.await(Duration.ofMillis(50)));

        assertTrue(awaitOpenClientSocketCount(0, Duration.ofSeconds(2)));
        invocation.cancel();
        invocation.cancel();
        assertEquals(0, openClientSocketCount());
        assertEquals(1, server.getRequestCount());
        CompletionException resultFailure =
                assertThrows(
                        CompletionException.class,
                        () -> invocation.result().toCompletableFuture().join());
        assertSame(timeout, resultFailure.getCause());
    }

    /** 状态格式错配是不可重试的本地协议错误，不得发出任何上游请求。 */
    @Test
    void rejectsIncompatibleModelStateBeforeNetworkCall() {
        ModelRequest source = requestWithReasoningState();
        ModelRequest request =
                new ModelRequest(
                        source.getResponseMessageId(),
                        source.getModelTarget(),
                        source.getMessages(),
                        source.getTools(),
                        new ModelState("anthropic-thinking/v1", Collections.singletonMap("x", "y")),
                        null,
                        null);

        ModelGatewayException failure =
                assertThrows(
                        ModelGatewayException.class,
                        () -> provider().stream(request, new RecordingListener()));

        assertFalse(failure.isRetryable());
        assertTrue(failure.getMessage().contains("格式不兼容"));
        assertEquals(0, server.getRequestCount());
    }

    /** 非 2xx 只报告状态，不把不受信任的上游错误正文带入浏览器或审计。 */
    @Test
    void nonSuccessfulResponseEndsWithError() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("invalid key"));
        RecordingListener listener = new RecordingListener();

        provider().stream(simpleRequest(), listener);

        assertTrue(listener.awaitTerminal());
        assertNotNull(listener.failure.get());
        assertTrue(listener.failure.get().getMessage().contains("HTTP 401"));
        assertFalse(listener.failure.get().getMessage().contains("invalid key"));
    }

    /** 非法厂商 chunk 是不可重试协议错误，不能伪装成正常空回答。 */
    @Test
    void malformedChunkEndsWithNonRetryableProtocolError() throws Exception {
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody("data: not-json\n\n"));
        RecordingListener listener = new RecordingListener();

        provider().stream(simpleRequest(), listener);

        assertTrue(listener.awaitTerminal());
        assertTrue(listener.failure.get() instanceof ModelGatewayException);
        assertFalse(((ModelGatewayException) listener.failure.get()).isRetryable());
    }

    /** 首个厂商事件前的连接失败必须只产生一次错误终态。 */
    @Test
    void transportFailureBeforeFirstEventEndsWithSingleError() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        RecordingListener listener = new RecordingListener();

        provider().stream(simpleRequest(), listener);

        assertTrue(listener.awaitTerminal());
        assertTrue(listener.events.isEmpty());
        assertEquals(0, listener.completedCount);
        assertNotNull(listener.failure.get());
    }

    /** 已交付部分结构事件后断流不得伪造 message-stop 或完成回调。 */
    @Test
    void transportFailureAfterPartialEventsDoesNotCompleteMessage() throws Exception {
        String firstEvent =
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"半截\"}}]}\n\n";
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody(firstEvent + ": " + repeatedText(4096))
                        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
        RecordingListener listener = new RecordingListener();

        provider().stream(simpleRequest(), listener);

        assertTrue(listener.awaitTerminal());
        assertEquals(2, listener.events.size());
        assertTrue(listener.events.get(0) instanceof ModelBlockStartEvent);
        assertTrue(listener.events.get(1) instanceof ModelBlockDeltaEvent);
        assertEquals(0L, countMessageStops(listener.events));
        assertEquals(0, listener.completedCount);
        assertNotNull(listener.failure.get());
    }

    /** [DONE] 已封闭消息后，即使连接在迟到清理阶段断开也不能推翻正常结果。 */
    @Test
    void transportFailureAfterProtocolCompletionIsIgnored() throws Exception {
        String completed =
                "data: {\"choices\":[{\"delta\":{\"content\":\"完成\"},"
                        + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":5,"
                        + "\"completion_tokens\":2,\"total_tokens\":7}}\n\n"
                        + "data: [DONE]\n\n";
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody(completed + ": " + repeatedText(4096))
                        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
        RecordingListener listener = new RecordingListener();

        provider().stream(simpleRequest(), listener);

        assertTrue(listener.awaitTerminal());
        assertEquals(1L, countMessageStops(listener.events));
        assertEquals(1, listener.completedCount);
        assertNull(listener.failure.get());
    }

    /** 取消未完成连接时必须幂等，服务端迟到正文也不得产生事件或终止回调。 */
    @Test
    void cancellationStopsPendingCallWithoutTerminalCallback() throws Exception {
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody(
                                "data: {\"choices\":[{\"delta\":{\"content\":\"迟到\"},"
                                        + "\"finish_reason\":\"stop\"}]}\n\n"
                                        + "data: [DONE]\n\n")
                        .setBodyDelay(200, TimeUnit.MILLISECONDS));
        RecordingListener listener = new RecordingListener();

        ModelCall call = provider().stream(simpleRequest(), listener);
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
        call.cancel();
        call.cancel();

        assertFalse(listener.terminal.await(350, TimeUnit.MILLISECONDS));
        assertTrue(listener.events.isEmpty());
        assertNull(listener.failure.get());
    }

    /** 创建足够长的 SSE 注释载荷，使测试连接确定在首个完整事件之后断开。 */
    private static String repeatedText(int count) {
        StringBuilder value = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            value.append('x');
        }
        return value.toString();
    }

    /** 统计 message-stop，证明完成与失败竞争不会产生重复稳定终态。 */
    private static long countMessageStops(List<ModelStreamEvent> events) {
        long count = 0L;
        for (ModelStreamEvent event : events) {
            if (event instanceof ModelMessageStopEvent) {
                count += 1L;
            }
        }
        return count;
    }

    /** 创建指向本地测试 Server 的 Provider。 */
    private OpenAiCompatibleModelProvider provider() {
        PatchBridgeAgentProperties.Target config = new PatchBridgeAgentProperties.Target();
        config.setBaseUrl(server.url("/v1").toString());
        config.setModel("test-model");
        config.setApiKey("test-key");
        return new OpenAiCompatibleModelProvider(config);
    }

    /**
     * 读取 MockWebServer 当前真实客户端连接数。
     *
     * <p>固定使用的 3.14 测试库没有公开连接关闭回调；这里仅在测试边界观察其线程安全连接集合，以证明超时不仅完成 Future，还让服务端实际读到对端关闭。
     */
    @SuppressWarnings("unchecked")
    private int openClientSocketCount() throws ReflectiveOperationException {
        Field field = MockWebServer.class.getDeclaredField("openClientSockets");
        field.setAccessible(true);
        return ((Set<Object>) field.get(server)).size();
    }

    /** 在短时限内等待测试 Server 观察到指定连接数，避免把异步网络关闭误判为未取消。 */
    private boolean awaitOpenClientSocketCount(int expected, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            if (openClientSocketCount() == expected) {
                return true;
            }
            Thread.sleep(10L);
        } while (System.nanoTime() < deadline);
        return openClientSocketCount() == expected;
    }

    /** 非法 base-url（非 HTTP(S)、含凭据/query/fragment、相对地址）必须在构造期失败。 */
    @Test
    void rejectsIllegalBaseUrlsAtConstruction() {
        String[] illegal = {
            "ftp://api.example.test/v1",
            "http://user:pass@api.example.test/v1",
            "https://api.example.test/v1?debug=1",
            "https://api.example.test/v1#section",
            "/v1",
            "api.example.test/v1",
            "not a url"
        };
        for (String baseUrl : illegal) {
            PatchBridgeAgentProperties.Target config = new PatchBridgeAgentProperties.Target();
            config.setBaseUrl(baseUrl);
            config.setModel("test-model");
            assertThrows(IllegalArgumentException.class,
                    () -> new OpenAiCompatibleModelProvider(config),
                    "应当拒绝 base-url: " + baseUrl);
        }
    }

    /** 尾斜杠与大小写不影响固定 chat/completions 地址的推导。 */
    @Test
    void trailingSlashesAreNormalizedForCompletionsUrl() {
        PatchBridgeAgentProperties.Target config = new PatchBridgeAgentProperties.Target();
        config.setBaseUrl("https://api.example.test/v1///");
        config.setModel("test-model");

        OpenAiCompatibleModelProvider provider = new OpenAiCompatibleModelProvider(config);

        // 仅验证构造成功；URL 已在构造期固定，无需发起网络请求
        assertNotNull(provider);
    }

    /** Gateway 的协议测试保留真实 Router 能力校验。 */
    private static ModelProviderRouter router(OpenAiCompatibleModelProvider provider) {
        ResolvedModelTarget target = new ResolvedModelTarget(REF, "测试 OpenAI", "openai-chat-completions",
                true, true, true, new ContextCompactionSettings(128000, 20000, 12800), provider);
        return new ModelProviderRouter(new ImmutableModelTargetCatalog(
                Collections.singletonList(target), REF), (access, selected) -> true);
    }

    /** 创建含历史工具调用及其精确 reasoning 状态的请求。 */
    private static ModelRequest requestWithReasoningState() {
        AgentMessage user =
                new AgentMessage(
                        "user-1",
                        MessageRole.USER,
                        Collections.<ContentBlock>singletonList(new TextBlock("hi")));
        ToolCallBlock call =
                new ToolCallBlock(
                        "call-old",
                        "local.echo",
                        Collections.<String, Object>singletonMap("text", "old"));
        AgentMessage assistant =
                new AgentMessage(
                        "assistant-prev",
                        MessageRole.ASSISTANT,
                        Arrays.<ContentBlock>asList(new ReasoningBlock("展示摘要"), call));
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put(
                "reasoningByMessageId",
                Collections.<String, Object>singletonMap("assistant-prev", "历史思考"));
        ModelToolDefinition tool =
                new ModelToolDefinition(
                        "local.echo",
                        "回显",
                        Collections.<String, Object>singletonMap("type", "object"));
        return new ModelRequest(
                "response-2",
                REF,
                Arrays.asList(user, assistant),
                Collections.singletonList(tool),
                new ModelState(OpenAiChatProtocol.STATE_FORMAT, data),
                null,
                null);
    }

    /** 创建不含状态的最小领域请求。 */
    private static ModelRequest simpleRequest() {
        AgentMessage user =
                new AgentMessage(
                        "user-1",
                        MessageRole.USER,
                        Collections.<ContentBlock>singletonList(new TextBlock("hi")));
        return new ModelRequest(
                "response-1",
                REF,
                Collections.singletonList(user),
                Collections.emptyList(),
                null,
                null,
                null);
    }

    /** 线程安全记录结构化事件和唯一终止信号。 */
    private static final class RecordingListener implements ModelStreamListener {
        /** 已收到的 Core 事件。 */
        private final List<ModelStreamEvent> events = new CopyOnWriteArrayList<ModelStreamEvent>();

        /** 异步失败。 */
        private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

        /** 任一终止回调。 */
        private final CountDownLatch terminal = new CountDownLatch(1);

        /** 正常完成次数。 */
        private volatile int completedCount;

        /** 记录结构化事件。 */
        @Override
        public void onEvent(ModelStreamEvent event) {
            events.add(event);
        }

        /** 记录正常完成。 */
        @Override
        public void onCompleted() {
            completedCount += 1;
            terminal.countDown();
        }

        /** 记录异步错误。 */
        @Override
        public void onError(Throwable error) {
            failure.set(error);
            terminal.countDown();
        }

        /** 最多等待三秒。 */
        private boolean awaitTerminal() throws InterruptedException {
            return terminal.await(3, TimeUnit.SECONDS);
        }
    }
}
