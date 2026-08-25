package io.patchbridge.agent.starter;

import io.patchbridge.agent.mcp.McpServerConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * patchbridge-agent.* 配置项。
 *
 * <p>可选配置提供合理默认值，但“配置在启动/绑定边界明确失败”是硬约束：
 * 数值范围、枚举取值和 namespace 形状都在 setter 中即时校验，
 * 错误配置不会带着静默修正后的值进入运行时。Starter 默认 Bean 均通过
 * 条件装配让位于宿主端口实现；Web 资源 Configurer 使用稳定 Bean 名作为替换边界。
 */
@ConfigurationProperties(prefix = "patchbridge-agent", ignoreUnknownFields = false)
public class PatchBridgeAgentProperties {

    /** 是否装配整个 Starter；默认开启。 */
    private boolean enabled = true;

    /** 浏览器 Agent 的独占 MVC 命名空间；根路径表示拥有全部 MVC 路径。 */
    private String basePath = "/ai";

    /** 默认 OpenAI-compatible Provider 的服务端配置。 */
    private final Model model = new Model();

    /** 会话 HTTP Adapter 的查询边界。 */
    private final Conversations conversations = new Conversations();

    /** 审计写入与载荷策略。 */
    private final Audit audit = new Audit();

    /** Global MCP Provider 与配置源。 */
    private final Mcp mcp = new Mcp();

    /** 默认关闭的管理端能力。 */
    private final Admin admin = new Admin();

    /** 表示 Starter 是否参与当前宿主应用装配。 */
    public boolean isEnabled() { return enabled; }

    /** 显式启用或关闭整个 Starter，不创建替代实现。 */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /** 返回 Controller、资源和拦截器共享的 API 根路径。 */
    public String getBasePath() { return basePath; }

    /**
     * 设置统一 API 独占命名空间。非根路径必须以单个斜杠开头且不带尾斜杠；
     * 根路径 / 明确表示宿主全部 MVC RequestMapping 均受 Starter 所有权约束。
     */
    public void setBasePath(String basePath) {
        if (basePath == null
                || (!("/".equals(basePath))
                && !basePath.matches("/[A-Za-z0-9._~-]+(?:/[A-Za-z0-9._~-]+)*"))) {
            throw new IllegalArgumentException(
                    "patchbridge-agent.base-path 必须是 / 或不带尾斜杠的绝对路径");
        }
        this.basePath = basePath;
    }

    /** 返回默认模型 Provider 配置。 */
    public Model getModel() { return model; }

    /** 返回会话 Adapter 配置。 */
    public Conversations getConversations() { return conversations; }

    /** 返回审计配置。 */
    public Audit getAudit() { return audit; }

    /** 返回 Global MCP 配置。 */
    public Mcp getMcp() { return mcp; }

    /** 返回管理端配置。 */
    public Admin getAdmin() { return admin; }

    /** 模型网关：OpenAI-compatible 流式代理。凭据只存在服务端，浏览器不可见。 */
    public static class Model {
        /** OpenAI-compatible base-url（如 https://api.deepseek.com/v1）。 */
        private String baseUrl;

        /** 只保存在服务端的上游 Bearer 凭据；目标网关无需认证时可空。 */
        private String apiKey;

        /** Browser 未覆盖时使用的默认模型名。 */
        private String model;

        /** 建连超时；0 明确表示不设上限。 */
        private int connectTimeoutMs = 10000;

        /** 流式读超时：长回答需要足够大；0 明确表示不设上限。 */
        private int readTimeoutMs = 300000;

        /** 返回模型服务根地址。 */
        public String getBaseUrl() { return baseUrl; }

        /** 写入根地址；默认 Provider 在构造期完成绝对 HTTP(S) 校验。 */
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        /** 返回只供服务端出站请求使用的凭据。 */
        public String getApiKey() { return apiKey; }

        /** 写入上游凭据，不提供生成或推导默认值。 */
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        /** 返回默认模型名。 */
        public String getModel() { return model; }

        /** 写入默认模型名；默认 Provider 要求非空白。 */
        public void setModel(String model) { this.model = model; }

        /** 返回建连超时毫秒数。 */
        public int getConnectTimeoutMs() { return connectTimeoutMs; }

        /** 负超时没有合法语义；0 显式保留 OkHttp 的“无超时”语义。 */
        public void setConnectTimeoutMs(int connectTimeoutMs) {
            if (connectTimeoutMs < 0) {
                throw new IllegalArgumentException(
                        "patchbridge-agent.model.connect-timeout-ms 不能为负数");
            }
            this.connectTimeoutMs = connectTimeoutMs;
        }

        /** 返回流式响应读超时毫秒数。 */
        public int getReadTimeoutMs() { return readTimeoutMs; }

        /** 负超时没有合法语义；0 显式保留 OkHttp 的“无超时”语义。 */
        public void setReadTimeoutMs(int readTimeoutMs) {
            if (readTimeoutMs < 0) {
                throw new IllegalArgumentException(
                        "patchbridge-agent.model.read-timeout-ms 不能为负数");
            }
            this.readTimeoutMs = readTimeoutMs;
        }
    }

    /** 会话列表查询的显式资源边界。 */
    public static class Conversations {
        /** 会话列表返回条数上限。 */
        private int listLimit = 50;

        /** 返回单次会话列表最多读取的记录数。 */
        public int getListLimit() { return listLimit; }

        /** 上限为 0 或负数会让列表能力不可用，必须在绑定期失败而不是返回空列表。 */
        public void setListLimit(int listLimit) {
            if (listLimit <= 0) {
                throw new IllegalArgumentException(
                        "patchbridge-agent.conversations.list-limit 必须大于 0");
            }
            this.listLimit = listLimit;
        }
    }

    /** 审计是否写入以及 payload 摘要的最小安全策略。 */
    public static class Audit {
        /** 是否记录调用事件；关闭后不创建 payload 或持久化写入。 */
        private boolean enabled = true;

        /** full：记录脱敏后的入参/出参摘要；metadata-only / none：只记录元数据。 */
        private String payloadMode = "metadata-only";

        /** 摘要最大长度，防止大结果拖垮审计表。 */
        private int summaryMaxLength = 4000;

        /** 表示审计事件写入是否启用。 */
        public boolean isEnabled() { return enabled; }

        /** 显式启用或关闭审计写入。 */
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        /** 返回 payload 记录策略。 */
        public String getPayloadMode() { return payloadMode; }

        /** 拼写错误的模式不允许静默当作 metadata-only 处理，必须在绑定期失败。 */
        public void setPayloadMode(String payloadMode) {
            if (!("full".equals(payloadMode) || "metadata-only".equals(payloadMode)
                    || "none".equals(payloadMode))) {
                throw new IllegalArgumentException(
                        "patchbridge-agent.audit.payload-mode 只支持 "
                                + "full、metadata-only 或 none");
            }
            this.payloadMode = payloadMode;
        }

        /** 返回脱敏摘要允许的最大字符数。 */
        public int getSummaryMaxLength() { return summaryMaxLength; }

        /** 非正长度会让摘要能力不可用，必须在绑定期失败。 */
        public void setSummaryMaxLength(int summaryMaxLength) {
            if (summaryMaxLength <= 0) {
                throw new IllegalArgumentException(
                        "patchbridge-agent.audit.summary-max-length 必须大于 0");
            }
            this.summaryMaxLength = summaryMaxLength;
        }
    }

    /** Global MCP 的启用开关、单一配置源与 Tool 命名空间。 */
    public static class Mcp {
        /** 是否装配 MCP Provider 与管理能力。 */
        private boolean enabled = true;
        /** properties 为显式只读模式；jdbc 为 Global Admin 动态管理模式。 */
        private String source = "properties";
        /** MCP 统一命名空间根（全名 = root.serverKey.toolName）。 */
        private String namespace = "mcp";
        /** properties 模式绑定的全局 Server 配置；与 JDBC 源互斥。 */
        private Map<String, McpServerConfig> servers = new LinkedHashMap<String, McpServerConfig>();

        /** JDBC 动态配置源的独立安全参数。 */
        private final Jdbc jdbc = new Jdbc();

        /** 表示默认 MCP Provider 与管理能力是否参与装配。 */
        public boolean isEnabled() { return enabled; }

        /** 显式启用或关闭 MCP；关闭时不装配默认客户端、Store、Registry 或 Admin。 */
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        /** 返回唯一配置源标识。 */
        public String getSource() { return source; }

        /** 设置互斥配置源，禁止 properties 与 JDBC 合并。 */
        public void setSource(String source) {
            if (!("properties".equals(source) || "jdbc".equals(source))) {
                throw new IllegalArgumentException(
                        "patchbridge-agent.mcp.source 只支持 properties 或 jdbc");
            }
            this.source = source;
        }
        /** 返回完整 MCP Tool 名使用的根命名空间。 */
        public String getNamespace() { return namespace; }

        /**
         * 设置 Tool 命名空间根。namespace 会拼进 Tool 全名并作为稳定路由标识，
         * 因此只允许字母/数字/下划线/连字符组成的点分段，总长不超过 128。
         */
        public void setNamespace(String namespace) {
            if (namespace == null || namespace.isEmpty() || namespace.length() > 128
                    || !namespace.matches("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*")) {
                throw new IllegalArgumentException(
                        "patchbridge-agent.mcp.namespace 必须是由字母数字下划线连字符组成、"
                                + "可用点分段且总长不超过 128 的标识符");
            }
            this.namespace = namespace;
        }
        /** 返回 properties 模式下由 Binder 维护的 Server Map。 */
        public Map<String, McpServerConfig> getServers() { return servers; }

        /** servers 整体置 null 没有合法语义，绑定期明确失败而不是回退默认空 Map。 */
        public void setServers(Map<String, McpServerConfig> servers) {
            if (servers == null) {
                throw new IllegalArgumentException(
                        "patchbridge-agent.mcp.servers 不能为 null；无 Server 时请省略该配置");
            }
            this.servers = servers;
        }
        /** 返回 JDBC 配置源的凭据加密参数。 */
        public Jdbc getJdbc() { return jdbc; }

        /** JDBC MCP 配置源的安全参数。 */
        public static class Jdbc {
            /**
             * AES-256 密钥的标准 Base64 文本。只在 source=jdbc 时读取，
             * 应由环境变量或宿主外部化配置注入。
             */
            private String encryptionKey;

            /** 返回外部注入的 MCP 凭据主密钥。 */
            public String getEncryptionKey() { return encryptionKey; }

            /** 设置外部注入的 MCP 凭据主密钥，不生成或推导默认值。 */
            public void setEncryptionKey(String encryptionKey) {
                this.encryptionKey = encryptionKey;
            }
        }
    }

    /**
     * 管理端配置。默认关闭，开启时宿主必须提供 AdminAccessPolicy，
     * 防止 Starter 因宿主遗漏 URL 安全规则而暴露审计与 MCP 管理能力。
     */
    public static class Admin {
        /** 是否装配管理 API、控制台资源和强制授权拦截器。 */
        private boolean enabled = false;

        /** 表示高权限管理面是否显式启用。 */
        public boolean isEnabled() { return enabled; }

        /** 启用后仍必须由宿主提供 AdminAccessPolicy，不存在放行默认值。 */
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
