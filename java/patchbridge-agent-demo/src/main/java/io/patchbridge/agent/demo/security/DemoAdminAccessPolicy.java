package io.patchbridge.agent.demo.security;

import io.patchbridge.agent.core.auth.AdminAccessPolicy;
import io.patchbridge.agent.core.auth.AdminCapability;
import io.patchbridge.agent.core.user.UserContext;
import org.springframework.stereotype.Component;

/**
 * Demo 的管理端能力映射：演示框架复用宿主权限码，而不是建立独立管理员体系。
 * 审计员只能查看 Trace；具备 ai:admin:access 的管理员才能诊断或刷新 MCP。
 */
@Component
public class DemoAdminAccessPolicy implements AdminAccessPolicy {

    /** 按 Demo 已有权限码授权管理能力。 */
    @Override
    public boolean canAccess(UserContext user, AdminCapability capability) {
        if (user == null) {
            return false;
        }
        switch (capability) {
            case CONSOLE:
                return user.getPermissions().contains("ai:trace:all")
                        || user.getPermissions().contains("ai:admin:access");
            case AUDIT_READ:
                return user.getPermissions().contains("ai:trace:all");
            case MCP_READ:
            case MCP_MANAGE:
                return user.getPermissions().contains("ai:admin:access");
            default:
                return false;
        }
    }
}
