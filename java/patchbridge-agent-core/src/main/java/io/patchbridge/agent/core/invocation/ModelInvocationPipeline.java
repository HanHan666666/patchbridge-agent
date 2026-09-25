package io.patchbridge.agent.core.invocation;

import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.error.ModelGatewayException;
import io.patchbridge.agent.core.interceptor.ModelCallInterceptor;
import io.patchbridge.agent.core.model.ModelCall;
import io.patchbridge.agent.core.model.target.ModelProviderRouter;
import io.patchbridge.agent.core.model.target.ModelAccessContext;
import io.patchbridge.agent.core.model.target.ResolvedModelTarget;
import io.patchbridge.agent.core.model.ModelRequest;
import io.patchbridge.agent.core.model.ModelStreamEvent;
import io.patchbridge.agent.core.model.ModelStreamListener;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模型调用应用服务：统一执行前后置拦截器，并管理异步调用的单次终止语义。
 *
 * <p>该类不绑定线程模型和 HTTP 技术。Provider 的完成、失败与主动取消都会在此 收敛，保证限流、成本记账和告警不会因 MVC / WebFlux Adapter 不同而旁路。
 */
public final class ModelInvocationPipeline {

    /** 实际模型协议与传输 Adapter。 */
    private final ModelProviderRouter router;

    /** 构造时冻结的模型拦截器顺序。 */
    private final List<ModelCallInterceptor> interceptors;

    /** 创建不可变的模型调用管线快照。 */
    public ModelInvocationPipeline(
            ModelProviderRouter router, List<ModelCallInterceptor> interceptors) {
        this.router = router;
        this.interceptors = new ArrayList<ModelCallInterceptor>(interceptors);
    }

    /** 启动异步模型流并返回统一取消句柄。 before 按注册顺序进入，after 在完成、失败或取消时按相反顺序退出。 */
    public ModelCall stream(
            ModelRequest request, AiRequestContext context, ModelStreamListener listener) {
        return stream(request, context, ModelAccessContext.authenticated(context.getUser()), listener);
    }

    /** Java 系统调用显式提供可信来源，HTTP 入口不得使用该来源替代用户认证。 */
    public ModelCall stream(ModelRequest input, AiRequestContext context,
            ModelAccessContext access, ModelStreamListener listener) {
        ResolvedModelTarget target = router.resolve(input.getModelTarget(), access);
        ModelRequest request = router.prepare(target, input);
        long start = System.currentTimeMillis();
        int entered = 0;
        try {
            for (ModelCallInterceptor interceptor : interceptors) {
                interceptor.before(request, context);
                entered += 1;
            }
        } catch (Exception e) {
            RuntimeException afterFailure =
                    invokeAfter(
                            request,
                            context,
                            false,
                            System.currentTimeMillis() - start,
                            e,
                            entered);
            if (afterFailure != null) {
                e.addSuppressed(afterFailure);
            }
            throw new ModelGatewayException("模型前置拦截器执行失败", e, false);
        }

        TerminalListener terminal =
                new TerminalListener(request, context, listener, start, entered);
        final ModelCall providerCall;
        try {
            providerCall = target.getAdapter().stream(request, terminal);
        } catch (RuntimeException e) {
            terminal.failBeforeStart(e);
            throw e;
        }
        if (providerCall == null) {
            ModelGatewayException failure =
                    new ModelGatewayException("ModelProvider 违反契约：未返回取消句柄", false);
            terminal.failBeforeStart(failure);
            throw failure;
        }
        return new ModelCall() {
            private final AtomicBoolean cancelled = new AtomicBoolean(false);

            /** 先收敛拦截器状态，再取消真实上游，避免 Provider 不回调造成调用悬挂。 */
            @Override
            public void cancel() {
                if (cancelled.compareAndSet(false, true)) {
                    terminal.cancel();
                    providerCall.cancel();
                }
            }
        };
    }

    /** 逆序退出已成功进入的模型拦截器，并合并多个后置失败。 */
    private RuntimeException invokeAfter(
            ModelRequest request,
            AiRequestContext context,
            boolean success,
            long durationMs,
            Throwable failure,
            int entered) {
        RuntimeException combined = null;
        for (int index = entered - 1; index >= 0; index -= 1) {
            try {
                interceptors.get(index).after(request, context, success, durationMs, failure);
            } catch (RuntimeException e) {
                if (combined == null) {
                    combined = e;
                } else {
                    combined.addSuppressed(e);
                }
            }
        }
        return combined;
    }

    /**
     * Provider 监听器代理：AtomicBoolean 保证并发完成/失败/取消只有一个终止者。 after 在向外发布终止信号前执行，后置失败因此仍能转换为明确的模型失败。
     *
     * <p>事件转发与终止切换共用 lifecycleLock 形成线性化边界（二次审计 Q-09）： 此前“先读 terminated 再转发”在两步之间并发取消时，仍可能向下游发布取消
     * 之后的迟到事件。锁序约定为 本锁 → 下游会话锁 单向获取；下游在转发中 回调取消属于同线程重入，安全通过。
     */
    private final class TerminalListener implements ModelStreamListener {
        /** 事件发布与终止切换的串行化边界，不承载其他职责。 */
        private final Object lifecycleLock = new Object();

        /** 本次不可变模型请求。 */
        private final ModelRequest request;

        /** 由服务端创建的可信调用上下文。 */
        private final AiRequestContext context;

        /** Controller 或宿主提供的下游监听器。 */
        private final ModelStreamListener delegate;

        /** 调用开始时间，用于统一计算拦截器耗时。 */
        private final long start;

        /** before 已成功进入的拦截器数量。 */
        private final int entered;

        /** 完成、失败和取消的唯一终止门。 */
        private final AtomicBoolean terminated = new AtomicBoolean(false);

        /** 保存本次调用的不可变终止上下文。 */
        private TerminalListener(
                ModelRequest request,
                AiRequestContext context,
                ModelStreamListener delegate,
                long start,
                int entered) {
            this.request = request;
            this.context = context;
            this.delegate = delegate;
            this.start = start;
            this.entered = entered;
        }

        /** 终止前已开始转发的事件允许完成送达；终止之后开始的转发直接丢弃。 */
        @Override
        public void onEvent(ModelStreamEvent event) {
            synchronized (lifecycleLock) {
                if (terminated.get()) {
                    return;
                }
                delegate.onEvent(event);
            }
        }

        /** 正常完成：终止切换收进锁内，保证与事件转发线性化。 */
        @Override
        public void onCompleted() {
            synchronized (lifecycleLock) {
                if (!terminated.compareAndSet(false, true)) {
                    return;
                }
                completeLocked();
            }
        }

        /** 持锁执行：先退出拦截器，再向下游发布完成。 */
        private void completeLocked() {
            RuntimeException afterFailure =
                    invokeAfter(
                            request,
                            context,
                            true,
                            System.currentTimeMillis() - start,
                            null,
                            entered);
            if (afterFailure == null) {
                delegate.onCompleted();
            } else {
                delegate.onError(new ModelGatewayException("模型后置拦截器执行失败", afterFailure, false));
            }
        }

        /** 异步失败：终止切换收进锁内；保留原始失败，并把后置异常作为 suppressed 附加。 */
        @Override
        public void onError(Throwable error) {
            synchronized (lifecycleLock) {
                if (!terminated.compareAndSet(false, true)) {
                    return;
                }
                errorLocked(error);
            }
        }

        /** 持锁执行：after 失败附加到原始失败后向下游发布。 */
        private void errorLocked(Throwable error) {
            RuntimeException afterFailure =
                    invokeAfter(
                            request,
                            context,
                            false,
                            System.currentTimeMillis() - start,
                            error,
                            entered);
            if (afterFailure != null) {
                error.addSuppressed(afterFailure);
            }
            delegate.onError(error);
        }

        /** 启动阶段异常已由调用方同步接收，只负责完成 after，不重复通知 listener。 */
        private void failBeforeStart(Throwable error) {
            synchronized (lifecycleLock) {
                if (terminated.compareAndSet(false, true)) {
                    failBeforeStartLocked(error);
                }
            }
        }

        /** 持锁执行：只补一次 after，不再向下游重复通知。 */
        private void failBeforeStartLocked(Throwable error) {
            RuntimeException afterFailure =
                    invokeAfter(
                            request,
                            context,
                            false,
                            System.currentTimeMillis() - start,
                            error,
                            entered);
            if (afterFailure != null) {
                error.addSuppressed(afterFailure);
            }
        }

        /** 主动取消只终止生命周期与 after，不再向已断开的下游发送错误；终止切换同样在锁内完成。 */
        private void cancel() {
            synchronized (lifecycleLock) {
                if (terminated.compareAndSet(false, true)) {
                    CancellationException cancellation = new CancellationException("模型调用已取消");
                    invokeAfter(
                            request,
                            context,
                            false,
                            System.currentTimeMillis() - start,
                            cancellation,
                            entered);
                }
            }
        }
    }
}
