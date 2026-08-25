package io.patchbridge.agent.core.invocation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.patchbridge.agent.core.error.ModelGatewayException;
import io.patchbridge.agent.core.error.ModelInvocationTimeoutException;
import io.patchbridge.agent.core.model.BlockType;
import io.patchbridge.agent.core.model.ModelBlockDeltaEvent;
import io.patchbridge.agent.core.model.ModelBlockStartEvent;
import io.patchbridge.agent.core.model.ModelBlockStopEvent;
import io.patchbridge.agent.core.model.ModelMessageStopEvent;
import io.patchbridge.agent.core.model.ModelStopReason;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 验证 Invocation 任一终态都会显式关闭响应聚合器并解除双向引用。 */
class ModelResponseAssemblerCleanupTest {

    /** 主动取消必须清空部分 blocks、关闭迟到事件入口并释放双方引用。 */
    @Test
    void cancellationClearsPartialBlocksAndFencesLateEvents() throws Exception {
        AssemblyFixture fixture = fixture();
        AtomicInteger cancellations = new AtomicInteger();
        fixture.invocation.bind(cancellations::incrementAndGet);
        fixture.assembler.onEvent(textStart());
        fixture.assembler.onEvent(textDelta("partial"));

        assertEquals(1, blocks(fixture.assembler).size());
        assertSame(fixture.assembler, field(fixture.invocation, "assembler"));

        fixture.invocation.cancel();

        assertEquals(1, cancellations.get());
        assertTrue(blocks(fixture.assembler).isEmpty());
        assertNull(field(fixture.assembler, "completedResponse"));
        assertNull(field(fixture.assembler, "terminal"));
        assertNull(field(fixture.invocation, "assembler"));
        assertTrue((Boolean) field(fixture.assembler, "terminated"));

        fixture.assembler.onEvent(textStart());
        fixture.assembler.onEvent(textDelta("late"));
        assertTrue(blocks(fixture.assembler).isEmpty(), "取消后的迟到事件不得重新建立聚合状态");
        assertThrows(
                CancellationException.class,
                () -> fixture.invocation.await(Duration.ofSeconds(1)));
    }

    /** message-stop 已冻结的候选响应在主动取消时也必须释放，不能等待 Provider 释放 listener。 */
    @Test
    void cancellationClearsCompletedResponseWaitingForProviderCompletion() throws Exception {
        AssemblyFixture fixture = fixture();
        fixture.invocation.bind(() -> {});
        emitMessageStopWithoutCompletion(fixture.assembler);

        assertTrue(field(fixture.assembler, "completedResponse") != null);

        fixture.invocation.cancel();

        assertNull(field(fixture.assembler, "completedResponse"));
        assertTrue(blocks(fixture.assembler).isEmpty());
        assertNull(field(fixture.assembler, "terminal"));
        assertNull(field(fixture.invocation, "assembler"));
    }

    /** await 超时通过同一终止门清理部分聚合状态，而不是只取消 Provider 连接。 */
    @Test
    void timeoutClearsPartialAssembly() throws Exception {
        AssemblyFixture fixture = fixture();
        AtomicInteger cancellations = new AtomicInteger();
        fixture.invocation.bind(cancellations::incrementAndGet);
        fixture.assembler.onEvent(textStart());
        fixture.assembler.onEvent(textDelta("partial"));

        assertThrows(
                ModelInvocationTimeoutException.class,
                () -> fixture.invocation.await(Duration.ofMillis(10)));

        assertEquals(1, cancellations.get());
        assertDiscarded(fixture);
    }

    /** await 线程中断同样清理聚合器、取消上游并恢复线程中断标记。 */
    @Test
    void interruptionClearsPartialAssembly() throws Exception {
        AssemblyFixture fixture = fixture();
        AtomicInteger cancellations = new AtomicInteger();
        fixture.invocation.bind(cancellations::incrementAndGet);
        fixture.assembler.onEvent(textStart());
        fixture.assembler.onEvent(textDelta("partial"));
        CountDownLatch awaiting = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread thread =
                new Thread(
                        () -> {
                            awaiting.countDown();
                            try {
                                fixture.invocation.await(Duration.ofSeconds(30));
                            } catch (Throwable e) {
                                failure.set(e);
                                interrupted.set(Thread.currentThread().isInterrupted());
                            }
                        });

        thread.start();
        assertTrue(awaiting.await(1, TimeUnit.SECONDS));
        thread.interrupt();
        thread.join(5000);

        assertTrue(failure.get() instanceof ModelGatewayException);
        assertTrue(interrupted.get());
        assertEquals(1, cancellations.get());
        assertDiscarded(fixture);
    }

    /** 正常成功和 Provider 失败同样清理关联，避免仅在主动取消路径修复引用滞留。 */
    @Test
    void successAndFailureBothReleaseAssemblerAssociation() throws Exception {
        AssemblyFixture succeeded = fixture();
        succeeded.invocation.bind(() -> {});
        emitMessageStopWithoutCompletion(succeeded.assembler);
        succeeded.assembler.onCompleted();

        assertNull(field(succeeded.invocation, "assembler"));
        assertNull(field(succeeded.assembler, "terminal"));
        assertTrue(blocks(succeeded.assembler).isEmpty());
        assertEquals(
                "complete", succeeded.invocation.await(Duration.ofSeconds(1)).getText());

        AssemblyFixture failed = fixture();
        failed.invocation.bind(() -> {});
        IllegalStateException failure = new IllegalStateException("upstream failed");
        failed.assembler.onEvent(textStart());
        failed.assembler.onError(failure);

        assertNull(field(failed.invocation, "assembler"));
        assertNull(field(failed.assembler, "terminal"));
        assertTrue(blocks(failed.assembler).isEmpty());
        assertSame(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () -> failed.invocation.await(Duration.ofSeconds(1))));
    }

    /** 创建已经建立双向关联、但尚未启动 Provider 的最小测试组合。 */
    private static AssemblyFixture fixture() {
        DefaultModelInvocation invocation = new DefaultModelInvocation();
        ModelResponseAssembler assembler =
                new ModelResponseAssembler("response-1", invocation);
        invocation.bindAssembler(assembler);
        return new AssemblyFixture(invocation, assembler);
    }

    /** 发布完整 message-stop，但保留 Provider onCompleted 尚未到达的中间窗口。 */
    private static void emitMessageStopWithoutCompletion(ModelResponseAssembler assembler) {
        assembler.onEvent(textStart());
        assembler.onEvent(textDelta("complete"));
        assembler.onEvent(new ModelBlockStopEvent(0));
        assembler.onEvent(new ModelMessageStopEvent(ModelStopReason.END_TURN, null, null));
    }

    /** 创建最小文本块开始事件。 */
    private static ModelBlockStartEvent textStart() {
        return new ModelBlockStartEvent(
                0, ModelBlockStartEvent.Block.content(BlockType.TEXT));
    }

    /** 创建最小文本增量事件。 */
    private static ModelBlockDeltaEvent textDelta(String text) {
        return new ModelBlockDeltaEvent(
                0, ModelBlockDeltaEvent.Delta.text(BlockType.TEXT, text));
    }

    /** 读取私有生命周期字段，避免为了测试向 Core 公共或包内 API 增加状态查询入口。 */
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    /** 读取聚合器当前 blocks，仅用于验证取消后实际释放中间态。 */
    @SuppressWarnings("unchecked")
    private static Map<Integer, Object> blocks(ModelResponseAssembler assembler) throws Exception {
        return (Map<Integer, Object>) field(assembler, "blocks");
    }

    /** 校验聚合中间态、回调引用和 Invocation 关联已经全部释放。 */
    private static void assertDiscarded(AssemblyFixture fixture) throws Exception {
        assertTrue(blocks(fixture.assembler).isEmpty());
        assertNull(field(fixture.assembler, "completedResponse"));
        assertNull(field(fixture.assembler, "terminal"));
        assertNull(field(fixture.invocation, "assembler"));
        assertTrue((Boolean) field(fixture.assembler, "terminated"));
    }

    /** 保存一次测试中的 Invocation 与 Assembler 组合。 */
    private static final class AssemblyFixture {

        /** 测试目标 Invocation。 */
        private final DefaultModelInvocation invocation;

        /** 与 Invocation 唯一关联的聚合器。 */
        private final ModelResponseAssembler assembler;

        /** 创建固定的一对测试对象。 */
        private AssemblyFixture(
                DefaultModelInvocation invocation, ModelResponseAssembler assembler) {
            this.invocation = invocation;
            this.assembler = assembler;
        }
    }
}
