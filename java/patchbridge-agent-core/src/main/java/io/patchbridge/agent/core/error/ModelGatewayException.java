package io.patchbridge.agent.core.error;

/** 模型网关异常：上游不可达、认证失败、协议不合法（MODEL_FAILED）。 */
public class ModelGatewayException extends RuntimeException {

    /** 是否适合由调用方在不修改请求的情况下重试。 */
    private final boolean retryable;

    /** 创建默认可重试的上游或网络失败。 */
    public ModelGatewayException(String message) {
        this(message, null, true);
    }

    /** 创建默认可重试且保留原因链的上游或网络失败。 */
    public ModelGatewayException(String message, Throwable cause) {
        this(message, cause, true);
    }

    /** 创建带明确重试语义的模型异常。 */
    public ModelGatewayException(String message, boolean retryable) {
        this(message, null, retryable);
    }

    /** 创建带明确重试语义和原因链的模型异常。 */
    public ModelGatewayException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    /** 返回调用方是否可在不修改请求的情况下重试。 */
    public boolean isRetryable() {
        return retryable;
    }
}
