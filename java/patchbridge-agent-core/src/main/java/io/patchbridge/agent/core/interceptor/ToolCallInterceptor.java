package io.patchbridge.agent.core.interceptor;

import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.tool.ToolCallResult;
import io.patchbridge.agent.core.tool.ToolDefinition;

import java.util.Map;

/**
 * Tool 调用拦截 SPI。
 *
 * <p>典型用途：企业级审批日志、限流、调用前参数校验、readOnly Tool 的自定义重试策略
 * （框架默认 retry=0，写操作永不自动重试）。
 *
 * <p>before 抛出异常会中断本次调用并作为 TOOL_FAILED 返回给浏览器。
 */
public interface ToolCallInterceptor {

    void before(ToolDefinition tool, Map<String, Object> arguments, AiRequestContext context)
            throws Exception;

    void after(ToolDefinition tool, Map<String, Object> arguments, AiRequestContext context,
               ToolCallResult result, Throwable failure);
}
