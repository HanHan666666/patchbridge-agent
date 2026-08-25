package io.patchbridge.agent.core.invocation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.patchbridge.agent.core.error.ModelInvocationTimeoutException;
import io.patchbridge.agent.core.model.AgentMessage;
import io.patchbridge.agent.core.model.ContentBlock;
import io.patchbridge.agent.core.model.MessageRole;
import io.patchbridge.agent.core.model.ModelResponse;
import io.patchbridge.agent.core.model.ModelStopReason;
import io.patchbridge.agent.core.model.TextBlock;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 验证 await 超时观察与生命周期终态切换之间的并发窗口。
 *
 * <p>测试直接操作包内实现，并借助反射持有私有生命周期锁，只为确定性重现两个操作之间的精确窗口；生产代码不因此暴露测试钩子或额外扩展点。
 */
class DefaultModelInvocationRaceTest {

    /** Future.get 已超时但尚未取得终止锁时，先完成的成功必须成为 await 与 result 的共同结果。 */
    @Test
    void completedResultWinsAfterGetTimeoutButBeforeTimeoutTermination() throws Exception {
        DefaultModelInvocation invocation = new DefaultModelInvocation();
        ModelResponse expected = response("success");
        Object lifecycleLock = lifecycleLock(invocation);
        CountDownLatch awaitStarted = new CountDownLatch(1);
        AtomicReference<ModelResponse> actual = new AtomicReference<ModelResponse>();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread awaitThread =
                new Thread(
                        () -> {
                            awaitStarted.countDown();
                            try {
                                actual.set(invocation.await(Duration.ofMillis(20)));
                            } catch (Throwable e) {
                                failure.set(e);
                            }
                        });

        boolean blockedAtTermination;
        synchronized (lifecycleLock) {
            awaitThread.start();
            assertTrue(awaitStarted.await(1, TimeUnit.SECONDS));
            blockedAtTermination = waitUntilBlocked(awaitThread, Duration.ofSeconds(2));
            if (blockedAtTermination) {
                invocation.succeed(expected);
            }
        }
        awaitThread.join(5000);

        assertTrue(blockedAtTermination, "await 线程应在 get 超时后阻塞于终止锁");
        assertFalse(awaitThread.isAlive());
        assertNull(failure.get());
        assertSame(expected, actual.get());
        assertSame(expected, invocation.result().toCompletableFuture().join());
    }

    /** timeout 先赢得终止锁后，迟到成功不得覆盖专用超时结果，真实上游只取消一次。 */
    @Test
    void timeoutWinnerDropsLateSuccess() {
        DefaultModelInvocation invocation = new DefaultModelInvocation();
        AtomicInteger cancellations = new AtomicInteger();
        invocation.bind(cancellations::incrementAndGet);

        ModelInvocationTimeoutException timeoutFailure =
                assertThrows(
                        ModelInvocationTimeoutException.class,
                        () -> invocation.await(Duration.ofMillis(10)));
        invocation.succeed(response("late"));

        assertEquals(1, cancellations.get());
        CompletionException resultFailure =
                assertThrows(
                        CompletionException.class,
                        () -> invocation.result().toCompletableFuture().join());
        assertSame(timeoutFailure, resultFailure.getCause());
    }

    /** 读取测试目标的真实线性化锁，避免为了测试向生产实现增加生命周期钩子。 */
    private static Object lifecycleLock(DefaultModelInvocation invocation) throws Exception {
        Field field = DefaultModelInvocation.class.getDeclaredField("lifecycleLock");
        field.setAccessible(true);
        return field.get(invocation);
    }

    /** 等待线程进入锁竞争状态；该状态只会在 Future.get 已超时后出现。 */
    private static boolean waitUntilBlocked(Thread thread, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (thread.getState() == Thread.State.BLOCKED) {
                return true;
            }
            Thread.yield();
        }
        return false;
    }

    /** 创建最小完整 assistant 响应。 */
    private static ModelResponse response(String text) {
        AgentMessage message =
                new AgentMessage(
                        "response-1",
                        MessageRole.ASSISTANT,
                        Collections.<ContentBlock>singletonList(new TextBlock(text)));
        return new ModelResponse(message, ModelStopReason.END_TURN, null, null);
    }
}
