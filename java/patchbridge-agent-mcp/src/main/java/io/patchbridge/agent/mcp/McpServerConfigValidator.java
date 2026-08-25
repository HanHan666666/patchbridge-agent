package io.patchbridge.agent.mcp;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Global MCP Server 配置的唯一校验入口。
 *
 * <p>properties、JDBC Admin 和运行时重载共用同一套边界规则，
 * 防止某个 Adapter 接受了协议客户端实际无法安全执行的配置。
 * URL 校验只限定 HTTP(S) 语法，不假装是网络出口策略；Global MCP Admin
 * 是可配置服务端出站目标的高权限边界，生产应由宿主网络、防火墙或代理
 * 限制可访问网段，不应仅依赖字符串 URL 校验防御 SSRF。
 */
public final class McpServerConfigValidator {

    /** Server key 同时是 Tool 全名的单个命名空间段，因此禁止点号和路径字符。 */
    private static final String SERVER_NAME_PATTERN = "[A-Za-z0-9][A-Za-z0-9_-]{0,63}";

    /** Header 名按 RFC token 约束，避免管理输入改变 HTTP 语义。 */
    private static final String HEADER_NAME_PATTERN = "[!#$%&'*+.^_`|~0-9A-Za-z-]+";

    /**
     * 由 OkHttp、HTTP 传输层或 MCP 会话协议控制的 Header。
     * 认证必须选择对应 auth.type，因此 Authorization 也不允许绕过类型边界。
     */
    private static final Set<String> FORBIDDEN_CONFIGURED_HEADERS =
            Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
                    "host", "content-length", "transfer-encoding", "connection",
                    "keep-alive", "proxy-connection", "proxy-authorization", "te",
                    "trailer", "upgrade", "accept", "content-type", "mcp-session-id",
                    "mcp-protocol-version", "authorization", "cookie")));

    /** 纯工具类不允许实例化。 */
    private McpServerConfigValidator() {
    }

    /** 校验 Server 名称和完整配置，失败时返回可操作的明确原因。 */
    public static void validate(String name, McpServerConfig config) {
        if (name == null || !name.matches(SERVER_NAME_PATTERN)) {
            throw new IllegalArgumentException(
                    "MCP Server 名称必须为 1-64 位字母、数字、下划线或连字符");
        }
        if (config == null) {
            throw new IllegalArgumentException("MCP Server 配置不能为空");
        }
        validateUrl(config.getUrl());
        if (!"streamable-http".equals(config.getTransport())) {
            throw new IllegalArgumentException("MCP transport 第一版只支持 streamable-http");
        }
        if (config.getTimeoutMs() < 100 || config.getTimeoutMs() > 300000) {
            throw new IllegalArgumentException("MCP timeoutMs 必须介于 100 与 300000 之间");
        }
        if (config.getCacheTtlMs() < 1000 || config.getCacheTtlMs() > 86400000) {
            throw new IllegalArgumentException("MCP cacheTtlMs 必须介于 1000 与 86400000 之间");
        }
        validateAuth(config.getAuth());
        validateTools(config.getTools());
    }

    /** 校验目标为不包用户信息的 HTTP(S) 绝对地址。 */
    private static void validateUrl(String value) {
        try {
            URI uri = new URI(value == null ? "" : value);
            if (!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException(
                        "MCP url 必须为不含用户信息和 fragment 的 HTTP(S) 绝对地址");
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("MCP url 格式无效", e);
        }
    }

    /** 按认证类型校验所需凭据，禁止留下半配置的运行状态。 */
    private static void validateAuth(McpServerConfig.Auth auth) {
        if (auth == null || auth.getType() == null) {
            throw new IllegalArgumentException("MCP auth.type 不能为空");
        }
        rejectUnknownFields(auth.getUnknownFields(), "MCP auth");
        String type = auth.getType();
        if (McpServerConfig.Auth.NONE.equals(type)) {
            rejectText(auth.getUsername(), "none username");
            rejectText(auth.getPassword(), "none password");
            rejectText(auth.getToken(), "none token");
            rejectText(auth.getHeaderName(), "none headerName");
            rejectHeaders(auth.getHeaders(), "none headers");
            return;
        }
        if (McpServerConfig.Auth.BASIC.equals(type)) {
            requireText(auth.getUsername(), "basic username");
            requireText(auth.getPassword(), "basic password");
            rejectHeaderValue(auth.getUsername(), "basic username");
            rejectHeaderValue(auth.getPassword(), "basic password");
            rejectText(auth.getToken(), "basic token");
            rejectText(auth.getHeaderName(), "basic headerName");
            rejectHeaders(auth.getHeaders(), "basic headers");
            return;
        }
        if (McpServerConfig.Auth.BEARER.equals(type)) {
            requireText(auth.getToken(), "bearer token");
            rejectHeaderValue(auth.getToken(), "bearer token");
            rejectText(auth.getUsername(), "bearer username");
            rejectText(auth.getPassword(), "bearer password");
            rejectText(auth.getHeaderName(), "bearer headerName");
            rejectHeaders(auth.getHeaders(), "bearer headers");
            return;
        }
        if (McpServerConfig.Auth.API_KEY_HEADER.equals(type)) {
            validateHeaderName(auth.getHeaderName());
            requireText(auth.getToken(), "api key");
            rejectHeaderValue(auth.getToken(), "api key");
            rejectText(auth.getUsername(), "api-key username");
            rejectText(auth.getPassword(), "api-key password");
            rejectHeaders(auth.getHeaders(), "api-key headers");
            return;
        }
        if (McpServerConfig.Auth.STATIC_HEADERS.equals(type)) {
            Map<String, String> headers = auth.getHeaders();
            if (headers == null || headers.isEmpty()) {
                throw new IllegalArgumentException("static-headers 至少需要一个 Header");
            }
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                validateHeaderName(entry.getKey());
                requireText(entry.getValue(), "static header value");
                rejectHeaderValue(entry.getValue(), "static header value");
            }
            rejectText(auth.getUsername(), "static-headers username");
            rejectText(auth.getPassword(), "static-headers password");
            rejectText(auth.getToken(), "static-headers token");
            rejectText(auth.getHeaderName(), "static-headers headerName");
            return;
        }
        throw new IllegalArgumentException("不支持的 MCP auth.type: " + type);
    }

    /** 校验 Tool 筛选与权限覆盖，拒绝相互矛盾的 include/exclude。 */
    private static void validateTools(McpServerConfig.ToolsFilter tools) {
        if (tools == null) {
            throw new IllegalArgumentException("MCP tools 配置不能为空");
        }
        rejectUnknownFields(tools.getUnknownFields(), "MCP tools");
        Set<String> include = new HashSet<String>(tools.getInclude() == null
                ? Collections.<String>emptyList() : tools.getInclude());
        Set<String> exclude = new HashSet<String>(tools.getExclude() == null
                ? Collections.<String>emptyList() : tools.getExclude());
        include.retainAll(exclude);
        if (!include.isEmpty()) {
            throw new IllegalArgumentException("MCP Tool 不能同时出现在 include 和 exclude: "
                    + include.iterator().next());
        }
        if (tools.getPermissions() != null) {
            for (Map.Entry<String, String> entry : tools.getPermissions().entrySet()) {
                requireText(entry.getKey(), "Tool permission 名称");
                requireText(entry.getValue(), "Tool permission 值");
            }
        }
    }

    /** 拒绝 Jackson 捕获的未知嵌套字段，作为所有配置入口的统一证据判定。 */
    private static void rejectUnknownFields(Map<String, Object> unknownFields, String scope) {
        if (unknownFields != null && !unknownFields.isEmpty()) {
            throw new IllegalArgumentException(
                    scope + " 包含未知字段: " + unknownFields.keySet());
        }
    }

    /** 校验 Header 名是单一 RFC token。 */
    private static void validateHeaderName(String name) {
        if (name == null || !name.matches(HEADER_NAME_PATTERN)) {
            throw new IllegalArgumentException("MCP Header 名格式无效");
        }
        if (FORBIDDEN_CONFIGURED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "MCP Header 由 HTTP/MCP 协议或认证类型统一控制，不允许自定义: " + name);
        }
    }

    /** 拒绝会造成 HTTP Header 注入的换行符。 */
    private static void rejectHeaderValue(String value, String field) {
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(field + " 不能包含换行符");
        }
    }

    /** 校验必填文本，不接受纯空白值。 */
    private static void requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }

    /** 拒绝当前认证类型不会使用的额外文本，避免管理员误以为已生效。 */
    private static void rejectText(String value, String field) {
        if (value != null && !value.isEmpty()) {
            throw new IllegalArgumentException(field + " 不属于当前认证类型");
        }
    }

    /** 拒绝当前认证类型不会使用的静态 Header Map。 */
    private static void rejectHeaders(Map<String, String> headers, String field) {
        if (headers != null && !headers.isEmpty()) {
            throw new IllegalArgumentException(field + " 不属于当前认证类型");
        }
    }

    /** 判断当前认证配置是否包含凭据，仅用于生成脱敏布尔标记。 */
    public static boolean credentialConfigured(McpServerConfig.Auth auth) {
        return auth != null && !McpServerConfig.Auth.NONE.equals(auth.getType());
    }
}
