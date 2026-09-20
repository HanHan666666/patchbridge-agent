package io.patchbridge.agent.core.compaction;

import io.patchbridge.agent.core.error.AgentErrorCode;
import io.patchbridge.agent.core.error.ModelGatewayException;

/**
 * 上下文摘要请求超过“模型窗口 − 输出预留”预算。
 *
 * <p>继承 {@link ModelGatewayException} 使既有模型网关失败通道（同步 catch、异步
 * 传播与统一异常映射）继续覆盖该失败，同时以独立类型表达“预算超限”的稳定语义：
 * 重试同样的请求必然再次失败，必须调整输入后重新发起。该异常以 413
 * {@link AgentErrorCode#CONTEXT_WINDOW_EXCEEDED} 返回浏览器，与上游不可达
 * （502 MODEL_FAILED）保持可区分。
 */
public class ContextWindowExceededException extends ModelGatewayException {

    /** 创建不可重试的窗口预算超限异常。 */
    public ContextWindowExceededException(String message) {
        super(message, false);
    }
}
