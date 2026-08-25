package io.patchbridge.agent.starter.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Collections;
import java.util.Map;

/**
 * Admin 控制台入口重定向。
 *
 * <p>静态资源映射（/ai-admin/** → classpath:/META-INF/patchbridge-agent-admin/）
 * 只能解析具体文件，不会对目录路径自动返回 index.html——Spring 的 welcome page
 * 机制仅适用于静态根路径。这里显式重定向，保证 /ai-admin 与 /ai-admin/ 均可直达。
 * 访问控制由 AdminAuthorizationInterceptor 与宿主安全链共同负责。
 */
@Controller
@ConditionalOnExpression("${patchbridge-agent.enabled:true}"
        + " and ${patchbridge-agent.admin.enabled:false}")
@ConditionalOnBean(AdminAuthorizationInterceptor.class)
public class AdminConsoleController {

    private final String basePath;

    /**
     * 创建管理台入口，并持有与业务 API Controller 相同的统一前缀。
     * 静态页面不能读取 Spring 配置，因此必须通过受保护端点显式下发该值。
     */
    public AdminConsoleController(String basePath) {
        this.basePath = basePath;
    }

    /** 将两个目录形式的入口统一重定向到实际静态文件。 */
    @GetMapping({"/ai-admin", "/ai-admin/"})
    public String index() {
        return "redirect:/ai-admin/index.html";
    }

    /**
     * 返回管理台调用 API 所需的运行时配置。
     * 该端点与其他 /ai-admin/** 资源共用 CONSOLE 权限，不向未授权用户泄露配置。
     */
    @GetMapping("/ai-admin/config")
    @ResponseBody
    public Map<String, String> config() {
        return Collections.singletonMap("basePath", basePath);
    }
}
