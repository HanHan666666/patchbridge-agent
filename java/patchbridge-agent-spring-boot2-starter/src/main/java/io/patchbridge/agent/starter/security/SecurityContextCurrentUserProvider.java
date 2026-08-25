package io.patchbridge.agent.starter.security;

import io.patchbridge.agent.core.auth.CurrentUserProvider;
import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.user.UserContext;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * CurrentUserProvider 的 Spring Security 默认适配器：
 * 把现有登录态翻译为框架的 UserContext。
 *
 * <p>约定：ROLE_ 前缀的 authority 进入 roles，其余进入 permissions
 * （对应“权限字符串挂 authority、角色挂 role”的常见做法）；
 * 未登录 / 匿名用户返回 null，是否放行由 ToolAccessPolicy 决定。
 * 使用 Shiro / Sa-Token / 自研权限体系的应用应提供自己的 CurrentUserProvider 覆盖本实现。
 */
public class SecurityContextCurrentUserProvider implements CurrentUserProvider {

    @Override
    public UserContext currentUser(AiRequestContext request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        UserContext.Builder builder = UserContext.builder()
                .userId(authentication.getName())
                .username(displayName(authentication));
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String name = authority.getAuthority();
            if (name != null && name.startsWith("ROLE_")) {
                builder.role(name);
            } else if (name != null) {
                builder.permission(name);
            }
        }
        return builder.build();
    }

    private static String displayName(Authentication authentication) {
        if (authentication.getPrincipal() instanceof UserDetails) {
            return ((UserDetails) authentication.getPrincipal()).getUsername();
        }
        return authentication.getName();
    }
}
