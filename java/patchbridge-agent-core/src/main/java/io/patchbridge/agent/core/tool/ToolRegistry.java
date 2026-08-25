package io.patchbridge.agent.core.tool;

import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.error.ToolAccessDeniedException;
import io.patchbridge.agent.core.error.ToolExecutionException;
import io.patchbridge.agent.core.user.UserContext;

import java.util.List;
import java.util.Map;

/**
 * 统一 Tool Registry：所有来源的 Tool 在此聚合为同一命名平面。
 *
 * <p>职责边界：
 * <ul>
 *   <li>发现（list）：按当前用户执行 ToolAccessPolicy.canDiscover 过滤，
 *       避免无权限 Tool 进入模型上下文；</li>
 *   <li>调用（call）：执行前再次执行 ToolAccessPolicy.canInvoke —— 浏览器不是安全边界，
 *       每一次调用都必须重新授权（即使 /ai/tools 已经过滤过）；</li>
 *   <li>命名：通过 ToolNamingStrategy 保证跨命名空间不冲突。</li>
 * </ul>
 */
public interface ToolRegistry {

    /** 当前用户可见（可发现）的 Tool 列表。 */
    List<ToolDefinition> list(UserContext user);

    /** 按命名空间全名查找 Tool 定义；不存在返回 null。 */
    ToolDefinition find(String fullName);

    /**
     * 执行 Tool：先 canInvoke 校验，再路由到对应 Provider。
     *
     * @throws ToolAccessDeniedException 权限拒绝
     * @throws ToolExecutionException Tool 不存在或执行失败
     */
    ToolCallResult call(String fullName, Map<String, Object> arguments,
                        AiRequestContext requestContext)
            throws ToolExecutionException, ToolAccessDeniedException;
}
