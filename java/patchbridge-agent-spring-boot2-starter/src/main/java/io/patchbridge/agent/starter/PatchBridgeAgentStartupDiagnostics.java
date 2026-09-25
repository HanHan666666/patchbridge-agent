package io.patchbridge.agent.starter;

import io.patchbridge.agent.core.auth.AuthenticatedToolAccessPolicy;
import io.patchbridge.agent.mcp.PropertiesMcpConfigurationStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.util.ClassUtils;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starter 默认实现的统一启动诊断入口。
 *
 * <p>该组件只报告依赖部署意图才能判断的可用性风险，不替代确定配置的构造期校验。
 * 它在自身应用上下文完成刷新后检查实际装配的默认 Bean，并使用固定编号和固定文本输出
 * 一次 WARN；日志不拼接配置值，避免 API Key、MCP 凭据或 URL 查询参数进入日志。
 */
public final class PatchBridgeAgentStartupDiagnostics
        implements ApplicationListener<ContextRefreshedEvent> {

    /** 统一承载 Starter 启动诊断，便于运维按组件过滤。 */
    private static final Logger LOGGER =
            LoggerFactory.getLogger(PatchBridgeAgentStartupDiagnostics.class);

    /** 默认 properties MCP 配置源为空的稳定诊断。 */
    static final String EMPTY_MCP_SERVERS_WARNING =
            "code=PBA-CFG-002; "
                    + "配置键=patchbridge-agent.mcp.servers,patchbridge-agent.mcp.enabled; "
                    + "影响=默认 properties 配置源为空，不会发现任何 Global MCP Tool; "
                    + "动作=配置 patchbridge-agent.mcp.servers "
                    + "或显式设置 patchbridge-agent.mcp.enabled=false";

    /** 默认 Tool 权限策略没有生产 RBAC/ACL 语义的稳定诊断。 */
    static final String DEFAULT_TOOL_POLICY_WARNING =
            "code=PBA-CFG-003; 配置键=ToolAccessPolicy; "
                    + "影响=默认 AuthenticatedToolAccessPolicy 只检查登录状态，"
                    + "不解释 @AiTool.permissions; "
                    + "动作=注册宿主 ToolAccessPolicy Bean 映射生产 RBAC/ACL";

    /** 默认 properties MCP Store 在自动装配中的稳定名称。 */
    private static final String DEFAULT_MCP_STORE_BEAN = "propertiesMcpConfigurationStore";

    /** 默认 ToolAccessPolicy 在自动装配中的稳定名称。 */
    private static final String DEFAULT_TOOL_POLICY_BEAN = "toolAccessPolicy";

    /** 只处理该组件所属上下文，避免父子上下文事件传播造成重复日志。 */
    private final ApplicationContext applicationContext;

    /** 只读取是否缺失的配置状态，任何配置值都不会进入日志模板。 */
    private final PatchBridgeAgentProperties properties;

    /** 防止同一上下文重复发布刷新事件时重复输出诊断。 */
    private final AtomicBoolean emitted = new AtomicBoolean(false);

    /**
     * 创建与当前 Starter 上下文绑定的诊断器。
     *
     * @param applicationContext 当前自动装配所在的应用上下文
     * @param properties 已完成严格绑定的 Starter 配置
     */
    public PatchBridgeAgentStartupDiagnostics(
            ApplicationContext applicationContext, PatchBridgeAgentProperties properties) {
        this.applicationContext = applicationContext;
        this.properties = properties;
    }

    /**
     * 在当前上下文完成刷新后统一输出诊断；子上下文事件和重复事件不会再次输出。
     *
     * @param event Spring 上下文刷新完成事件
     */
    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (event.getApplicationContext() != applicationContext
                || !emitted.compareAndSet(false, true)) {
            return;
        }

        if (properties.getMcp().isEnabled()
                && usesStarterDefault(
                        DEFAULT_MCP_STORE_BEAN, PropertiesMcpConfigurationStore.class)
                && properties.getMcp().getServers().isEmpty()) {
            LOGGER.warn(EMPTY_MCP_SERVERS_WARNING);
        }
        if (usesStarterDefault(
                DEFAULT_TOOL_POLICY_BEAN, AuthenticatedToolAccessPolicy.class)) {
            LOGGER.warn(DEFAULT_TOOL_POLICY_WARNING);
        }
    }

    /**
     * 按自动装配的稳定 Bean 名和用户类共同识别 Starter 默认实现。
     * 宿主提供同接口 Bean 后默认 Bean 不会创建，因此不会因配置残留产生误报。
     */
    private boolean usesStarterDefault(String beanName, Class<?> expectedType) {
        if (!applicationContext.containsBean(beanName)) {
            return false;
        }
        Object bean = applicationContext.getBean(beanName);
        return expectedType.equals(ClassUtils.getUserClass(bean));
    }

}
