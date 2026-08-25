package io.patchbridge.agent.mcp;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个远程 MCP Server 的运行配置。
 *
 * <p>该类是协议客户端与配置存储之间的中立模型，不依赖 Spring。
 * 浏览器用户的 Cookie / Authorization 永远不会透传给远程
 * MCP Server；运行时凭据只能由明确选中的 properties 或 JDBC 配置源提供。
 *
 * <p>嵌套的 auth / tools 捕获 Jackson 未知字段并原样保留到 {@link #copy()}，
 * 保证“先拷贝、后校验”的路径不会丢失协议违规证据。
 */
public class McpServerConfig {

    /** 远程 MCP Streamable HTTP 绝对地址。 */
    private String url;

    /** v0.1 仅支持 streamable-http（MCP 2026-07-28 主路径）。 */
    private String transport = "streamable-http";

    /** 是否把该 Server 的 Tool 纳入运行时目录。 */
    private boolean enabled = true;

    /** 单次远程请求超时，受统一 Validator 范围约束。 */
    private long timeoutMs = 30000;

    /**
     * 缓存 tools/list 的最长时间（毫秒）。服务端 ttlMs 更短时尊重远端提示，
     * 更长时仍由宿主上限截断，避免错误或恶意 Server 让 Tool 目录长期不刷新。
     */
    private long cacheTtlMs = 300000;

    /** 服务端出站凭据；不会进入 Browser 响应。 */
    private Auth auth = new Auth();

    /** Tool 导入范围与宿主权限映射。 */
    private ToolsFilter tools = new ToolsFilter();

    /** 暴露给配置 Binder 与运行时客户端的远程地址。 */
    public String getUrl() { return url; }

    /** 由配置入口写入远程地址，安全校验统一在 Validator 完成。 */
    public void setUrl(String url) { this.url = url; }

    /** 返回已声明的 MCP 传输类型。 */
    public String getTransport() { return transport; }

    /** 写入传输类型；v0.1 支持范围由 Validator 统一判定。 */
    public void setTransport(String transport) { this.transport = transport; }

    /** 表示该配置是否参与当前运行时 Tool 路由。 */
    public boolean isEnabled() { return enabled; }

    /** 更新运行时启用标记，不隐式改变其他配置。 */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /** 返回远程请求超时毫秒数。 */
    public long getTimeoutMs() { return timeoutMs; }

    /** 写入远程请求超时；范围在所有入口共享的 Validator 校验。 */
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }

    /** 返回 Tool 目录缓存上限。 */
    public long getCacheTtlMs() { return cacheTtlMs; }

    /** 写入 Tool 目录缓存上限；范围在统一 Validator 校验。 */
    public void setCacheTtlMs(long cacheTtlMs) { this.cacheTtlMs = cacheTtlMs; }

    /** 返回只在服务端内存中使用的出站认证配置。 */
    public Auth getAuth() { return auth; }

    /** 写入认证对象；显式 null 会保留并由 Validator 拒绝。 */
    public void setAuth(Auth auth) { this.auth = auth; }

    /** 返回 Tool 导入规则。 */
    public ToolsFilter getTools() { return tools; }

    /** 写入 Tool 导入规则；显式 null 会保留并由 Validator 拒绝。 */
    public void setTools(ToolsFilter tools) { this.tools = tools; }

    /**
     * 生成与可变配置源脱离的深拷贝。
     * Registry 只持有该副本，避免 Binder、Admin DTO 或 JDBC 映射后续修改
     * 正在被并发 Tool Call 读取的配置。
     *
     * <p>显式 null 的 auth/tools 必须原样保留而不是替换为默认对象：
     * 拷贝结果随后会经过 {@link McpServerConfigValidator} 统一拒绝，
     * 在这里转默认对象会掩盖调用方的协议违规。嵌套对象的未知字段
     * 同样逐字段复制，保证 copy-then-validate 不丢失证据。
     */
    public McpServerConfig copy() {
        McpServerConfig copy = new McpServerConfig();
        copy.setUrl(url);
        copy.setTransport(transport);
        copy.setEnabled(enabled);
        copy.setTimeoutMs(timeoutMs);
        copy.setCacheTtlMs(cacheTtlMs);

        if (auth == null) {
            copy.setAuth(null);
        } else {
            Auth authCopy = new Auth();
            authCopy.setType(auth.getType());
            authCopy.setUsername(auth.getUsername());
            authCopy.setPassword(auth.getPassword());
            authCopy.setToken(auth.getToken());
            authCopy.setHeaderName(auth.getHeaderName());
            authCopy.setHeaders(auth.getHeaders() == null
                    ? new LinkedHashMap<String, String>()
                    : new LinkedHashMap<String, String>(auth.getHeaders()));
            authCopy.getUnknownFields().putAll(auth.getUnknownFields());
            copy.setAuth(authCopy);
        }

        if (tools == null) {
            copy.setTools(null);
        } else {
            ToolsFilter toolsCopy = new ToolsFilter();
            toolsCopy.setInclude(tools.getInclude() == null ? null
                    : new java.util.ArrayList<String>(tools.getInclude()));
            toolsCopy.setExclude(tools.getExclude() == null ? null
                    : new java.util.ArrayList<String>(tools.getExclude()));
            toolsCopy.setPermissions(tools.getPermissions() == null
                    ? new LinkedHashMap<String, String>()
                    : new LinkedHashMap<String, String>(tools.getPermissions()));
            toolsCopy.getUnknownFields().putAll(tools.getUnknownFields());
            copy.setTools(toolsCopy);
        }
        return copy;
    }

    /**
     * 远程 MCP 的服务端凭据类型。
     *
     * <p>未知字段由 Jackson 收集，供校验入口统一拒绝。
     */
    public static class Auth {
        /** 不发送任何认证信息。 */
        public static final String NONE = "none";

        /** 使用 HTTP Basic 认证。 */
        public static final String BASIC = "basic";

        /** 使用 Authorization Bearer Token。 */
        public static final String BEARER = "bearer";

        /** 使用管理员显式声明的单个 API Key Header。 */
        public static final String API_KEY_HEADER = "api-key-header";

        /** 使用管理员显式声明的静态 Header 集合。 */
        public static final String STATIC_HEADERS = "static-headers";

        /** 认证策略标识；默认不发送凭据。 */
        private String type = NONE;

        /** Basic 用户名，仅在 basic 模式有效。 */
        private String username;

        /** Basic 密码，仅在 basic 模式有效。 */
        private String password;

        /** Bearer 或 API Key 凭据，只在对应模式有效。 */
        private String token;

        /** API Key Header 名，只在 api-key-header 模式有效。 */
        private String headerName;

        /** static-headers 模式下的显式 Header 集合。 */
        private Map<String, String> headers = new LinkedHashMap<String, String>();

        /** Jackson 收集的未知字段，由 McpServerConfigValidator 统一拒绝。 */
        private final Map<String, Object> unknownFields = new LinkedHashMap<String, Object>();

        /** 返回决定凭据解释方式的认证类型。 */
        public String getType() { return type; }

        /** 写入认证类型；字段组合由统一 Validator 校验。 */
        public void setType(String type) { this.type = type; }

        /** 返回 Basic 用户名。 */
        public String getUsername() { return username; }

        /** 写入 Basic 用户名；其他认证类型会拒绝该字段。 */
        public void setUsername(String username) { this.username = username; }

        /** 返回只在服务端使用的 Basic 密码。 */
        public String getPassword() { return password; }

        /** 写入 Basic 密码；响应层从不序列化整个 Auth。 */
        public void setPassword(String password) { this.password = password; }

        /** 返回 Bearer 或 API Key 凭据。 */
        public String getToken() { return token; }

        /** 写入 Token；响应层从不序列化整个 Auth。 */
        public void setToken(String token) { this.token = token; }

        /** 返回 API Key Header 名。 */
        public String getHeaderName() { return headerName; }

        /** 写入 Header 名；协议保留 Header 由 Validator 拒绝。 */
        public void setHeaderName(String headerName) { this.headerName = headerName; }

        /** 返回 static-headers 模式的服务端出站 Header。 */
        public Map<String, String> getHeaders() { return headers; }

        /** 写入静态 Header；禁止项与换行注入由 Validator 统一检查。 */
        public void setHeaders(Map<String, String> headers) { this.headers = headers; }

        /** 返回 Jackson 捕获的未知字段证据；不参与序列化，避免污染存储与响应格式。 */
        @JsonIgnore
        public Map<String, Object> getUnknownFields() { return unknownFields; }

        /** 收集 Jackson 全局配置可能忽略的未知字段，防止错误配置悄然通过。 */
        @JsonAnySetter
        public void captureUnknownField(String name, Object value) {
            unknownFields.put(name, value);
        }
    }

    /**
     * Tool 白名单 / 黑名单与权限覆盖：远程 Server 有什么 Tool 与
     * 本企业用户能调用什么 Tool 是两件事（设计文档第 24 节）。
     *
     * <p>未知字段由 Jackson 收集，供校验入口统一拒绝。
     */
    public static class ToolsFilter {
        /** 只导入这些远程 Tool（为空表示全部）。 */
        private List<String> include;
        /** 排除这些远程 Tool。 */
        private List<String> exclude;
        /** 远程 Tool 名 → 权限标识（交给 ToolAccessPolicy 判读）。 */
        private Map<String, String> permissions = new LinkedHashMap<String, String>();

        /** Jackson 收集的未知字段，由 McpServerConfigValidator 统一拒绝。 */
        private final Map<String, Object> unknownFields = new LinkedHashMap<String, Object>();

        /** 返回只导入的远程 Tool 名；null 或空集合表示不限制。 */
        public List<String> getInclude() { return include; }

        /** 写入远程 Tool allowlist。 */
        public void setInclude(List<String> include) { this.include = include; }

        /** 返回显式排除的远程 Tool 名。 */
        public List<String> getExclude() { return exclude; }

        /** 写入远程 Tool denylist；与 include 冲突会被 Validator 拒绝。 */
        public void setExclude(List<String> exclude) { this.exclude = exclude; }

        /** 返回远程 Tool 名到宿主权限标识的映射。 */
        public Map<String, String> getPermissions() { return permissions; }

        /** 写入权限覆盖；最终判读仍由宿主 ToolAccessPolicy 负责。 */
        public void setPermissions(Map<String, String> permissions) { this.permissions = permissions; }

        /** 返回 Jackson 捕获的未知字段证据；不参与序列化，避免污染存储与响应格式。 */
        @JsonIgnore
        public Map<String, Object> getUnknownFields() { return unknownFields; }

        /** 收集 Jackson 全局配置可能忽略的未知字段，防止错误配置悄然通过。 */
        @JsonAnySetter
        public void captureUnknownField(String name, Object value) {
            unknownFields.put(name, value);
        }
    }
}
