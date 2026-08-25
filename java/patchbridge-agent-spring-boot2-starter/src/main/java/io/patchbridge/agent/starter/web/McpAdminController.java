package io.patchbridge.agent.starter.web;

import io.patchbridge.agent.mcp.McpConfigurationManager;
import io.patchbridge.agent.mcp.McpCredentialUpdate;
import io.patchbridge.agent.mcp.McpException;
import io.patchbridge.agent.mcp.McpServerConfig;
import io.patchbridge.agent.mcp.McpServerRecord;
import io.patchbridge.agent.mcp.McpServerView;
import io.patchbridge.agent.mcp.McpToolRegistry;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Global MCP Server 配置与运行状态管理 API。
 *
 * <p>GET 路由只返回脱敏配置和运行快照；凭据只能通过创建或
 * REPLACE 指令写入，任何响应都不包含 username、password、token 或 static headers。
 * properties 模式仍保留查看、Tool 列表、刷新和测试能力，变更路由明确拒绝。
 * 访问控制由 AdminAuthorizationInterceptor 统一映射到 MCP_READ / MCP_MANAGE。
 */
@RestController
@ConditionalOnExpression("${patchbridge-agent.enabled:true}"
        + " and ${patchbridge-agent.admin.enabled:false}"
        + " and ${patchbridge-agent.mcp.enabled:true}")
@ConditionalOnBean(AdminAuthorizationInterceptor.class)
@RequestMapping("${patchbridge-agent.base-path:/ai}")
public class McpAdminController {

    /** 提供 Tool 快照、刷新和运行状态。 */
    private final McpToolRegistry registry;

    /** 收口配置读写与运行时快照发布。 */
    private final McpConfigurationManager manager;

    /** 创建 MCP Admin API。 */
    public McpAdminController(McpToolRegistry registry, McpConfigurationManager manager) {
        this.registry = registry;
        this.manager = manager;
    }

    /** 返回当前配置源、可变更性以及所有 Server 脱敏视图。 */
    @GetMapping("/admin/mcp/servers")
    public Map<String, Object> servers() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("source", manager.source());
        body.put("mutable", manager.mutable());
        List<Map<String, Object>> serverViews = new ArrayList<Map<String, Object>>();
        Map<String, McpServerView> runtimeViews = new LinkedHashMap<String, McpServerView>();
        for (McpServerView runtime : registry.serverViews()) {
            runtimeViews.put(runtime.getName(), runtime);
        }
        for (McpServerRecord record : manager.records()) {
            serverViews.add(toResponse(record, runtimeViews.get(record.getName())));
        }
        body.put("servers", serverViews);
        return body;
    }

    /** 返回指定 Server 当前已导入的 Tool 快照。 */
    @GetMapping("/admin/mcp/servers/{name}/tools")
    public Map<String, Object> tools(@PathVariable("name") String name) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("tools", registry.serverTools(name));
        return body;
    }

    /** 创建 Global MCP Server；非 none 认证必须一次性提供完整凭据。 */
    @PostMapping("/admin/mcp/servers")
    public ResponseEntity<Map<String, Object>> create(@RequestBody ServerWriteRequest request) {
        request.validateCreate();
        McpServerRecord created = manager.create(request.getName(), request.toConfig());
        return ResponseEntity.status(HttpStatus.CREATED).body(serverBody(created));
    }

    /** 更新完整配置，必须显式声明凭据是 KEEP、REPLACE 还是 CLEAR。 */
    @PutMapping("/admin/mcp/servers/{name}")
    public Map<String, Object> update(@PathVariable("name") String name,
                                      @RequestBody ServerWriteRequest request) {
        request.validateUpdate(name);
        McpServerRecord updated = manager.update(name, request.toConfig(),
                request.requireRevision(), request.requireCredentialUpdate());
        return serverBody(updated);
    }

    /** 仅更改 Server 启用状态，不触及凭据和其他字段。 */
    @PostMapping("/admin/mcp/servers/{name}/enabled")
    public Map<String, Object> setEnabled(@PathVariable("name") String name,
                                          @RequestBody EnabledRequest request) {
        request.validate();
        McpServerRecord updated = manager.setEnabled(name, request.requireEnabled(),
                request.requireRevision());
        return serverBody(updated);
    }

    /** 删除指定 revision 的 Server，避免删除已被他人修改的新版配置。 */
    @DeleteMapping("/admin/mcp/servers/{name}")
    public ResponseEntity<Void> delete(@PathVariable("name") String name,
                                       @RequestParam("revision") long revision) {
        manager.delete(name, revision);
        return ResponseEntity.noContent().build();
    }

    /** 手工刷新指定 Server 的 tools/list 快照。 */
    @PostMapping("/admin/mcp/servers/{name}/refresh")
    public Map<String, Object> refresh(@PathVariable("name") String name) {
        registry.refresh(name);
        return serverBody(manager.require(name));
    }

    /** 执行一次真实 tools/list 连通性测试，不回传请求凭据。 */
    @PostMapping("/admin/mcp/servers/{name}/test")
    public ResponseEntity<Map<String, Object>> test(@PathVariable("name") String name) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        try {
            registry.refresh(name);
            McpServerView view = findRuntimeView(name);
            body.put("ok", true);
            body.put("latencyMs", view == null ? -1 : view.getLastLatencyMs());
        } catch (McpException e) {
            body.put("ok", false);
            body.put("error", e.getMessage());
        }
        return ResponseEntity.ok(body);
    }

    /** 组装单个 Server 的统一响应外层。 */
    private Map<String, Object> serverBody(McpServerRecord record) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("server", toResponse(record, findRuntimeView(record.getName())));
        return body;
    }

    /** 从 Registry 快照中查找对应运行状态。 */
    private McpServerView findRuntimeView(String name) {
        for (McpServerView view : registry.serverViews()) {
            if (view.getName().equals(name)) {
                return view;
            }
        }
        return null;
    }

    /** 显式选取可对外字段，确保内存中的解密 auth 不被 Jackson 遍历。 */
    private static Map<String, Object> toResponse(McpServerRecord record,
                                                  McpServerView runtime) {
        McpServerConfig config = record.getConfig();
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("name", record.getName());
        response.put("url", config.getUrl());
        response.put("transport", config.getTransport());
        response.put("enabled", config.isEnabled());
        response.put("timeoutMs", config.getTimeoutMs());
        response.put("cacheTtlMs", config.getCacheTtlMs());
        response.put("authType", config.getAuth().getType());
        response.put("credentialConfigured", record.isCredentialConfigured());
        response.put("tools", config.getTools());
        response.put("revision", record.getRevision());
        response.put("createdAt", record.getCreatedAt());
        response.put("updatedAt", record.getUpdatedAt());
        response.put("status", runtime == null ? "DOWN" : runtime.getStatus());
        response.put("toolCount", runtime == null ? 0 : runtime.getToolCount());
        response.put("lastRefreshAt", runtime == null ? null : runtime.getLastRefreshAt());
        response.put("lastLatencyMs", runtime == null ? -1L : runtime.getLastLatencyMs());
        response.put("lastError", runtime == null ? null : runtime.getLastError());
        return response;
    }

    /**
     * Admin 创建/更新请求，其 auth 字段只用于单向写入。
     *
     * <p>与 Model/Conversation 契约一致：未知字段由 {@code @JsonAnySetter}
     * 收集并在 validate 阶段拒绝；显式 null 的 auth/tools 视为协议违规，
     * 不得被替换为默认对象——默认对象仅用于“字段整体缺失”的场景。
     */
    public static final class ServerWriteRequest {
        /** 创建时使用的全局唯一 Server 名称；更新时以路径参数为准。 */
        private String name;

        /** MCP Streamable HTTP endpoint。 */
        private String url;

        /** 第一版固定支持的 MCP 传输类型。 */
        private String transport = "streamable-http";

        /** 是否把该 Server 暴露到运行时 Tool 路由。 */
        private boolean enabled = true;

        /** 单次远程 MCP 请求超时。 */
        private long timeoutMs = 30000L;

        /** tools/list 快照与失败重试的缓存时间。 */
        private long cacheTtlMs = 300000L;

        /** 只写认证对象；缺失时使用明确的 none 默认，显式 null 由校验拒绝。 */
        private McpServerConfig.Auth auth = new McpServerConfig.Auth();

        /** 标记 auth 字段是否被显式置为 null，用于区分“缺失”与“显式 null”。 */
        private boolean authExplicitlyNull;

        /** 远程 Tool 导入过滤和权限映射；缺失时使用明确默认，显式 null 由校验拒绝。 */
        private McpServerConfig.ToolsFilter tools = new McpServerConfig.ToolsFilter();

        /** 标记 tools 字段是否被显式置为 null，用于区分“缺失”与“显式 null”。 */
        private boolean toolsExplicitlyNull;

        /** 更新目标的乐观锁版本；创建时不使用。 */
        private Long revision;

        /** 更新时必填的凭据处理指令，避免空字段误清除密文。 */
        private String credentialUpdate;

        /** Jackson 收集的未知字段，校验时统一拒绝。 */
        private final Map<String, Object> unknownFields = new LinkedHashMap<String, Object>();

        /** 返回创建时的 Server 名称。 */
        public String getName() { return name; }
        /** 设置创建时的 Server 名称。 */
        public void setName(String name) { this.name = name; }
        /** 返回远程 endpoint。 */
        public String getUrl() { return url; }
        /** 设置远程 endpoint。 */
        public void setUrl(String url) { this.url = url; }
        /** 返回传输类型。 */
        public String getTransport() { return transport; }
        /** 设置传输类型。 */
        public void setTransport(String transport) { this.transport = transport; }
        /** 返回是否启用。 */
        public boolean isEnabled() { return enabled; }
        /** 设置是否启用。 */
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        /** 返回请求超时。 */
        public long getTimeoutMs() { return timeoutMs; }
        /** 设置请求超时。 */
        public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
        /** 返回 Tool 快照 TTL。 */
        public long getCacheTtlMs() { return cacheTtlMs; }
        /** 设置 Tool 快照 TTL。 */
        public void setCacheTtlMs(long cacheTtlMs) { this.cacheTtlMs = cacheTtlMs; }
        /** 返回单向写入认证对象。 */
        public McpServerConfig.Auth getAuth() { return auth; }
        /** 设置单向写入认证对象；记录显式 null 供校验拒绝。 */
        public void setAuth(McpServerConfig.Auth auth) {
            this.auth = auth;
            this.authExplicitlyNull = auth == null;
        }
        /** 返回 Tool 导入规则。 */
        public McpServerConfig.ToolsFilter getTools() { return tools; }
        /** 设置 Tool 导入规则；记录显式 null 供校验拒绝。 */
        public void setTools(McpServerConfig.ToolsFilter tools) {
            this.tools = tools;
            this.toolsExplicitlyNull = tools == null;
        }
        /** 返回预期 revision。 */
        public Long getRevision() { return revision; }
        /** 设置预期 revision。 */
        public void setRevision(Long revision) { this.revision = revision; }
        /** 返回凭据更新指令。 */
        public String getCredentialUpdate() { return credentialUpdate; }
        /** 设置凭据更新指令。 */
        public void setCredentialUpdate(String credentialUpdate) { this.credentialUpdate = credentialUpdate; }

        /** 收集 Jackson 全局配置可能忽略的未知字段，防止错误输入悄然通过。 */
        @JsonAnySetter
        public void captureUnknownField(String name, Object value) {
            unknownFields.put(name, value);
        }

        /**
         * 创建场景校验：不允许携带 revision / credentialUpdate
         * （它们只对更新有意义），避免客户端误以为已生效。
         */
        public void validateCreate() {
            validateCommon();
            if (revision != null) {
                throw new IllegalArgumentException("创建请求不允许携带 revision");
            }
            if (credentialUpdate != null) {
                throw new IllegalArgumentException("创建请求不允许携带 credentialUpdate");
            }
        }

        /** 更新场景校验：body name 提供时必须与路径 name 一致，防止改错对象。 */
        public void validateUpdate(String pathName) {
            validateCommon();
            if (name != null && !name.equals(pathName)) {
                throw new IllegalArgumentException(
                        "请求体 name 与路径 name 不一致: " + name + " != " + pathName);
            }
        }

        /** 校验创建/更新共享的契约边界。 */
        private void validateCommon() {
            if (!unknownFields.isEmpty()) {
                throw new IllegalArgumentException(
                        "MCP Server 请求包含未知字段: " + unknownFields.keySet());
            }
            if (authExplicitlyNull) {
                throw new IllegalArgumentException(
                        "auth 显式为 null 不允许；如需清除凭据请使用 auth.type=none");
            }
            if (toolsExplicitlyNull) {
                throw new IllegalArgumentException(
                        "tools 显式为 null 不允许；如需全部导入请省略该字段");
            }
        }

        /** 将 Web DTO 转换为不依赖 Spring 的 MCP 配置模型。 */
        private McpServerConfig toConfig() {
            McpServerConfig config = new McpServerConfig();
            config.setUrl(url);
            config.setTransport(transport);
            config.setEnabled(enabled);
            config.setTimeoutMs(timeoutMs);
            config.setCacheTtlMs(cacheTtlMs);
            config.setAuth(auth);
            config.setTools(tools);
            return config;
        }

        /** 更新请求必须显式提供 revision。 */
        private long requireRevision() {
            if (revision == null || revision.longValue() < 0L) {
                throw new IllegalArgumentException("revision 必须是非负整数");
            }
            return revision.longValue();
        }

        /** 将必填凭据指令解析为稳定枚举。 */
        private McpCredentialUpdate requireCredentialUpdate() {
            try {
                return McpCredentialUpdate.valueOf(credentialUpdate == null
                        ? "" : credentialUpdate.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "credentialUpdate 必须为 KEEP、REPLACE 或 CLEAR");
            }
        }
    }

    /**
     * 启用/停用请求，单独建模以避免意外覆盖其他配置。
     *
     * <p>enabled 使用包装类型并要求显式提供：boolean 绑定无法区分
     * “未提供”与“显式 false”，会让漏传字段的请求被当作停用指令执行。
     */
    public static final class EnabledRequest {
        /** 目标启用状态；必须显式提供。 */
        private Boolean enabled;

        /** 目标记录的乐观锁版本。 */
        private Long revision;

        /** Jackson 收集的未知字段，校验时统一拒绝。 */
        private final Map<String, Object> unknownFields = new LinkedHashMap<String, Object>();

        /** 返回目标启用状态；未提供时为 null。 */
        public Boolean getEnabled() { return enabled; }
        /** 设置目标启用状态。 */
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
        /** 返回预期 revision。 */
        public Long getRevision() { return revision; }
        /** 设置预期 revision。 */
        public void setRevision(Long revision) { this.revision = revision; }

        /** 收集 Jackson 全局配置可能忽略的未知字段，防止错误输入悄然通过。 */
        @JsonAnySetter
        public void captureUnknownField(String name, Object value) {
            unknownFields.put(name, value);
        }

        /** 校验启用请求只包含契约内字段且 enabled 已显式提供。 */
        public void validate() {
            if (!unknownFields.isEmpty()) {
                throw new IllegalArgumentException(
                        "启用状态请求包含未知字段: " + unknownFields.keySet());
            }
            requireEnabled();
        }

        /** enabled 必须显式提供，缺失即协议违规。 */
        private boolean requireEnabled() {
            if (enabled == null) {
                throw new IllegalArgumentException("enabled 必须显式提供 true 或 false");
            }
            return enabled.booleanValue();
        }

        /** 启用状态变更必须通过乐观锁。 */
        private long requireRevision() {
            if (revision == null || revision.longValue() < 0L) {
                throw new IllegalArgumentException("revision 必须是非负整数");
            }
            return revision.longValue();
        }
    }
}
