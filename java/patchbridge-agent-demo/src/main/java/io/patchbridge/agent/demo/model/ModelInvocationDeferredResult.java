package io.patchbridge.agent.demo.model;

import io.patchbridge.agent.core.invocation.ModelInvocation;
import io.patchbridge.agent.core.model.ModelResponse;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Duration;

/**
 * 把单次模型调用句柄适配为 Spring MVC 异步响应。
 *
 * <p>{@link DeferredResult} 只负责 Servlet 异步派发，并不知道如何终止真实模型连接。
 * 本适配器将超时、容器错误和下游完成都绑定到 {@link ModelInvocation#cancel()}，
 * 避免浏览器断开后上游仍继续产生 token 和费用。正常完成时 Invocation 已终止，
 * 最后一次幂等取消只用于覆盖容器的所有终止路径。
 */
final class ModelInvocationDeferredResult extends DeferredResult<ModelResponse> {

    /** 需要与 Servlet 异步生命周期共同终止的模型调用。 */
    private final ModelInvocation invocation;

    /**
     * 创建并立即绑定上游结果与下游终止回调。
     *
     * @param invocation 已启动的单次模型调用
     * @param timeout Servlet 异步响应的显式超时
     */
    ModelInvocationDeferredResult(ModelInvocation invocation, Duration timeout) {
        super(timeoutMillis(timeout));
        if (invocation == null) {
            throw new IllegalArgumentException("invocation 不可为空");
        }
        this.invocation = invocation;
        onTimeout(this::handleTimeout);
        onError(this::handleAsyncError);
        onCompletion(this::handleCompletion);
        invocation.result().whenComplete(this::completeFromUpstream);
    }

    /**
     * 将容器超时固定为 Spring MVC 标准超时异常，然后取消真实上游。
     *
     * <p>先设置超时结果可防止取消产生的 {@code CancellationException} 覆盖 HTTP 超时语义。
     */
    void handleTimeout() {
        setErrorResult(new AsyncRequestTimeoutException());
        invocation.cancel();
    }

    /**
     * 容器报告异步 I/O 错误时保留原错误，并取消不再有消费者的上游。
     *
     * @param error Servlet 容器报告的原始异步错误
     */
    void handleAsyncError(Throwable error) {
        setErrorResult(error);
        invocation.cancel();
    }

    /**
     * 响应完成或客户端提前结束时取消句柄，将下游终态和上游资源寿命绑定。
     */
    void handleCompletion() {
        invocation.cancel();
    }

    /**
     * 把上游的唯一终态翻译成 Spring MVC 的结果或异常派发。
     *
     * @param response 成功聚合的厂商中立响应
     * @param failure 网络、协议、取消或聚合失败
     */
    private void completeFromUpstream(ModelResponse response, Throwable failure) {
        if (failure == null) {
            setResult(response);
            return;
        }
        setErrorResult(failure);
    }

    /**
     * 把 Duration 转换为 Spring MVC 的毫秒超时，并拒绝无限或立即超时的含糊配置。
     *
     * @param timeout 显式正时长
     * @return Spring MVC 接受的毫秒数
     */
    private static long timeoutMillis(Duration timeout) {
        if (timeout == null) {
            throw new IllegalArgumentException("timeout 不可为空");
        }
        long timeoutMillis = timeout.toMillis();
        if (timeoutMillis <= 0L) {
            throw new IllegalArgumentException("timeout 必须至少为一毫秒");
        }
        return timeoutMillis;
    }
}
