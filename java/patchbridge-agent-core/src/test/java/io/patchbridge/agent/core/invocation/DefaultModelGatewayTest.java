package io.patchbridge.agent.core.invocation;

import io.patchbridge.agent.core.model.ModelTestTargets;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.error.ModelGatewayException;
import io.patchbridge.agent.core.error.ModelInvocationTimeoutException;
import io.patchbridge.agent.core.interceptor.ModelCallInterceptor;
import io.patchbridge.agent.core.model.BlockType;
import io.patchbridge.agent.core.model.ModelBlockDeltaEvent;
import io.patchbridge.agent.core.model.ModelBlockStartEvent;
import io.patchbridge.agent.core.model.ModelBlockStopEvent;
import io.patchbridge.agent.core.model.ModelCall;
import io.patchbridge.agent.core.model.ModelMessageStopEvent;
import io.patchbridge.agent.core.model.ModelProvider;
import io.patchbridge.agent.core.model.ModelRequest;
import io.patchbridge.agent.core.model.ModelRequests;
import io.patchbridge.agent.core.model.ModelResponse;
import io.patchbridge.agent.core.model.ModelStopReason;
import io.patchbridge.agent.core.model.ModelStreamListener;
import io.patchbridge.agent.core.model.ModelToolDefinition;
import io.patchbridge.agent.core.user.UserContext;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 验证默认 Gateway 的单次调用、上下文、异常和真实上游生命周期契约。 */
class DefaultModelGatewayTest {

    /** Provider 即使在返回句柄前同步完成，Invocation 仍能返回无损完整结果。 */
    @Test
    void supportsSynchronousProviderCompletionBeforeCallBinding() {
        AtomicInteger calls = new AtomicInteger();
        ModelProvider provider =
                new ModelProvider() {
                    @Override
                    public ModelCall stream(ModelRequest request, ModelStreamListener listener) {
                        calls.incrementAndGet();
                        emitTextResponse(listener, "完成");
                        return () -> {};
                    }
                };

        ModelResponse response = gateway(provider).invoke(request()).await(Duration.ofSeconds(1));

        assertEquals(1, calls.get(), "一次 Gateway 调用最多启动一次 Provider");
        assertEquals("完成", response.getText());
        assertEquals(ModelStopReason.END_TURN, response.getStopReason());
    }

    /** 无上下文重载只创建临时 traceId，不猜测用户身份或 Web 请求字段。 */
    @Test
    void createsAnonymousTemporaryContext() {
        AtomicReference<AiRequestContext> received = new AtomicReference<AiRequestContext>();
        ModelCallInterceptor interceptor =
                new ModelCallInterceptor() {
                    @Override
                    public void before(ModelRequest request, AiRequestContext context) {
                        received.set(context);
                    }

                    @Override
                    public void after(
                            ModelRequest request,
                            AiRequestContext context,
                            boolean success,
                            long durationMs,
                            Throwable failure) {}
                };
        ModelProvider provider =
                (request, listener) -> {
                    emitTextResponse(listener, "ok");
                    return () -> {};
                };
        ModelInvocationPipeline pipeline =
                new ModelInvocationPipeline(ModelTestTargets.router(provider), Collections.singletonList(interceptor));

        new DefaultModelGateway(pipeline).invoke(request()).await(Duration.ofSeconds(1));

        assertNull(received.get().getUser());
        assertTrue(received.get().getTraceId() != null && !received.get().getTraceId().isEmpty());
        assertNull(received.get().getRequestId());
        assertNull(received.get().getConversationId());
    }

    /** 非空 Tool 定义在进入 Pipeline 前明确拒绝，Provider 不得被启动。 */
    @Test
    void rejectsToolDefinitionsBeforeProviderInvocation() {
        AtomicInteger calls = new AtomicInteger();
        ModelProvider provider =
                (request, listener) -> {
                    calls.incrementAndGet();
                    return () -> {};
                };
        ModelRequest request =
                new ModelRequest(
                        "response-1",
                        ModelTestTargets.REF,
                        Collections.emptyList(),
                        Collections.singletonList(
                                new ModelToolDefinition(
                                        "local.test",
                                        "test",
                                        Collections.<String, Object>emptyMap())),
                        null,
                        null,
                        null);

        assertThrows(IllegalArgumentException.class, () -> gateway(provider).invoke(request));
        assertEquals(0, calls.get());
    }

    /** Provider 启动阶段的非网关异常统一转换为同步且不可重试的网关异常。 */
    @Test
    void wrapsSynchronousProviderStartupFailure() {
        IllegalStateException failure = new IllegalStateException("broken provider");
        ModelProvider provider =
                (request, listener) -> {
                    throw failure;
                };

        ModelGatewayException thrown =
                assertThrows(ModelGatewayException.class, () -> gateway(provider).invoke(request()));

        assertSame(failure, thrown.getCause());
        assertFalse(thrown.isRetryable());
    }

    /** Provider 在返回句柄前同步发布非法事件时，绑定阶段仍必须落实聚合器记录的上游取消意图。 */
    @Test
    void protocolFailureBeforeCallBindingCancelsReturnedUpstream() {
        AtomicInteger cancellations = new AtomicInteger();
        ModelProvider provider =
                (request, listener) -> {
                    listener.onEvent(
                            new ModelBlockDeltaEvent(
                                    0,
                                    ModelBlockDeltaEvent.Delta.text(BlockType.TEXT, "invalid")));
                    return cancellations::incrementAndGet;
                };

        ModelInvocation invocation = gateway(provider).invoke(request());

        ModelGatewayException failure =
                assertThrows(
                        ModelGatewayException.class,
                        () -> invocation.await(Duration.ofSeconds(1)));
        assertFalse(failure.isRetryable());
        assertEquals(1, cancellations.get());
    }

    /** 主动取消真实上游且保持幂等，迟到完成不能覆盖 CancellationException。 */
    @Test
    void cancellationClosesUpstreamOnceAndFencesLateCompletion() {
        AtomicInteger cancellations = new AtomicInteger();
        AtomicReference<ModelStreamListener> listenerRef =
                new AtomicReference<ModelStreamListener>();
        ModelProvider provider =
                (request, listener) -> {
                    listenerRef.set(listener);
                    return cancellations::incrementAndGet;
                };
        ModelInvocation invocation = gateway(provider).invoke(request());

        invocation.cancel();
        invocation.cancel();
        emitTextResponse(listenerRef.get(), "late");

        assertEquals(1, cancellations.get());
        assertThrows(
                CancellationException.class,
                () -> invocation.result().toCompletableFuture().join());
    }

    /** await 超时以专用异常赢得唯一终态并取消真实上游，不返回部分结果。 */
    @Test
    void timeoutCancelsRealUpstream() {
        AtomicInteger cancellations = new AtomicInteger();
        ModelProvider provider = (request, listener) -> cancellations::incrementAndGet;
        ModelInvocation invocation = gateway(provider).invoke(request());

        ModelInvocationTimeoutException timeoutFailure =
                assertThrows(
                        ModelInvocationTimeoutException.class,
                        () -> invocation.await(Duration.ofMillis(10)));

        assertEquals(1, cancellations.get());
        CompletionException resultFailure =
                assertThrows(
                        CompletionException.class,
                        () -> invocation.result().toCompletableFuture().join());
        assertSame(timeoutFailure, resultFailure.getCause());
    }

    /** await 线程中断会取消上游、恢复中断标记，并返回明确失败。 */
    @Test
    void interruptionCancelsUpstreamAndRestoresInterruptFlag() throws Exception {
        AtomicInteger cancellations = new AtomicInteger();
        ModelProvider provider = (request, listener) -> cancellations::incrementAndGet;
        ModelInvocation invocation = gateway(provider).invoke(request());
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread thread =
                new Thread(
                        () -> {
                            waiting.countDown();
                            try {
                                invocation.await(Duration.ofSeconds(30));
                            } catch (Throwable e) {
                                failure.set(e);
                                interrupted.set(Thread.currentThread().isInterrupted());
                            }
                        });

        thread.start();
        assertTrue(waiting.await(1, TimeUnit.SECONDS));
        thread.interrupt();
        thread.join(5000);

        assertFalse(thread.isAlive());
        assertTrue(failure.get() instanceof ModelGatewayException);
        assertTrue(interrupted.get());
        assertEquals(1, cancellations.get());
    }

    /** 异步 Provider 失败通过结果原样暴露，框架不会自动重试或改写异常身份。 */
    @Test
    void preservesAsynchronousProviderFailure() {
        IllegalStateException failure = new IllegalStateException("upstream failed");
        ModelProvider provider =
                (request, listener) -> {
                    listener.onError(failure);
                    return () -> {};
                };

        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () -> gateway(provider).invoke(request()).await(Duration.ofSeconds(1)));

        assertSame(failure, thrown);
    }

    /** 创建只包含文本输入的最小请求。 */
    private static ModelRequest request() {
        return ModelRequests.builder()
                .modelTarget(ModelTestTargets.REF)
                .responseMessageId("response-1")
                .userText("hello")
                .build();
    }

    /** 创建不带拦截器和额外能力的默认 Gateway。 */
    private static DefaultModelGateway gateway(ModelProvider provider) {
        return new DefaultModelGateway(
                new ModelInvocationPipeline(ModelTestTargets.router(provider), Collections.emptyList()));
    }

    /** 发布一条协议完整的文本响应。 */
    private static void emitTextResponse(ModelStreamListener listener, String text) {
        listener.onEvent(
                new ModelBlockStartEvent(
                        0, ModelBlockStartEvent.Block.content(BlockType.TEXT)));
        listener.onEvent(
                new ModelBlockDeltaEvent(
                        0, ModelBlockDeltaEvent.Delta.text(BlockType.TEXT, text)));
        listener.onEvent(new ModelBlockStopEvent(0));
        listener.onEvent(new ModelMessageStopEvent(ModelStopReason.END_TURN, null, null));
        listener.onCompleted();
    }
}
