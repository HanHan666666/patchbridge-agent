package io.patchbridge.agent.demo.web;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletRequest;

/**
 * Demo 登录页：模拟企业系统已有的表单登录。
 *
 * <p>必须自备登录页的原因：SecurityConfig 为 /ai/** 配置了
 * DelegatingAuthenticationEntryPoint（API 未登录返回 401 JSON 而非 302 跳转），
 * Spring Security 检测到自定义入口点后不再生成默认登录页；
 * 真实企业系统同样自带登录页，框架不做任何假设。
 */
@Controller
public class LoginController {

    @GetMapping("/login")
    @ResponseBody
    public String login(HttpServletRequest request) {
        // CsrfFilter 已把 token 放进 request 属性；表单登录 POST 必须携带
        CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        String token = csrf == null ? "" : csrf.getToken();
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">"
                + "<title>登录 · 企业设备管理系统（Demo）</title>"
                + "<style>body{font-family:'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;"
                + "background:#f3f4f6;display:flex;justify-content:center;padding-top:10vh;margin:0}"
                + ".card{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:32px;"
                + "width:360px;box-shadow:0 8px 30px rgba(0,0,0,.06)}"
                + "h1{font-size:18px;margin:0 0 6px}p{color:#6b7280;font-size:13px;margin:0 0 20px}"
                + "label{display:block;font-size:13px;color:#374151;margin:12px 0 4px}"
                + "input{width:100%;box-sizing:border-box;height:36px;border:1px solid #d1d5db;"
                + "border-radius:8px;padding:0 10px;font-size:14px}"
                + "button{margin-top:20px;width:100%;height:38px;border:none;border-radius:8px;"
                + "background:#2563eb;color:#fff;font-size:14px;cursor:pointer}"
                + ".tip{margin-top:16px;font-size:12px;color:#9ca3af;line-height:1.8}"
                + ".tip .title{margin-bottom:4px}"
                + ".account{display:flex;justify-content:space-between;gap:8px;padding:3px 8px;"
                + "border-radius:6px;cursor:pointer;font-family:Consolas,monospace}"
                + ".account:hover{background:#eff6ff;color:#2563eb}"
                + ".account.selected{background:#dbeafe;color:#1d4ed8}"
                + ".account .role{font-family:'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif}</style></head>"
                + "<body><div class=\"card\"><h1>企业设备管理系统</h1><p>AI 助手接入 Demo，请先登录</p>"
                + "<form method=\"post\" action=\"/login\">"
                + "<input type=\"hidden\" name=\"_csrf\" value=\"" + token + "\"/>"
                + "<label>用户名</label><input name=\"username\" autofocus>"
                + "<label>密码</label><input name=\"password\" type=\"password\">"
                + "<button type=\"submit\">登 录</button></form>"
                // 演示账号一键填充：点击只填表单不提交，登录动作仍由用户完成
                + "<div class=\"tip\"><div class=\"title\">演示账号（点击自动填充）：</div>"
                + "<div class=\"account\" data-u=\"admin\" data-p=\"admin123\">"
                + "<span>admin / admin123</span><span class=\"role\">管理员</span></div>"
                + "<div class=\"account\" data-u=\"operator\" data-p=\"op123456\">"
                + "<span>operator / op123456</span><span class=\"role\">运维</span></div>"
                + "<div class=\"account\" data-u=\"user\" data-p=\"user123456\">"
                + "<span>user / user123456</span><span class=\"role\">只读</span></div>"
                + "<div class=\"account\" data-u=\"auditor\" data-p=\"auditor123\">"
                + "<span>auditor / auditor123</span><span class=\"role\">审计员</span></div>"
                + "</div>"
                + "<script>"
                + "document.querySelector('.tip').addEventListener('click',function(e){"
                + "var item=e.target.closest('.account');if(!item)return;"
                + "document.querySelectorAll('.account.selected').forEach(function(n){n.classList.remove('selected')});"
                + "item.classList.add('selected');"
                + "document.querySelector('input[name=username]').value=item.dataset.u;"
                + "document.querySelector('input[name=password]').value=item.dataset.p;});"
                + "</script>"
                + "</div></body></html>";
    }
}
