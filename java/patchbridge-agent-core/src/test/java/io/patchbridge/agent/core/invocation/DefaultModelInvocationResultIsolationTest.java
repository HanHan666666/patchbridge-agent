package io.patchbridge.agent.core.invocation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.patchbridge.agent.core.model.AgentMessage;
import io.patchbridge.agent.core.model.ContentBlock;
import io.patchbridge.agent.core.model.MessageRole;
import io.patchbridge.agent.core.model.ModelResponse;
import io.patchbridge.agent.core.model.ModelStopReason;
import io.patchbridge.agent.core.model.TextBlock;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** 验证公开 CompletionStage 的写操作与 Invocation 内部终态、上游取消完全隔离。 */
class DefaultModelInvocationResultIsolationTest {

    /** 外部完成一个公开投影只改变该投影；后续真实成功仍由 await 和新观察者一致接收。 */
    @Test
    void externalCompletionDoesNotReplaceRealSuccess() {
        DefaultModelInvocation invocation = new DefaultModelInvocation();
        AtomicInteger cancellations = new AtomicInteger();
        invocation.bind(cancellations::incrementAndGet);
        ModelResponse external = response("external");
        ModelResponse actual = response("actual");
        CompletableFuture<ModelResponse> firstView = invocation.result().toCompletableFuture();

        assertTrue(firstView.complete(external));
        invocation.succeed(actual);

        assertSame(external, firstView.join(), "调用方自行完成的旧投影保持自己的局部结果");
        assertSame(actual, invocation.result().toCompletableFuture().join());
        assertSame(actual, invocation.await(Duration.ofSeconds(1)));
        assertEquals(0, cancellations.get(), "外部 complete 不得取消真实上游");
    }

    /** 外部取消一个公开投影不触发上游；随后 Invocation 主动取消仍成为新观察者和 await 的共同终态。 */
    @Test
    void externalProjectionCancellationDoesNotCancelInvocation() {
        DefaultModelInvocation invocation = new DefaultModelInvocation();
        AtomicInteger cancellations = new AtomicInteger();
        invocation.bind(cancellations::incrementAndGet);
        CompletableFuture<ModelResponse> firstView = invocation.result().toCompletableFuture();

        assertTrue(firstView.cancel(true));
        assertTrue(firstView.isCancelled());
        assertEquals(0, cancellations.get(), "取消观察投影不得传播到真实上游");

        invocation.cancel();

        assertEquals(1, cancellations.get());
        assertThrows(
                CancellationException.class,
                () -> invocation.result().toCompletableFuture().join());
        assertThrows(
                CancellationException.class,
                () -> invocation.await(Duration.ofSeconds(1)));
        assertNotSame(
                firstView,
                invocation.result().toCompletableFuture(),
                "后续观察必须获得不受旧投影污染的新对象");
    }

    /** 创建具有独立文本值的最小完整响应。 */
    private static ModelResponse response(String text) {
        AgentMessage message =
                new AgentMessage(
                        "response-" + text,
                        MessageRole.ASSISTANT,
                        Collections.<ContentBlock>singletonList(new TextBlock(text)));
        return new ModelResponse(message, ModelStopReason.END_TURN, null, null);
    }
}
