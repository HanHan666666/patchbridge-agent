package io.patchbridge.agent.starter.web;

import io.patchbridge.agent.core.auth.AdminAccessPolicy;
import io.patchbridge.agent.core.auth.AdminCapability;
import io.patchbridge.agent.core.auth.CurrentUserProvider;
import io.patchbridge.agent.core.error.AgentErrorCode;
import io.patchbridge.agent.core.user.UserContext;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Admin 统一授权入口：同时覆盖 API、控制台入口和静态资源处理器。
 *
 * <p>Spring Security 等宿主过滤链仍可做第一层 URL 保护；本拦截器是框架端的
 * 强制能力校验，防止宿主遗漏某条路径后直接暴露审计数据或 MCP 管理操作。
 */
public class AdminAuthorizationInterceptor implements HandlerInterceptor {

    /** 与公共 Controller 共用的可信身份解析入口。 */
    private final CurrentUserResolver currentUser;
    /** 宿主提供的管理能力授权策略。 */
    private final AdminAccessPolicy accessPolicy;
    /** 用于把管理端路由映射为最小能力的 API 前缀。 */
    private final String basePath;

    /** 创建管理端授权拦截器。 */
    public AdminAuthorizationInterceptor(CurrentUserProvider userProvider,
                                         AdminAccessPolicy accessPolicy,
                                         String basePath) {
        this.currentUser = new CurrentUserResolver(userProvider);
        this.accessPolicy = accessPolicy;
        this.basePath = basePath;
    }

    /**
     * 在控制器或静态资源读取前执行能力校验。
     * OPTIONS 由宿主的跨域与安全链处理，不在框架层推断其最终业务操作。
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        UserContext user;
        try {
            user = currentUser.requiredUser();
        } catch (AuthRequiredException e) {
            return reject(response, HttpServletResponse.SC_UNAUTHORIZED,
                    AgentErrorCode.AUTH_REQUIRED, "访问管理端需要先登录");
        }
        AdminCapability capability = resolveCapability(request);
        if (capability == null) {
            return reject(response, HttpServletResponse.SC_FORBIDDEN,
                    AgentErrorCode.ADMIN_FORBIDDEN, "管理端路由未声明所需能力");
        }
        if (!accessPolicy.canAccess(user, capability)) {
            return reject(response, HttpServletResponse.SC_FORBIDDEN,
                    AgentErrorCode.ADMIN_FORBIDDEN,
                    "当前用户无权执行管理端操作: " + capability.name());
        }
        return true;
    }

    /**
     * 直接结束被拒绝的请求。静态资源处理器不属于 Starter Controller，不能依赖
     * ControllerAdvice；在统一拦截器写响应才能确保 API 与控制台资源都被覆盖。
     */
    private boolean reject(HttpServletResponse response, int status,
                           String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        String body = "{\"error\":{\"code\":\"" + code
                + "\",\"message\":\"" + message + "\"}}";
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        return false;
    }

    /**
     * 根据稳定的管理端路由与 HTTP 方法解析最小所需能力。
     * 未知 API 返回 null 并由调用方拒绝；新增端点必须先显式加入能力映射。
     */
    private AdminCapability resolveCapability(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String mcpPrefix = basePath + "/admin/mcp/";
        if (path.startsWith(mcpPrefix)) {
            return "GET".equalsIgnoreCase(request.getMethod())
                    || "HEAD".equalsIgnoreCase(request.getMethod())
                    ? AdminCapability.MCP_READ : AdminCapability.MCP_MANAGE;
        }
        if (path.equals(basePath + "/admin/stats")
                || path.equals(basePath + "/admin/traces")
                || path.startsWith(basePath + "/admin/traces/")) {
            return AdminCapability.AUDIT_READ;
        }
        if (path.equals("/ai-admin") || path.startsWith("/ai-admin/")) {
            return AdminCapability.CONSOLE;
        }
        return null;
    }
}
