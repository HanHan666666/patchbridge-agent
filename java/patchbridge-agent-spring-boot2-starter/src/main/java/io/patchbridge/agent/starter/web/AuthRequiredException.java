package io.patchbridge.agent.starter.web;

/**
 * /ai/** 端点未解析到登录用户（宿主安全体系未放行或未接入 CurrentUserProvider）。
 * 映射为 401 AUTH_REQUIRED，浏览器据此引导用户登录。
 */
public class AuthRequiredException extends RuntimeException {

    public AuthRequiredException(String message) {
        super(message);
    }
}
