package io.patchbridge.agent.core.tool;

import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.error.ToolExecutionException;

import java.util.List;

/**
 * Tool 来源提供方 SPI。
 *
 * <p>统一 Tool Gateway 的聚合单元：本地 @AiTool、现有 REST API、远程 MCP 各有一个实现，
 * 各自负责自己命名空间内的 Tool 发现与执行。Gateway 不关心来源差异。
 *
 * <p>实现注意：list/call 都可能被并发调用，实现必须线程安全；
 * Provider 不做权限判断（那是 ToolAccessPolicy 的职责）。
 */
public interface ToolProvider {

    /** 本 Provider 的命名空间（如 local、mcp.inventory），全小写、不含点。 */
    String namespace();

    /**
     * 列出本命名空间下全部 Tool 定义（未按用户过滤，过滤由 Registry 调用 ToolAccessPolicy 完成）。
     */
    List<ToolDefinition> list();

    /**
     * 执行命名空间内（不含前缀）的 Tool。
     *
     * @param toolName 去掉命名空间前缀后的 Tool 名
     * @param arguments 模型生成的业务参数（已通过 JSON 反序列化为 Map）
     * @param requestContext 含当前用户、traceId 的可信上下文
     */
    ToolCallResult call(String toolName, java.util.Map<String, Object> arguments,
                        AiRequestContext requestContext) throws ToolExecutionException;
}
