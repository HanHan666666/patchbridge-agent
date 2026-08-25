package io.patchbridge.agent.starter;

import io.patchbridge.agent.core.auth.CurrentUserProvider;
import io.patchbridge.agent.core.auth.ToolAccessPolicy;
import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.model.ModelCall;
import io.patchbridge.agent.core.model.ModelProvider;
import io.patchbridge.agent.core.model.ModelRequest;
import io.patchbridge.agent.core.model.ModelStreamListener;
import io.patchbridge.agent.core.tool.ToolDefinition;
import io.patchbridge.agent.core.user.UserContext;
import io.patchbridge.agent.mcp.McpConfigurationStore;
import io.patchbridge.agent.mcp.McpServerConfig;
import io.patchbridge.agent.mcp.McpServerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.support.StaticApplicationContext;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证启动诊断的正反触发条件、让位语义与输出约束：
 * 只报告实际装配的默认实现，每次刷新只输出一次，任何配置值不得进入日志。
 */
@ExtendWith(OutputCaptureExtension.class)
class PatchBridgeAgentStartupDiagnosticsTest {

    /** 可启动完整 Starter 装配的最小上下文；默认实现齐全时应输出全部三条 WARN。 */
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
                    org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration.class,
                    JacksonAutoConfiguration.class,
                    PatchBridgeAgentAutoConfiguration.class))
            .withUserConfiguration(MinimalHostConfig.class)
            .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:mem:diagnostics;DB_CLOSE_DELAY=-1",
                    "spring.datasource.driver-class-name=org.h2.Driver",
                    "patchbridge-agent.model.base-url=http://localhost:0/v1",
                    "patchbridge-agent.model.model=fake-model");

    /** 默认实现全部装配且缺少部署条件配置时，三条编号 WARN 都必须出现。 */
    @Test
    void defaultBeansEmitAllThreeWarnings(CapturedOutput output) {
        runner.run(context -> {
            assertTrue(context.isRunning());
            assertTrue(output.toString().contains("PBA-CFG-001"), "缺少模型凭据警告");
            assertTrue(output.toString().contains("PBA-CFG-002"), "缺少 MCP Server 警告");
            assertTrue(output.toString().contains("PBA-CFG-003"), "默认权限策略警告");
        });
    }

    /** 配置 API Key 后不再告警，且配置值本身绝不进入日志。 */
    @Test
    void configuredApiKeySuppressesWarningAndNeverLeaksValue(CapturedOutput output) {
        runner.withPropertyValues(
                        "patchbridge-agent.model.api-key=sk-diagnostic-sentinel-9f2b") // gitleaks:allow 固定测试哨兵，仅用于断言配置值不进入日志，非真实凭据
                .run(context -> {
                    assertFalse(output.toString().contains("PBA-CFG-001"));
                    assertFalse(output.toString().contains("sk-diagnostic-sentinel-9f2b"));
                });
    }

    /** 宿主提供自定义 ModelProvider 时默认实现让位，不再产生模型凭据误报。 */
    @Test
    void customModelProviderSuppressesModelWarning(CapturedOutput output) {
        runner.withBean("hostModelProvider", ModelProvider.class, StubModelProvider::new)
                .run(context -> assertFalse(output.toString().contains("PBA-CFG-001")));
    }

    /** 显式关闭 MCP 不会再有“空 Server 列表”告警。 */
    @Test
    void disabledMcpSuppressesEmptyServersWarning(CapturedOutput output) {
        runner.withPropertyValues("patchbridge-agent.mcp.enabled=false")
                .run(context -> assertFalse(output.toString().contains("PBA-CFG-002")));
    }

    /** 宿主提供自定义 MCP 配置源时默认 properties 源让位，不产生空列表误报。 */
    @Test
    void customMcpStoreSuppressesEmptyServersWarning(CapturedOutput output) {
        runner.withBean("hostMcpStore", McpConfigurationStore.class, EmptyMcpStore::new)
                .run(context -> assertFalse(output.toString().contains("PBA-CFG-002")));
    }

    /** 宿主提供 RBAC/ACL 策略时默认策略让位，不再提示默认权限语义。 */
    @Test
    void customToolPolicySuppressesDefaultPolicyWarning(CapturedOutput output) {
        runner.withBean("hostToolPolicy", ToolAccessPolicy.class, PermitAllToolPolicy::new)
                .run(context -> assertFalse(output.toString().contains("PBA-CFG-003")));
    }

    /** 同一上下文重复刷新与外来上下文事件都不得重复输出诊断。 */
    @Test
    void repeatedAndForeignEventsEmitEachWarningOnce(CapturedOutput output) {
        StaticApplicationContext context = new StaticApplicationContext();
        context.refresh();
        // 按自动装配的稳定名称注册默认实现，模拟完整默认 Bean 图
        context.getBeanFactory().registerSingleton(
                "openAiCompatibleModelProvider",
                new io.patchbridge.agent.starter.model.OpenAiCompatibleModelProvider(
                        modelConfig("http://localhost:0/v1", "fake-model")));
        context.getBeanFactory().registerSingleton(
                "propertiesMcpConfigurationStore",
                new io.patchbridge.agent.mcp.PropertiesMcpConfigurationStore(
                        Collections.<String, McpServerConfig>emptyMap()));
        context.getBeanFactory().registerSingleton(
                "toolAccessPolicy",
                new io.patchbridge.agent.core.auth.AuthenticatedToolAccessPolicy());

        PatchBridgeAgentStartupDiagnostics diagnostics =
                new PatchBridgeAgentStartupDiagnostics(context, new PatchBridgeAgentProperties());
        diagnostics.onApplicationEvent(new ContextRefreshedEvent(context));
        diagnostics.onApplicationEvent(new ContextRefreshedEvent(context));

        StaticApplicationContext foreign = new StaticApplicationContext();
        foreign.refresh();
        diagnostics.onApplicationEvent(new ContextRefreshedEvent(foreign));

        assertEquals(1, countOccurrences("PBA-CFG-001", output.toString()));
        assertEquals(1, countOccurrences("PBA-CFG-002", output.toString()));
        assertEquals(1, countOccurrences("PBA-CFG-003", output.toString()));

        context.close();
        foreign.close();
    }

    /** 统计诊断码出现次数，用于验证只输出一次的约束。 */
    private static int countOccurrences(String code, String text) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(code, index)) >= 0) {
            count += 1;
            index += code.length();
        }
        return count;
    }

    /** 构造已通过构造期校验的模型配置。 */
    private static PatchBridgeAgentProperties.Model modelConfig(String baseUrl, String model) {
        PatchBridgeAgentProperties.Model config = new PatchBridgeAgentProperties.Model();
        config.setBaseUrl(baseUrl);
        config.setModel(model);
        return config;
    }

    /** 最小宿主配置：提供 Starter 硬依赖的当前用户适配器。 */
    @Configuration
    static class MinimalHostConfig {

        /** 返回固定已登录用户的宿主身份适配器。 */
        @Bean
        public CurrentUserProvider fixedUserProvider() {
            return new CurrentUserProvider() {
                @Override
                public UserContext currentUser(AiRequestContext request) {
                    return UserContext.builder().userId("diagnostic-user").build();
                }
            };
        }
    }

    /** 不发起真实网络调用的宿主自定义模型实现。 */
    static class StubModelProvider implements ModelProvider {

        /** 诊断测试不会触发模型调用，实现只需满足装配。 */
        @Override
        public ModelCall stream(ModelRequest request, ModelStreamListener listener) {
            throw new UnsupportedOperationException("诊断测试不执行模型调用");
        }
    }

    /** 恒定放行的宿主自定义策略；仅验证让位语义，不代表生产推荐。 */
    static class PermitAllToolPolicy implements ToolAccessPolicy {

        /** 测试桩固定放行发现。 */
        @Override
        public boolean canDiscover(UserContext user, ToolDefinition tool) {
            return true;
        }

        /** 测试桩固定放行调用。 */
        @Override
        public boolean canInvoke(UserContext user, ToolDefinition tool) {
            return true;
        }
    }

    /** 恒定返回空列表的宿主自定义 MCP 配置源。 */
    static class EmptyMcpStore implements McpConfigurationStore {

        /** 测试桩固定标识。 */
        @Override
        public String source() {
            return "test-empty";
        }

        /** 测试桩固定只读。 */
        @Override
        public boolean mutable() {
            return false;
        }

        /** 空配置源版本恒定。 */
        @Override
        public String version() {
            return "empty";
        }

        /** 空配置源。 */
        @Override
        public List<McpServerRecord> findAll() {
            return Collections.emptyList();
        }

        /** 空配置源查无结果。 */
        @Override
        public McpServerRecord find(String name) {
            return null;
        }

        /** 空配置源不支持写入。 */
        @Override
        public McpServerRecord create(String name, McpServerConfig config) {
            throw new UnsupportedOperationException("测试桩不支持写入");
        }

        /** 空配置源不支持写入。 */
        @Override
        public McpServerRecord update(String name, McpServerConfig config, long expectedRevision) {
            throw new UnsupportedOperationException("测试桩不支持写入");
        }

        /** 空配置源不支持删除。 */
        @Override
        public void delete(String name, long expectedRevision) {
            throw new UnsupportedOperationException("测试桩不支持删除");
        }
    }
}
