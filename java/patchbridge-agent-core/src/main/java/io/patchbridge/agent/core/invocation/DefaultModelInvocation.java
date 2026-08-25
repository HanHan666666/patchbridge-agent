package io.patchbridge.agent.core.invocation;

import io.patchbridge.agent.core.error.ModelGatewayException;
import io.patchbridge.agent.core.error.ModelInvocationTimeoutException;
import io.patchbridge.agent.core.model.ModelCall;
import io.patchbridge.agent.core.model.ModelResponse;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 默认单次模型调用生命周期实现。
 *
 * <p>一个互斥锁线性化完成、失败、取消和上游句柄绑定，解决 Provider 在 {@code stream()} 返回句柄前同步回调的竞态；只有最先到达的终止原因能够完成结果。
 */
final class DefaultModelInvocation implements ModelInvocation, ModelResponseAssembler.Terminal {

    /** 上游绑定与唯一终止切换的串行化边界。 */
    private final Object lifecycleLock = new Object();

    /** 只完成一次的公共异步结果。 */
    private final CompletableFuture<ModelResponse> result =
            new CompletableFuture<ModelResponse>();

    /** Pipeline 返回的真实上游取消句柄；终止后立即释放引用。 */
    private ModelCall upstream;

    /** 本次调用的短生命周期响应聚合器；任一终态获胜后立即双向断开。 */
    private ModelResponseAssembler assembler;

    /** 完成、失败与取消共用的唯一终止标记。 */
    private boolean terminated;

    /** 协议失败或主动取消先于句柄绑定时，要求绑定后立即取消真实上游。 */
    private boolean cancelWhenBound;

    /**
     * 返回当前内部结果的独立观察投影。
     *
     * <p>CompletionStage 可通过 {@code toCompletableFuture()} 暴露写操作，因此不能直接返回内部 Future。每次调用创建新投影，外部 complete 或 cancel 只影响该投影，不会改写
     * Invocation 终态、取消上游或污染后续观察者。
     */
    @Override
    public CompletionStage<ModelResponse> result() {
        CompletableFuture<ModelResponse> projection =
                new CompletableFuture<ModelResponse>();
        result.whenComplete(
                (response, failure) -> {
                    if (failure == null) {
                        projection.complete(response);
                    } else {
                        projection.completeExceptionally(failure);
                    }
                });
        return projection;
    }

    /** 在指定时限内等待，并让超时或中断与其他终态在线性化门上竞争。 */
    @Override
    public ModelResponse await(Duration timeout) {
        if (timeout == null) {
            throw new IllegalArgumentException("timeout 不可为空");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 必须大于零");
        }
        final long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("timeout 超出支持范围", e);
        }
        try {
            return result.get(timeoutNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            ModelInvocationTimeoutException timeoutFailure =
                    new ModelInvocationTimeoutException("等待模型响应超时", e);
            if (terminateExceptionally(timeoutFailure, true)) {
                throw timeoutFailure;
            }
            return resolvedTerminalResult();
        } catch (InterruptedException e) {
            ModelGatewayException interruptionFailure =
                    new ModelGatewayException("等待模型响应时线程被中断", e, false);
            try {
                if (terminateExceptionally(interruptionFailure, true)) {
                    throw interruptionFailure;
                }
                return resolvedTerminalResult();
            } finally {
                Thread.currentThread().interrupt();
            }
        } catch (ExecutionException e) {
            throw propagate(e.getCause());
        }
    }

    /** 以 CancellationException 竞争唯一终态并关闭真实上游。 */
    @Override
    public void cancel() {
        terminateExceptionally(new CancellationException("模型调用已取消"), true);
    }

    /**
     * 绑定 Pipeline 的真实取消句柄。
     *
     * <p>Provider 可以在返回句柄前同步完成或暴露协议错误；这种情况下不再保存无用引用，并按已记录的取消意图立即关闭上游。
     */
    void bind(ModelCall call) {
        if (call == null) {
            throw new IllegalArgumentException("upstream call 不可为空");
        }
        boolean cancel;
        synchronized (lifecycleLock) {
            if (upstream != null) {
                throw new IllegalStateException("模型调用已绑定上游句柄");
            }
            cancel = terminated && cancelWhenBound;
            if (!terminated) {
                upstream = call;
            }
        }
        if (cancel) {
            cancelUpstream(call);
        }
    }

    /**
     * 关联本次调用唯一的响应聚合器。
     *
     * <p>关联发生在 Provider 启动前，使同步回调、主动取消和启动失败都能走同一个显式清理入口。
     */
    void bindAssembler(ModelResponseAssembler responseAssembler) {
        if (responseAssembler == null) {
            throw new IllegalArgumentException("responseAssembler 不可为空");
        }
        boolean discard;
        synchronized (lifecycleLock) {
            if (assembler != null) {
                throw new IllegalStateException("模型调用已关联响应聚合器");
            }
            discard = terminated;
            if (!terminated) {
                assembler = responseAssembler;
            }
        }
        if (discard) {
            responseAssembler.discard();
        }
    }

    /**
     * Provider 启动同步失败时释放尚未进入公共终态的聚合器关联。
     *
     * <p>该路径的异常由 invoke 同步抛出，Invocation 不会交给宿主，因此这里只负责关闭事件入口和解除引用。
     */
    void discardAssembler() {
        ModelResponseAssembler discarded;
        synchronized (lifecycleLock) {
            discarded = assembler;
            assembler = null;
        }
        discardAssembler(discarded);
    }

    /** 聚合成功时完成公共结果；迟到的成功信号不会覆盖既有终态。 */
    @Override
    public void succeed(ModelResponse response) {
        boolean accepted;
        ModelResponseAssembler completedAssembler = null;
        synchronized (lifecycleLock) {
            accepted = !terminated;
            if (accepted) {
                terminated = true;
                upstream = null;
                completedAssembler = assembler;
                assembler = null;
            }
        }
        if (accepted) {
            discardAssembler(completedAssembler);
            result.complete(response);
        }
    }

    /** 聚合或上游失败时完成异常结果，并按协议失败要求决定是否取消真实上游。 */
    @Override
    public void fail(Throwable failure, boolean cancelUpstream) {
        if (failure == null) {
            failure = new ModelGatewayException("模型调用以空异常失败", false);
        }
        terminateExceptionally(failure, cancelUpstream);
    }

    /**
     * 让一个异常原因在线性化终止门上竞争，并在获胜后完成结果及取消上游。
     *
     * <p>超时与线程中断必须直接参与该竞争，不能先观察 Future 超时、再普通取消；否则成功可能在两步之间插入，造成 await 报错但 result 最终成功。
     */
    private boolean terminateExceptionally(Throwable failure, boolean cancelUpstream) {
        ModelCall call;
        ModelResponseAssembler terminatedAssembler;
        synchronized (lifecycleLock) {
            if (terminated) {
                return false;
            }
            terminated = true;
            call = cancelUpstream ? upstream : null;
            cancelWhenBound = cancelUpstream && call == null;
            upstream = null;
            terminatedAssembler = assembler;
            assembler = null;
        }
        discardAssembler(terminatedAssembler);
        result.completeExceptionally(failure);
        try {
            cancelUpstream(call);
        } catch (RuntimeException cancelFailure) {
            failure.addSuppressed(cancelFailure);
        }
        return true;
    }

    /**
     * 在 Invocation 锁外关闭聚合器，保持两类生命周期锁之间不存在反向嵌套。
     *
     * <p>Assembler 的 discard 是幂等的，因此正常回调已经自行清理时再次调用也不会产生副作用。
     */
    private static void discardAssembler(ModelResponseAssembler responseAssembler) {
        if (responseAssembler != null) {
            responseAssembler.discard();
        }
    }

    /**
     * 在超时或中断输给既有终态时读取该终态，保证 await 与 result 对同一次线性化结果保持一致。
     *
     * <p>终态标记可能比 CompletableFuture 完成早几个指令，因此这里使用 join 等待获胜线程发布结果；它不会重新参与生命周期竞争。
     */
    private ModelResponse resolvedTerminalResult() {
        try {
            return result.join();
        } catch (CompletionException e) {
            throw propagate(e.getCause());
        }
    }

    /** 取消真实上游；ModelCall 的幂等契约保证该操作不会产生重复副作用。 */
    private static void cancelUpstream(ModelCall call) {
        if (call != null) {
            call.cancel();
        }
    }

    /** 把异步失败恢复为 await 的明确非受检异常，保留原始异常身份。 */
    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException) {
            return (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        return new ModelGatewayException("模型异步调用失败", failure, false);
    }
}
