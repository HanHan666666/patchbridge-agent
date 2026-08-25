package io.patchbridge.agent.demo.security;

import io.patchbridge.agent.core.auth.ToolAccessPolicy;
import io.patchbridge.agent.core.tool.ToolDefinition;
import io.patchbridge.agent.core.user.UserContext;
import org.springframework.stereotype.Component;

/**
 * Demo 的 Tool 权限策略：把 @AiTool / MCP 导入时的 permissions 标识
 * 对照当前登录用户的权限集合（Spring Security authorities 已被
 * SecurityContextCurrentUserProvider 翻译进 UserContext.permissions）。
 *
 * <p>这演示了框架的关键设计：权限语义完全属于宿主应用——
 * 框架只透传权限标识列表，怎么组合判断由本类决定。
 * 真实企业可在此接入 Shiro / Sa-Token / 权限中心，替换方式就是提供本 Bean。
 *
 * <p>规则：
 * <ul>
 *   <li>未登录一律拒绝（浏览器不是安全边界）；</li>
 *   <li>未声明 permissions 的 Tool 仅要求登录（演示数据查询类）；</li>
 *   <li>声明多个 permissions 时采用 ALL 语义，要求用户同时拥有全部权限。</li>
 * </ul>
 * ALL 是 Demo 明确选择的业务规则，不是框架强加的权限语义；企业可替换本 Bean
 * 实现 ANY、角色表达式或权限中心决策。
 */
@Component
public class DemoToolAccessPolicy implements ToolAccessPolicy {

    /** 发现阶段复用 Demo 的 ALL 权限规则。 */
    @Override
    public boolean canDiscover(UserContext user, ToolDefinition tool) {
        return check(user, tool);
    }

    /** 调用阶段再次执行权限校验，不能信任浏览器发现结果。 */
    @Override
    public boolean canInvoke(UserContext user, ToolDefinition tool) {
        // 发现与调用同规则：列表里看不到的 Tool 调用也过不了，
        // 两处都保留判断是因为 /ai/tools 的过滤不能替代 /ai/tools/call 的校验
        return check(user, tool);
    }

    /**
     * 校验登录状态与 Tool 声明的全部权限。
     *
     * @param user 当前登录用户
     * @param tool 待发现或调用的 Tool
     * @return 用户是否满足 Demo 的访问规则
     */
    private boolean check(UserContext user, ToolDefinition tool) {
        if (user == null || user.getUserId() == null) {
            return false;
        }
        if (tool.getPermissions().isEmpty()) {
            return true;
        }
        return user.getPermissions().containsAll(tool.getPermissions());
    }
}
