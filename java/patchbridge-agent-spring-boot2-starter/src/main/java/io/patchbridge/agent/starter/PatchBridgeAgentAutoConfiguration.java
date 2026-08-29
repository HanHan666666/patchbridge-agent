package io.patchbridge.agent.starter;

import io.patchbridge.agent.core.audit.AuditQueryRepository;
import io.patchbridge.agent.core.audit.AuditRedactor;
import io.patchbridge.agent.core.audit.AuditSink;
import io.patchbridge.agent.core.auth.AdminAccessPolicy;
import io.patchbridge.agent.core.auth.AuthenticatedToolAccessPolicy;
import io.patchbridge.agent.core.auth.ToolAccessPolicy;
import io.patchbridge.agent.core.compaction.ContextCompactionProvider;
import io.patchbridge.agent.core.compaction.ContextCompactionSettings;
import io.patchbridge.agent.core.compaction.DefaultContextCompactionProvider;
import io.patchbridge.agent.core.conversation.ConversationOwnerResolver;
import io.patchbridge.agent.core.conversation.ConversationRepository;
import io.patchbridge.agent.core.conversation.UserIdConversationOwnerResolver;
import io.patchbridge.agent.core.interceptor.ModelCallInterceptor;
import io.patchbridge.agent.core.interceptor.ToolCallInterceptor;
import io.patchbridge.agent.core.invocation.DefaultModelGateway;
import io.patchbridge.agent.core.invocation.ModelGateway;
import io.patchbridge.agent.core.invocation.ModelInvocationPipeline;
import io.patchbridge.agent.core.invocation.ToolInvocationPipeline;
import io.patchbridge.agent.core.model.ModelProvider;
import io.patchbridge.agent.core.model.ModelStateProjector;
import io.patchbridge.agent.core.schema.SimpleReflectionSchemaGenerator;
import io.patchbridge.agent.core.schema.ToolSchemaGenerator;
import io.patchbridge.agent.core.tool.DefaultToolNamingStrategy;
import io.patchbridge.agent.core.tool.DefaultToolRegistry;
import io.patchbridge.agent.core.tool.ToolNamingStrategy;
import io.patchbridge.agent.core.tool.ToolProvider;
import io.patchbridge.agent.core.tool.ToolRegistry;
import io.patchbridge.agent.mcp.McpConfigurationManager;
import io.patchbridge.agent.mcp.McpConfigurationStore;
import io.patchbridge.agent.mcp.McpCredentialCipher;
import io.patchbridge.agent.mcp.McpToolRegistry;
import io.patchbridge.agent.mcp.PropertiesMcpConfigurationStore;
import io.patchbridge.agent.mcp.RemoteMcpClient;
import io.patchbridge.agent.mcp.StreamableHttpMcpClient;
import io.patchbridge.agent.starter.audit.AuditRecorder;
import io.patchbridge.agent.starter.model.OpenAiCompatibleModelProvider;
import io.patchbridge.agent.starter.security.SecurityContextCurrentUserProvider;
import io.patchbridge.agent.starter.tool.AnnotatedToolProvider;
import io.patchbridge.agent.starter.web.AdminApiController;
import io.patchbridge.agent.starter.web.AdminAuthorizationInterceptor;
import io.patchbridge.agent.starter.web.ConversationController;
import io.patchbridge.agent.starter.web.ContextCompactionController;
import io.patchbridge.agent.starter.web.McpAdminController;
import io.patchbridge.agent.starter.web.ModelStreamController;
import io.patchbridge.agent.starter.web.ToolGatewayController;
import io.patchbridge.agent.storage.jdbc.AesGcmMcpCredentialCipher;
import io.patchbridge.agent.storage.jdbc.JdbcAuditQueryRepository;
import io.patchbridge.agent.storage.jdbc.JdbcAuditSink;
import io.patchbridge.agent.storage.jdbc.JdbcConversationRepository;
import io.patchbridge.agent.storage.jdbc.JdbcMcpConfigurationStore;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

/**
 * 自动装配入口：引入 Starter 依赖即注册全部 /ai/** 端点与默认实现。
 *
 * <p>装配原则（项目总原则的 Spring 侧落地）：
 *
 * <ul>
 *   <li>所有默认 Bean 都带 @ConditionalOnMissingBean——宿主应用提供同类型 Bean 即自动让位；
 *   <li>JDBC 存储只在存在 DataSource 时装配；无 DataSource 且未自定义 ConversationRepository 时启动失败（v0.1
 *       会话持久化是必需能力，不做内存降级实现）；
 *   <li>Spring Security 只是可选 Adapter：类路径存在才装配默认 CurrentUserProvider， 否则宿主必须自己提供；
 *   <li>MCP Gateway 由 patchbridge-agent.mcp.enabled 控制，默认开启；显式关闭时不装配
 *       默认 RemoteMcpClient、配置源、密码器、Manager 与 MCP Admin，source=jdbc 也无需 DataSource/密钥。
 * </ul>
 */
@Configuration
@ConditionalOnProperty(
        prefix = "patchbridge-agent",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
// 依赖 DataSource / ObjectMapper / MVC 映射基础设施的条件装配必须在它们注册之后评估
@AutoConfigureAfter({DataSourceAutoConfiguration.class, JacksonAutoConfiguration.class,
        WebMvcAutoConfiguration.class})
@EnableConfigurationProperties(PatchBridgeAgentProperties.class)
public class PatchBridgeAgentAutoConfiguration {

    // ---------- 启动诊断 ----------

    /**
     * 注册唯一启动诊断入口；它只报告实际装配的默认实现，不参与配置修正或降级。
     */
    @Bean
    @ConditionalOnMissingBean(PatchBridgeAgentStartupDiagnostics.class)
    public PatchBridgeAgentStartupDiagnostics patchbridgeAgentStartupDiagnostics(
            ApplicationContext applicationContext, PatchBridgeAgentProperties properties) {
        return new PatchBridgeAgentStartupDiagnostics(applicationContext, properties);
    }

    // ---------- 契约默认实现 ----------

    @Bean
    @ConditionalOnMissingBean
    public ToolSchemaGenerator toolSchemaGenerator() {
        return new SimpleReflectionSchemaGenerator();
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolNamingStrategy toolNamingStrategy() {
        return new DefaultToolNamingStrategy();
    }

    @Bean
    @ConditionalOnMissingBean(ToolAccessPolicy.class)
    public ToolAccessPolicy toolAccessPolicy() {
        return new AuthenticatedToolAccessPolicy();
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.security.core.context.SecurityContextHolder")
    @ConditionalOnMissingBean(io.patchbridge.agent.core.auth.CurrentUserProvider.class)
    public SecurityContextCurrentUserProvider securityContextCurrentUserProvider() {
        return new SecurityContextCurrentUserProvider();
    }

    /** 单租户默认使用 userId 隔离会话；多租户宿主提供同类型 Bean 即可替换归属规则。 */
    @Bean
    @ConditionalOnMissingBean(ConversationOwnerResolver.class)
    public ConversationOwnerResolver conversationOwnerResolver() {
        return new UserIdConversationOwnerResolver();
    }

    // ---------- 模型网关 ----------

    /**
     * 从宿主必需的模型窗口配置派生 80% 阈值与近期保留预算。
     * 缺失窗口在启动期失败，不能由 Browser 或 Provider 猜测。
     */
    @Bean
    @ConditionalOnMissingBean(ContextCompactionSettings.class)
    public ContextCompactionSettings contextCompactionSettings(
            PatchBridgeAgentProperties properties) {
        Integer contextWindowTokens = properties.getModel().getContextWindowTokens();
        if (contextWindowTokens == null) {
            throw new IllegalStateException(
                    "patchbridge-agent.model.context-window-tokens 是必需配置");
        }
        return new ContextCompactionSettings(
                contextWindowTokens.intValue(), properties.getModel().getKeepRecentTokens());
    }

    @Bean
    @ConditionalOnMissingBean(ModelProvider.class)
    public OpenAiCompatibleModelProvider openAiCompatibleModelProvider(
            PatchBridgeAgentProperties properties) {
        return new OpenAiCompatibleModelProvider(properties.getModel());
    }

    // ---------- 存储默认实现（需要 DataSource） ----------

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(ConversationRepository.class)
    public JdbcConversationRepository jdbcConversationRepository(
            DataSource dataSource, ObjectMapper objectMapper) {
        return new JdbcConversationRepository(dataSource, objectMapper);
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(AuditSink.class)
    @ConditionalOnProperty(
            prefix = "patchbridge-agent.audit",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public JdbcAuditSink jdbcAuditSink(DataSource dataSource) {
        return new JdbcAuditSink(dataSource);
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(AuditQueryRepository.class)
    public JdbcAuditQueryRepository jdbcAuditQueryRepository(DataSource dataSource) {
        return new JdbcAuditQueryRepository(dataSource);
    }

    /** 安全默认脱敏器：即使宿主显式开启 full 载荷，也不会在未配置规则时写入原文。 企业需提供 AuditRedactor Bean 后才能按自己的数据分类规则保留脱敏摘要。 */
    @Bean
    @ConditionalOnMissingBean
    public AuditRedactor auditRedactor() {
        return new AuditRedactor() {
            @Override
            public String redact(io.patchbridge.agent.core.audit.AuditEvent event, String summary) {
                return "[REDACTED]";
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(AuditRecorder.class)
    public AuditRecorder auditRecorder(
            ObjectProvider<AuditSink> auditSink,
            AuditRedactor redactor,
            ObjectMapper objectMapper,
            PatchBridgeAgentProperties properties) {
        AuditSink sink = auditSink.getIfAvailable();
        if (sink == null) {
            // audit.enabled=false 时挂一个空实现，端点无需判空
            sink =
                    new AuditSink() {
                        @Override
                        public void write(io.patchbridge.agent.core.audit.AuditEvent event) {
                            // 审计已显式关闭
                        }
                    };
        }
        return new AuditRecorder(sink, redactor, objectMapper, properties.getAudit());
    }

    // ---------- Tool 聚合 ----------

    @Bean
    @ConditionalOnMissingBean(AnnotatedToolProvider.class)
    public AnnotatedToolProvider annotatedToolProvider(
            ListableBeanFactory beanFactory,
            ToolSchemaGenerator schemaGenerator,
            ObjectMapper objectMapper,
            ToolNamingStrategy namingStrategy) {
        return new AnnotatedToolProvider(
                beanFactory, schemaGenerator, objectMapper, namingStrategy.localToolNamespace());
    }

    /** MCP 关闭时不装配默认远程客户端，宿主自定义实现不受影响。 */
    @Bean
    @ConditionalOnMissingBean(RemoteMcpClient.class)
    @ConditionalOnProperty(
            prefix = "patchbridge-agent.mcp",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public RemoteMcpClient streamableHttpMcpClient() {
        return new StreamableHttpMcpClient();
    }

    /** properties 是默认且显式只读的 MCP 配置源。它不检测或合并 JDBC 数据，从而保持单一事实源。 */
    @Bean
    @ConditionalOnMissingBean(McpConfigurationStore.class)
    @ConditionalOnExpression("${patchbridge-agent.mcp.enabled:true}"
            + " and '${patchbridge-agent.mcp.source:properties}'.equals('properties')")
    public McpConfigurationStore propertiesMcpConfigurationStore(
            PatchBridgeAgentProperties properties) {
        return new PropertiesMcpConfigurationStore(properties.getMcp().getServers());
    }

    /**
     * JDBC 模式的 AES-GCM 凭据密码器；密钥缺失时启动直接失败。
     * MCP 关闭（mcp.enabled=false）时不装配，因此 source=jdbc 也无需提供密钥。
     */
    @Bean
    @ConditionalOnMissingBean({McpCredentialCipher.class, McpConfigurationStore.class})
    @ConditionalOnExpression("${patchbridge-agent.mcp.enabled:true}"
            + " and '${patchbridge-agent.mcp.source:}'.equals('jdbc')")
    public McpCredentialCipher mcpCredentialCipher(PatchBridgeAgentProperties properties) {
        return new AesGcmMcpCredentialCipher(properties.getMcp().getJdbc().getEncryptionKey());
    }

    /**
     * JDBC Global MCP 配置源。DataSource 是显式硬依赖，
     * source=jdbc 但宿主未提供时由 Spring 在启动期报错，不降级到 properties。
     * MCP 关闭时完全不装配，不要求 DataSource 或加密密钥。
     */
    @Bean
    @ConditionalOnMissingBean(McpConfigurationStore.class)
    @ConditionalOnExpression("${patchbridge-agent.mcp.enabled:true}"
            + " and '${patchbridge-agent.mcp.source:}'.equals('jdbc')")
    @DependsOnDatabaseInitialization
    public McpConfigurationStore jdbcMcpConfigurationStore(
            DataSource dataSource, ObjectMapper objectMapper, McpCredentialCipher cipher) {
        return new JdbcMcpConfigurationStore(dataSource, objectMapper, cipher);
    }

    /** MCP 开启时创建唯一远程 Tool Provider，并在初始化阶段加载配置快照。 */
    @Bean(initMethod = "refreshAll")
    @ConditionalOnMissingBean(McpToolRegistry.class)
    @ConditionalOnProperty(
            prefix = "patchbridge-agent.mcp",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public McpToolRegistry mcpToolRegistry(
            PatchBridgeAgentProperties properties,
            RemoteMcpClient client,
            McpConfigurationStore configurationStore) {
        return new McpToolRegistry(configurationStore, client, properties.getMcp().getNamespace());
    }

    /** 统一收口 Admin 配置变更与 Registry 快照发布。 */
    @Bean
    @ConditionalOnMissingBean(McpConfigurationManager.class)
    @ConditionalOnBean(McpToolRegistry.class)
    @ConditionalOnProperty(
            prefix = "patchbridge-agent.mcp",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public McpConfigurationManager mcpConfigurationManager(
            McpConfigurationStore configurationStore, McpToolRegistry registry) {
        return new McpConfigurationManager(configurationStore, registry);
    }

    @Bean
    @ConditionalOnMissingBean(ToolRegistry.class)
    public ToolRegistry toolRegistry(
            ObjectProvider<ToolProvider> providers, ToolAccessPolicy accessPolicy) {
        List<ToolProvider> providerList = new ArrayList<ToolProvider>();
        for (ToolProvider provider : providers) {
            providerList.add(provider);
        }
        return new DefaultToolRegistry(providerList, accessPolicy);
    }

    /** 所有 Tool Transport 共用的应用调用管线。 */
    @Bean
    @ConditionalOnMissingBean(ToolInvocationPipeline.class)
    public ToolInvocationPipeline toolInvocationPipeline(
            ToolRegistry registry, ObjectProvider<ToolCallInterceptor> interceptors) {
        List<ToolCallInterceptor> interceptorList = new ArrayList<ToolCallInterceptor>();
        interceptors.orderedStream().forEach(interceptorList::add);
        return new ToolInvocationPipeline(registry, interceptorList);
    }

    /** 所有模型 Transport 共用的应用调用管线。 */
    @Bean
    @ConditionalOnMissingBean(ModelInvocationPipeline.class)
    public ModelInvocationPipeline modelInvocationPipeline(
            ModelProvider provider, ObjectProvider<ModelCallInterceptor> interceptors) {
        List<ModelCallInterceptor> interceptorList = new ArrayList<ModelCallInterceptor>();
        interceptors.orderedStream().forEach(interceptorList::add);
        return new ModelInvocationPipeline(provider, interceptorList);
    }

    /**
     * 注册供宿主 Java Service 直接调用的同 JVM 模型门面。
     *
     * <p>默认实现只复用模型调用管线并在单次调用内聚合结果，不读取 Web 登录态，也不接触会话、审计或数据库。宿主若需要增加自己的
     * 事务、配额或横切策略，应提供同类型 Bean 完整替换该默认门面。
    */
    @Bean
    @ConditionalOnMissingBean(ModelGateway.class)
    public ModelGateway patchbridgeAgentModelGateway(ModelInvocationPipeline invocationPipeline) {
        return new DefaultModelGateway(invocationPipeline);
    }

    /**
     * 使用当前 ModelGateway 与当前 Provider 状态投影器生成上下文检查点。
     * 自定义 Provider 必须同时提供 ModelStateProjector，缺失时启动明确失败。
     */
    @Bean
    @ConditionalOnMissingBean(ContextCompactionProvider.class)
    public ContextCompactionProvider contextCompactionProvider(
            ModelGateway modelGateway, ModelStateProjector stateProjector) {
        return new DefaultContextCompactionProvider(modelGateway, stateProjector);
    }

    // ---------- Web 端点 ----------

    /** 宿主应用不会扫描 Starter 的包，统一错误模型必须由自动装配显式注册。 */
    @Bean
    @ConditionalOnMissingBean(io.patchbridge.agent.starter.web.PatchBridgeAgentExceptionHandler.class)
    public io.patchbridge.agent.starter.web.PatchBridgeAgentExceptionHandler
            patchbridgeAgentExceptionHandler() {
        return new io.patchbridge.agent.starter.web.PatchBridgeAgentExceptionHandler();
    }

    /**
     * 注册协议异常解析器：405 与映射阶段的 406/415 发生在 Handler 选中之前，
     * 只能由独立解析器在 base-path 内回写统一错误信封；命名空间外零副作用交回宿主链。
     * 仅 Servlet Web 环境装配，非 Web 宿主不存在该异常路径。
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnMissingBean(
            io.patchbridge.agent.starter.web.PatchBridgeAgentProtocolExceptionResolver.class)
    public io.patchbridge.agent.starter.web.PatchBridgeAgentProtocolExceptionResolver
            patchbridgeAgentProtocolExceptionResolver(
                    PatchBridgeAgentProperties properties, ObjectMapper objectMapper) {
        return new io.patchbridge.agent.starter.web.PatchBridgeAgentProtocolExceptionResolver(
                properties.getBasePath(), objectMapper);
    }

    /**
     * 注册启动期所有权校验器：全部 RequestMapping 注册完成后验证 base-path 内
     * 只存在 Starter 公共 Controller，使协议解析器按路径归属请求的前提在运行期恒成立。
     * 宿主未装配 Spring MVC 映射基础设施时端点本就无法映射，校验无对象，跳过装配。
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(RequestMappingHandlerMapping.class)
    @ConditionalOnBean(RequestMappingHandlerMapping.class)
    @ConditionalOnMissingBean(
            io.patchbridge.agent.starter.web.PatchBridgeAgentMappingOwnershipValidator.class)
    public io.patchbridge.agent.starter.web.PatchBridgeAgentMappingOwnershipValidator
            patchbridgeAgentMappingOwnershipValidator(
                    PatchBridgeAgentProperties properties,
                    RequestMappingHandlerMapping handlerMapping) {
        return new io.patchbridge.agent.starter.web.PatchBridgeAgentMappingOwnershipValidator(
                properties.getBasePath(), handlerMapping);
    }

    @Bean
    @ConditionalOnMissingBean(io.patchbridge.agent.starter.web.AdminConsoleController.class)
    @ConditionalOnProperty(
            prefix = "patchbridge-agent.admin",
            name = "enabled",
            havingValue = "true")
    public io.patchbridge.agent.starter.web.AdminConsoleController adminConsoleController(
            PatchBridgeAgentProperties properties) {
        return new io.patchbridge.agent.starter.web.AdminConsoleController(properties.getBasePath());
    }

    @Bean
    @ConditionalOnMissingBean(ToolGatewayController.class)
    public ToolGatewayController toolGatewayController(
            ToolRegistry toolRegistry,
            ToolInvocationPipeline invocationPipeline,
            ObjectProvider<io.patchbridge.agent.core.auth.CurrentUserProvider> userProvider,
            AuditRecorder audit) {
        return new ToolGatewayController(
                toolRegistry, invocationPipeline, requiredUserProvider(userProvider), audit);
    }

    @Bean
    @ConditionalOnMissingBean(ModelStreamController.class)
    public ModelStreamController modelStreamController(
            ModelInvocationPipeline invocationPipeline,
            ObjectProvider<io.patchbridge.agent.core.auth.CurrentUserProvider> userProvider,
            AuditRecorder audit,
            PatchBridgeAgentProperties properties,
            ObjectMapper objectMapper) {
        return new ModelStreamController(
                invocationPipeline,
                requiredUserProvider(userProvider),
                audit,
                properties.getModel().getModel(),
                objectMapper);
    }

    /** 注册模型配置与自动/手动上下文压缩端点。 */
    @Bean
    @ConditionalOnMissingBean(ContextCompactionController.class)
    public ContextCompactionController contextCompactionController(
            ContextCompactionSettings settings,
            ContextCompactionProvider provider,
            ObjectProvider<io.patchbridge.agent.core.auth.CurrentUserProvider> userProvider,
            AuditRecorder audit,
            PatchBridgeAgentProperties properties) {
        return new ContextCompactionController(
                settings,
                provider,
                requiredUserProvider(userProvider),
                audit,
                properties.getModel().getModel());
    }

    @Bean
    @ConditionalOnMissingBean(ConversationController.class)
    public ConversationController conversationController(
            ConversationRepository repository,
            ObjectProvider<io.patchbridge.agent.core.auth.CurrentUserProvider> userProvider,
            ConversationOwnerResolver ownerResolver,
            PatchBridgeAgentProperties properties) {
        return new ConversationController(
                repository, requiredUserProvider(userProvider), ownerResolver, properties);
    }

    @Bean
    @ConditionalOnMissingBean(AdminApiController.class)
    @ConditionalOnBean(AuditQueryRepository.class)
    @ConditionalOnProperty(
            prefix = "patchbridge-agent.admin",
            name = "enabled",
            havingValue = "true")
    public AdminApiController adminApiController(AuditQueryRepository queryRepository) {
        return new AdminApiController(queryRepository);
    }

    /** Admin 与 MCP 同时显式开启时才发布高权限 MCP 配置端点。 */
    @Bean
    @ConditionalOnMissingBean(McpAdminController.class)
    @ConditionalOnBean(McpToolRegistry.class)
    @ConditionalOnExpression("${patchbridge-agent.admin.enabled:false}"
            + " and ${patchbridge-agent.mcp.enabled:true}")
    public McpAdminController mcpAdminController(
            McpToolRegistry registry, McpConfigurationManager manager) {
        return new McpAdminController(registry, manager);
    }

    /** Admin 开启后统一装配强制授权拦截器；缺少宿主策略时启动立即失败。 */
    @Bean
    @ConditionalOnMissingBean(AdminAuthorizationInterceptor.class)
    @ConditionalOnProperty(
            prefix = "patchbridge-agent.admin",
            name = "enabled",
            havingValue = "true")
    public AdminAuthorizationInterceptor adminAuthorizationInterceptor(
            ObjectProvider<io.patchbridge.agent.core.auth.CurrentUserProvider> userProvider,
            ObjectProvider<AdminAccessPolicy> accessPolicy,
            PatchBridgeAgentProperties properties) {
        return new AdminAuthorizationInterceptor(
                requiredUserProvider(userProvider),
                requiredAdminAccessPolicy(accessPolicy),
                properties.getBasePath());
    }

    /** 静态资源：浏览器 Agent Bundle 与 Admin 页面（构建产物随 Starter 打包）。 */
    @Bean
    @ConditionalOnMissingBean(name = "patchbridgeAgentResourceConfigurer")
    public WebMvcConfigurer patchbridgeAgentResourceConfigurer(
            PatchBridgeAgentProperties properties,
            ObjectProvider<AdminAuthorizationInterceptor> adminInterceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addResourceHandlers(ResourceHandlerRegistry registry) {
                registry.addResourceHandler(properties.getBasePath() + "/assets/**")
                        .addResourceLocations("classpath:/META-INF/patchbridge-agent/");
                if (properties.getAdmin().isEnabled()) {
                    registry.addResourceHandler("/ai-admin/**")
                            .addResourceLocations("classpath:/META-INF/patchbridge-agent-admin/");
                }
            }

            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                AdminAuthorizationInterceptor interceptor = adminInterceptor.getIfAvailable();
                if (interceptor != null) {
                    registry.addInterceptor(interceptor)
                            .addPathPatterns(
                                    properties.getBasePath() + "/admin/**",
                                    "/ai-admin",
                                    "/ai-admin/**");
                }
            }
        };
    }

    /** CurrentUserProvider 是硬依赖：装配期缺失直接给出可操作的错误信息。 */
    private static io.patchbridge.agent.core.auth.CurrentUserProvider requiredUserProvider(
            ObjectProvider<io.patchbridge.agent.core.auth.CurrentUserProvider> provider) {
        io.patchbridge.agent.core.auth.CurrentUserProvider resolved = provider.getIfAvailable();
        if (resolved == null) {
            throw new IllegalStateException(
                    "缺少 CurrentUserProvider：类路径未检测到 Spring Security，"
                            + "请引入 spring-security 或自行实现 CurrentUserProvider Bean");
        }
        return resolved;
    }

    /** AdminAccessPolicy 仅在 Admin 显式开启时是硬依赖，框架不提供放行默认值。 */
    private static AdminAccessPolicy requiredAdminAccessPolicy(
            ObjectProvider<AdminAccessPolicy> provider) {
        AdminAccessPolicy resolved = provider.getIfAvailable();
        if (resolved == null) {
            throw new IllegalStateException(
                    "已开启 patchbridge-agent.admin.enabled，但缺少 AdminAccessPolicy Bean；"
                            + "请把管理能力映射到宿主现有权限体系");
        }
        return resolved;
    }
}
