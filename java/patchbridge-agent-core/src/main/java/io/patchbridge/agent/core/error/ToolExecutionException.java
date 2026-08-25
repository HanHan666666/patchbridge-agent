package io.patchbridge.agent.core.error;

/**
 * Tool 执行失败（Tool 不存在、参数绑定失败、业务方法抛出异常）。
 * 最终会以 TOOL_FAILED 错误返回浏览器，同时写入审计。
 */
public class ToolExecutionException extends Exception {

    public ToolExecutionException(String message) {
        super(message);
    }

    public ToolExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
