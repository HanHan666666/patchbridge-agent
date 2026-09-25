package io.patchbridge.agent.core.invocation;

import io.patchbridge.agent.core.model.ModelTestTargets;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.interceptor.ModelCallInterceptor;
import io.patchbridge.agent.core.model.BlockType;
import io.patchbridge.agent.core.model.ModelBlockDeltaEvent;
import io.patchbridge.agent.core.model.ModelCall;
import io.patchbridge.agent.core.model.ModelProvider;
import io.patchbridge.agent.core.model.ModelRequest;
import io.patchbridge.agent.core.model.ModelStreamEvent;
import io.patchbridge.agent.core.model.ModelStreamListener;
import io.patchbridge.agent.core.user.UserContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * 取消与事件转发的串行化边界测试（二次审计 Q-09）。
 *
 * <p>栅栏让“事件正卡在下游转发中”与“取消正在终止”形成真实并发窗口：修复前
 * 取消可以越过未完成的事件转发先完成终止切换，事件发布与终止之间没有线性化顺序。
 */
class ModelInvocationPipelineCancellationTest {

    /** 事件转发进行中时，取消必须等待转发结束才能完成终止切换，且 after 与 Provider cancel 都恰执行一次。 */
    @Test
    void cancelWaitsForInFlightEventAndRunsAfterExactlyOnce() throws Exception {
        CountDownLatch eventEntered = new CountDownLatch(1);
        CountDownLatch releaseEvent = new CountDownLatch(1);
        List<String> delegateLog = Collections.synchronizedList(new ArrayList<String>());
        AtomicInteger afterCalls = new AtomicInteger();
        AtomicInteger providerCancels = new AtomicInteger();
        AtomicReference<ModelStreamListener> providerListener = new AtomicReference<ModelStreamListener>();

        ModelProvider provider = new ModelProvider() {
            @Override
            public ModelCall stream(ModelRequest request, ModelStreamListener listener) {
                providerListener.set(listener);
                return new ModelCall() {
                    @Override
                    public void cancel() {
                        providerCancels.incrementAndGet();
                    }
                };
            }
        };
        ModelCallInterceptor counting = new ModelCallInterceptor() {
            @Override
            public void before(ModelRequest request, AiRequestContext context) {}

            @Override
            public void after(ModelRequest request, AiRequestContext context, boolean success,
                              long durationMs, Throwable failure) {
                afterCalls.incrementAndGet();
            }
        };
        ModelInvocationPipeline pipeline = new ModelInvocationPipeline(ModelTestTargets.router(provider), Collections.singletonList(counting));
        ModelStreamListener delegate = new ModelStreamListener() {
            @Override
            public void onEvent(ModelStreamEvent event) {
                delegateLog.add("event");
                eventEntered.countDown();
                try {
                    assertTrue(releaseEvent.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public void onCompleted() {
                delegateLog.add("completed");
            }

            @Override
            public void onError(Throwable error) {
                delegateLog.add("error");
            }
        };

        ModelCall call = pipeline.stream(modelRequest(), context(), delegate);
        ModelStreamListener terminal = providerListener.get();

        Thread eventThread = new Thread(() -> terminal.onEvent(deltaEvent()));
        eventThread.start();
        assertTrue(eventEntered.await(5, TimeUnit.SECONDS), "事件应已进入下游转发");

        Thread cancelThread = new Thread(call::cancel);
        cancelThread.start();
        Thread.sleep(200);
        assertEquals(0, afterCalls.get(), "事件转发未结束时取消不得先完成终止切换（发布与终止必须线性化）");

        releaseEvent.countDown();
        eventThread.join(5000);
        cancelThread.join(5000);
        assertFalse(eventThread.isAlive());
        assertFalse(cancelThread.isAlive());

        assertEquals(Collections.singletonList("event"), delegateLog, "终止前开始的事件允许送达");
        assertEquals(1, afterCalls.get(), "after 恰执行一次");
        assertEquals(1, providerCancels.get(), "Provider cancel 恰执行一次");

        // 终止后：新事件全部丢弃，完成/失败不再转发，重复取消无副作用。
        terminal.onEvent(deltaEvent());
        terminal.onCompleted();
        call.cancel();
        assertEquals(Collections.singletonList("event"), delegateLog, "取消后不得再发布任何事件");
        assertEquals(1, afterCalls.get());
        assertEquals(1, providerCancels.get(), "重复 cancel 必须保持幂等");
    }

    /** 失败终止后同样关闭事件入口：迟到事件不得触达下游。 */
    @Test
    void lateEventsAfterErrorTerminationAreDropped() {
        AtomicInteger delegateEvents = new AtomicInteger();
        AtomicInteger afterCalls = new AtomicInteger();
        AtomicReference<ModelStreamListener> providerListener = new AtomicReference<ModelStreamListener>();

        ModelProvider provider = new ModelProvider() {
            @Override
            public ModelCall stream(ModelRequest request, ModelStreamListener listener) {
                providerListener.set(listener);
                return () -> {};
            }
        };
        ModelCallInterceptor counting = new ModelCallInterceptor() {
            @Override
            public void before(ModelRequest request, AiRequestContext context) {}

            @Override
            public void after(ModelRequest request, AiRequestContext context, boolean success,
                              long durationMs, Throwable failure) {
                afterCalls.incrementAndGet();
            }
        };
        ModelInvocationPipeline pipeline = new ModelInvocationPipeline(ModelTestTargets.router(provider), Collections.singletonList(counting));
        ModelStreamListener delegate = new ModelStreamListener() {
            @Override
            public void onEvent(ModelStreamEvent event) {
                delegateEvents.incrementAndGet();
            }

            @Override
            public void onCompleted() {}

            @Override
            public void onError(Throwable error) {}
        };

        pipeline.stream(modelRequest(), context(), delegate);
        ModelStreamListener terminal = providerListener.get();

        terminal.onEvent(deltaEvent());
        assertEquals(1, delegateEvents.get());

        terminal.onError(new IllegalStateException("upstream failed"));
        terminal.onEvent(deltaEvent());
        terminal.onEvent(deltaEvent());
        terminal.onCompleted();

        assertEquals(1, delegateEvents.get(), "失败终止后不得再转发任何事件");
        assertEquals(1, afterCalls.get(), "失败路径 after 恰执行一次");
    }

    /** 创建不含业务数据的最小模型请求。 */
    private static ModelRequest modelRequest() {
        return new ModelRequest("response-1", ModelTestTargets.REF, Collections.emptyList(), Collections.emptyList(),
                null, null, null);
    }

    /** 创建服务端可信调用上下文。 */
    private static AiRequestContext context() {
        UserContext user = UserContext.builder().userId("u-1").tenantId("t-1").build();
        return new AiRequestContext(user, "trace-1", "request-1", null, "c-1");
    }

    /** 创建最小文本增量事件。 */
    private static ModelStreamEvent deltaEvent() {
        return new ModelBlockDeltaEvent(
                0, ModelBlockDeltaEvent.Delta.text(BlockType.TEXT, "x"));
    }
}
