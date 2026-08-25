package io.patchbridge.agent.core.interceptor;

import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.model.ModelRequest;

/**
 * 模型调用拦截 SPI：转发前 / 结束后（含失败）的钩子。
 *
 * <p>典型用途：按用户 / 租户限流、Token 成本记账、异常模型的告警。
 */
public interface ModelCallInterceptor {

    /** 模型调用前钩子；上下文中的用户与 traceId 均来自服务端可信链路。 */
    void before(ModelRequest request, AiRequestContext context) throws Exception;

    /** 模型流终止后的观察钩子，成功与失败都会执行。 */
    void after(ModelRequest request, AiRequestContext context, boolean success,
               long durationMs, Throwable failure);
}
