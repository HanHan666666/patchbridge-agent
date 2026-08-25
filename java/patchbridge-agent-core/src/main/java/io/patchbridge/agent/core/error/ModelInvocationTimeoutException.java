package io.patchbridge.agent.core.error;

/**
 * 宿主等待 Java 单次模型调用超过显式时限。
 *
 * <p>抛出该异常前 Invocation 已请求取消真实上游，因此调用方不会在超时后又收到一个迟到的成功结果。
 */
public final class ModelInvocationTimeoutException extends RuntimeException {

    /** 固定序列化版本，避免跨 JVM 日志或任务边界反序列化时产生默认值漂移。 */
    private static final long serialVersionUID = 1L;

    /** 创建保留标准超时原因链的等待超时异常。 */
    public ModelInvocationTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
