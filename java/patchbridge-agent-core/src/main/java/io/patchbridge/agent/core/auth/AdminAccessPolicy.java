package io.patchbridge.agent.core.auth;

import io.patchbridge.agent.core.user.UserContext;

/**
 * 管理端访问策略 SPI。
 *
 * <p>框架不建立第二套管理员账户，也不猜测宿主的角色命名。宿主只有在显式开启
 * Admin 功能时才需要提供本接口，并把 {@link AdminCapability} 映射到已有权限体系。
 */
public interface AdminAccessPolicy {

    /**
     * 判断当前用户是否具备指定管理能力。
     *
     * @param user 由宿主登录体系解析出的可信用户
     * @param capability 当前请求需要的管理能力
     * @return 允许访问时返回 true
     */
    boolean canAccess(UserContext user, AdminCapability capability);
}
