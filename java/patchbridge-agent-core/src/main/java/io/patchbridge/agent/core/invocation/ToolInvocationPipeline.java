package io.patchbridge.agent.core.invocation;

import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.error.ToolAccessDeniedException;
import io.patchbridge.agent.core.error.ToolExecutionException;
import io.patchbridge.agent.core.error.ToolVersionMismatchException;
import io.patchbridge.agent.core.interceptor.ToolCallInterceptor;
import io.patchbridge.agent.core.tool.ToolCallResult;
import io.patchbridge.agent.core.tool.ToolDefinition;
import io.patchbridge.agent.core.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tool 调用应用服务：把 Registry 调用与企业拦截器收敛为唯一执行管线。
 *
 * <p>before 按注册顺序执行，after 按相反顺序退出，行为与嵌套装饰器一致。
 * 只有成功进入 before 的拦截器才会收到 after；权限、版本与业务异常保持原始类型。
 */
public final class ToolInvocationPipeline {

    private final ToolRegistry registry;
    private final List<ToolCallInterceptor> interceptors;

    /** 创建不可变的 Tool 调用管线快照。 */
    public ToolInvocationPipeline(ToolRegistry registry,
                                  List<ToolCallInterceptor> interceptors) {
        this.registry = registry;
        this.interceptors = new ArrayList<ToolCallInterceptor>(interceptors);
    }

    /**
     * 执行一次 Tool 调用，并保证已进入的 after 在成功和失败路径都会执行。
     * 后置拦截器失败不会覆盖原始业务异常；无原始异常时则作为 TOOL_FAILED 暴露。
     */
    public ToolCallResult invoke(String fullName, String definitionVersion,
                                 Map<String, Object> arguments, AiRequestContext context)
            throws ToolExecutionException, ToolAccessDeniedException,
            ToolVersionMismatchException {
        ToolDefinition tool = registry.find(fullName);
        if (tool == null) {
            return registry.call(fullName, definitionVersion, arguments, context);
        }

        int entered = 0;
        ToolCallResult result = null;
        Exception failure = null;
        try {
            for (ToolCallInterceptor interceptor : interceptors) {
                interceptor.before(tool, arguments, context);
                entered += 1;
            }
            result = registry.call(fullName, definitionVersion, arguments, context);
        } catch (Exception e) {
            failure = e;
        }

        ToolExecutionException afterFailure = invokeAfter(
                tool, arguments, context, result, failure, entered);
        if (failure != null) {
            if (afterFailure != null) {
                failure.addSuppressed(afterFailure);
            }
            rethrow(failure);
        }
        if (afterFailure != null) {
            throw afterFailure;
        }
        return result;
    }

    /** 逆序执行已进入拦截器的 after，并合并多个后置失败。 */
    private ToolExecutionException invokeAfter(ToolDefinition tool,
                                                Map<String, Object> arguments,
                                                AiRequestContext context,
                                                ToolCallResult result,
                                                Throwable failure,
                                                int entered) {
        ToolExecutionException combined = null;
        for (int index = entered - 1; index >= 0; index -= 1) {
            try {
                interceptors.get(index).after(tool, arguments, context, result, failure);
            } catch (RuntimeException e) {
                ToolExecutionException current = new ToolExecutionException(
                        "Tool 后置拦截器执行失败", e);
                if (combined == null) {
                    combined = current;
                } else {
                    combined.addSuppressed(current);
                }
            }
        }
        return combined;
    }

    /** 保留安全拒绝、版本不一致与 Tool 失败语义，其余拦截器异常统一转换为 ToolExecutionException。 */
    private static void rethrow(Exception failure)
            throws ToolExecutionException, ToolAccessDeniedException,
            ToolVersionMismatchException {
        if (failure instanceof ToolAccessDeniedException) {
            throw (ToolAccessDeniedException) failure;
        }
        if (failure instanceof ToolVersionMismatchException) {
            throw (ToolVersionMismatchException) failure;
        }
        if (failure instanceof ToolExecutionException) {
            throw (ToolExecutionException) failure;
        }
        throw new ToolExecutionException("Tool 前置拦截器执行失败", failure);
    }
}
