package io.patchbridge.agent.core.auth;

import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.user.UserContext;

/**
 * 当前用户解析 SPI。
 *
 * <p>框架不绑定任何安全框架：宿主应用用 Spring Security / Shiro / Sa-Token /
 * 自研权限中心实现本接口，把现有登录态翻译为 UserContext。
 * 解析不出登录用户时返回 null（是否放行由 ToolAccessPolicy 决定）。
 */
public interface CurrentUserProvider {

    /** 从当前请求解析登录用户；匿名 / 未登录返回 null。 */
    UserContext currentUser(AiRequestContext request);
}
