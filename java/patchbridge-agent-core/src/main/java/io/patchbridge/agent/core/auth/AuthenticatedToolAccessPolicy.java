package io.patchbridge.agent.core.auth;

import io.patchbridge.agent.core.tool.ToolDefinition;
import io.patchbridge.agent.core.user.UserContext;

/**
 * 默认访问策略：仅要求“已登录”，不看角色与权限。
 *
 * <p>作为 Starter 的默认实现存在；企业应替换为自己的策略
 * （例如把 ToolDefinition.permissions 对照 RBAC 权限集合并选择 ALL 或 ANY 语义），见设计原则
 * “Core defines contracts. Starter provides defaults. Applications keep control.”。
 */
public class AuthenticatedToolAccessPolicy implements ToolAccessPolicy {

    @Override
    public boolean canDiscover(UserContext user, ToolDefinition tool) {
        return isAuthenticated(user);
    }

    @Override
    public boolean canInvoke(UserContext user, ToolDefinition tool) {
        return isAuthenticated(user);
    }

    private boolean isAuthenticated(UserContext user) {
        return user != null && user.getUserId() != null && !user.getUserId().isEmpty();
    }
}
