package io.patchbridge.agent.core.tool;

import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.error.ToolAccessDeniedException;
import io.patchbridge.agent.core.error.ToolExecutionException;
import io.patchbridge.agent.core.error.ToolVersionMismatchException;
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
 *   <li>版本（call）：调用携带的定义/路由版本引用必须与当前定义一致，
 *       防止模型基于旧描述把调用执行到已变化的新目标上；</li>
 *   <li>命名：通过 ToolNamingStrategy 保证跨命名空间不冲突。</li>
 * </ul>
 */
public interface ToolRegistry {

    /** 当前用户可见（可发现）的 Tool 列表。 */
    List<ToolDefinition> list(UserContext user);

    /** 按命名空间全名查找 Tool 定义；不存在返回 null。 */
    ToolDefinition find(String fullName);

    /**
     * 执行 Tool：先校验版本引用，再 canInvoke 校验，最后路由到对应 Provider。
     *
     * @param definitionVersion 发现时取得的定义/路由版本引用；
     *                          静态 Tool 恒为 {@code null}，必须与当前定义精确一致
     * @throws ToolAccessDeniedException 权限拒绝
     * @throws ToolVersionMismatchException 版本引用与当前定义不一致
     * @throws ToolExecutionException Tool 不存在或执行失败
     */
    ToolCallResult call(String fullName, String definitionVersion,
                        Map<String, Object> arguments, AiRequestContext requestContext)
            throws ToolExecutionException, ToolAccessDeniedException, ToolVersionMismatchException;
}
