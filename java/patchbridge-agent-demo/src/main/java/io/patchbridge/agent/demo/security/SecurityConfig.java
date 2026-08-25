package io.patchbridge.agent.demo.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;

/**
 * Demo 安全配置：企业已有 RBAC 的模拟（User N:M Role N:M Permission）。
 *
 * <p>体现框架的安全边界假设：
 * <ul>
 *   <li>/ai/** 复用当前登录态（Cookie Session），浏览器 Agent 与普通前端 API 同一安全模型；</li>
 *   <li>/ai/admin/** 限定 ADMIN / AUDITOR（框架不建第二套管理员账户）；</li>
 *   <li>首页及其页面壳资源（/css/**、/ai/assets/**）公开，业务 API 一律认证；</li>
 *   <li>API 未登录返回 401 JSON（浏览器据 AUTH_REQUIRED 引导登录），页面未登录跳转表单；</li>
 * </ul>
 * CSRF 对 /ai/** 关闭：该路径是 JSON API + SameSite Cookie 场景。
 */
@Configuration
public class SecurityConfig extends WebSecurityConfigurerAdapter {

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http
                .authorizeRequests()
                .antMatchers("/", "/index.html", "/login", "/error", "/favicon.ico").permitAll()
                // 首页的布局与主题样式拆分在 /css/**（与首页同属公开页面壳，须随首页一起放行，
                // 否则未登录访问首页时样式被重定向到登录页，页面裸奔无样式）
                .antMatchers("/css/**").permitAll()
                // Chrome/Edge 的 DevTools 自动探测请求：放行为静态 404 即可。
                // 若走认证拦截，它会被存成 saved request，登录成功后跳回这个
                // 不存在的路径，用户看到 Whitelabel 404 而不是首页
                .antMatchers("/.well-known/**").permitAll()
                .antMatchers("/ai/assets/**").permitAll()
                .antMatchers("/ai/admin/**").hasAnyRole("ADMIN", "AUDITOR")
                .antMatchers("/ai/**").authenticated()
                .antMatchers("/ai-admin/**").hasAnyRole("ADMIN", "AUDITOR")
                .anyRequest().authenticated()
                .and()
                .formLogin()
                .and()
                .logout()
                .and()
                .csrf()
                // /logout 无 CSRF 防护必要（伪造登出无收益，业界常规做法）；
                // 宿主页面的退出链接是纯 fetch POST，拿不到服务端渲染的 token
                .ignoringAntMatchers("/ai/**", "/logout")
                .and()
                .requestCache()
                // /ai/** 的 401 不产生 saved request（见 ApiAwareRequestCache 注释）
                .requestCache(new ApiAwareRequestCache())
                .and()
                .exceptionHandling()
                .authenticationEntryPoint(apiAwareEntryPoint());
    }

    /**
     * API 感知的 RequestCache：/ai/** 是浏览器 fetch 的 AJAX 调用，
     * ExceptionTranslationFilter 返回 401 前总会 saveRequest——若照单全收，
     * 登录成功后会被跳回该 API 地址，用户看到一屏裸 JSON 而不是页面。
     * 这里把 API 请求排除在缓存外，登录后回落默认首页（widget 自行重新初始化）；
     * 页面级路径（如 /ai-admin）保留“登录后跳回原页”的标准行为。
     */
    static final class ApiAwareRequestCache implements RequestCache {

        private final HttpSessionRequestCache delegate = new HttpSessionRequestCache();

        @Override
        public void saveRequest(HttpServletRequest request, HttpServletResponse response) {
            if (!request.getRequestURI().startsWith("/ai/")) {
                delegate.saveRequest(request, response);
            }
        }

        @Override
        public SavedRequest getRequest(HttpServletRequest request, HttpServletResponse response) {
            return delegate.getRequest(request, response);
        }

        @Override
        public HttpServletRequest getMatchingRequest(HttpServletRequest request,
                                                     HttpServletResponse response) {
            return delegate.getMatchingRequest(request, response);
        }

        @Override
        public void removeRequest(HttpServletRequest request, HttpServletResponse response) {
            delegate.removeRequest(request, response);
        }
    }

    /**
     * API 路径（/ai/**）未登录返回 401 JSON（浏览器 Agent 依赖 AUTH_REQUIRED 错误码）；
     * 其余路径沿用登录页重定向。
     */
    @Bean
    public DelegatingAuthenticationEntryPoint apiAwareEntryPoint() {
        LinkedHashMap<org.springframework.security.web.util.matcher.RequestMatcher,
                org.springframework.security.web.AuthenticationEntryPoint> entryPoints =
                new LinkedHashMap<org.springframework.security.web.util.matcher.RequestMatcher,
                        org.springframework.security.web.AuthenticationEntryPoint>();
        entryPoints.put(new AntPathRequestMatcher("/ai/**"),
                (request, response, authException) -> {
                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                    response.setContentType("application/json;charset=UTF-8");
                    // 必须以 UTF-8 字节流写出：PrintWriter 会按容器默认的
                    // ISO-8859-1 编码，中文错误消息会整体变成问号
                    response.getOutputStream().write(
                            "{\"error\":{\"code\":\"AUTH_REQUIRED\",\"message\":\"请先登录\"}}"
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                });
        DelegatingAuthenticationEntryPoint delegating =
                new DelegatingAuthenticationEntryPoint(entryPoints);
        delegating.setDefaultEntryPoint(
                new org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint("/login"));
        return delegating;
    }

    /**
     * 演示账号：角色与权限差异直接决定各自可见与可调用的 Tool 集合。
     * 全部用 authorities() 声明（ROLE_ 前缀 + 业务权限）——User.Builder 的
     * roles() 与 authorities() 同时调用时后者会整体覆盖前者，混用会丢角色。
     */
    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(
                User.withUsername("admin")
                        .password(encoder.encode("admin123"))
                        .authorities("ROLE_ADMIN", "ai:chat:use", "ai:tool:device:read",
                                "ai:tool:device:restart", "ai:mcp:inventory:read", "ai:mcp:ops:restart",
                                "ai:trace:all", "ai:admin:access")
                        .build(),
                User.withUsername("operator")
                        .password(encoder.encode("op123456"))
                        .authorities("ROLE_OPERATOR", "ai:chat:use", "ai:tool:device:read",
                                "ai:tool:device:restart", "ai:mcp:inventory:read")
                        .build(),
                User.withUsername("user")
                        .password(encoder.encode("user123456"))
                        .authorities("ROLE_USER", "ai:chat:use", "ai:tool:device:read")
                        .build(),
                User.withUsername("auditor")
                        .password(encoder.encode("auditor123"))
                        .authorities("ROLE_AUDITOR", "ai:chat:use", "ai:trace:all")
                        .build());
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
