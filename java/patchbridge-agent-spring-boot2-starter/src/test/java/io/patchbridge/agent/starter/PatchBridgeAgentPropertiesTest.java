package io.patchbridge.agent.starter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证会被多个 Web 边界共享的配置不变量：错误配置必须在绑定期明确失败。 */
class PatchBridgeAgentPropertiesTest {

    /** 只注册配置绑定 Bean，隔离验证 Binder 的未知字段失败语义。 */
    private final ApplicationContextRunner bindingContext = new ApplicationContextRunner()
            .withUserConfiguration(BindingOnlyConfiguration.class);

    /** 多级绝对路径可用于宿主现有网关前缀。 */
    @Test
    void acceptsNormalizedAbsoluteBasePath() {
        PatchBridgeAgentProperties properties = new PatchBridgeAgentProperties();

        properties.setBasePath("/platform/ai");

        assertEquals("/platform/ai", properties.getBasePath());
    }

    /** 根路径明确表示 Starter 独占全部 MVC 路径空间。 */
    @Test
    void acceptsRootBasePath() {
        PatchBridgeAgentProperties properties = new PatchBridgeAgentProperties();

        properties.setBasePath("/");

        assertEquals("/", properties.getBasePath());
    }

    /** 相对路径与非根尾斜杠会产生不一致映射，必须在绑定期失败。 */
    @Test
    void rejectsAmbiguousBasePaths() {
        PatchBridgeAgentProperties properties = new PatchBridgeAgentProperties();

        assertThrows(IllegalArgumentException.class, () -> properties.setBasePath("ai"));
        assertThrows(IllegalArgumentException.class, () -> properties.setBasePath("/ai/"));
    }

    /** 模型超时禁止负数；0 显式保留 OkHttp 的无超时语义。 */
    @Test
    void modelTimeoutsRejectNegativeButAllowZero() {
        PatchBridgeAgentProperties.Model model = new PatchBridgeAgentProperties.Model();

        assertThrows(IllegalArgumentException.class, () -> model.setConnectTimeoutMs(-1));
        assertThrows(IllegalArgumentException.class, () -> model.setReadTimeoutMs(-1));
        assertDoesNotThrow(() -> {
            model.setConnectTimeoutMs(0);
            model.setReadTimeoutMs(0);
        });
    }

    /** 列表上限为 0 或负数会让列表能力不可用，必须在绑定期失败。 */
    @Test
    void conversationsListLimitMustBePositive() {
        PatchBridgeAgentProperties.Conversations conversations =
                new PatchBridgeAgentProperties.Conversations();

        assertThrows(IllegalArgumentException.class, () -> conversations.setListLimit(0));
        assertThrows(IllegalArgumentException.class, () -> conversations.setListLimit(-1));
    }

    /** 拼写错误的 payload-mode 不允许静默当作 metadata-only 处理。 */
    @Test
    void auditPayloadModeOnlyAcceptsKnownValues() {
        PatchBridgeAgentProperties.Audit audit = new PatchBridgeAgentProperties.Audit();

        assertDoesNotThrow(() -> audit.setPayloadMode("full"));
        assertDoesNotThrow(() -> audit.setPayloadMode("metadata-only"));
        assertDoesNotThrow(() -> audit.setPayloadMode("none"));
        assertThrows(IllegalArgumentException.class, () -> audit.setPayloadMode("meta"));
        assertThrows(IllegalArgumentException.class, () -> audit.setPayloadMode(null));
        assertThrows(IllegalArgumentException.class,
                () -> audit.setSummaryMaxLength(0));
    }

    /** namespace 是稳定 Tool 路由标识：点分段、有限字符集、总长不超过 128。 */
    @Test
    void mcpNamespaceShapeIsValidated() {
        PatchBridgeAgentProperties.Mcp mcp = new PatchBridgeAgentProperties.Mcp();

        assertDoesNotThrow(() -> mcp.setNamespace("mcp"));
        assertDoesNotThrow(() -> mcp.setNamespace("ext.mcp.v2"));
        assertThrows(IllegalArgumentException.class, () -> mcp.setNamespace(null));
        assertThrows(IllegalArgumentException.class, () -> mcp.setNamespace(""));
        assertThrows(IllegalArgumentException.class, () -> mcp.setNamespace("mcp."));
        assertThrows(IllegalArgumentException.class, () -> mcp.setNamespace("m cp"));
        assertThrows(IllegalArgumentException.class, () -> mcp.setNamespace("mcp//x"));
        StringBuilder tooLong = new StringBuilder("n");
        for (int i = 0; i < 128; i++) {
            tooLong.append('n');
        }
        assertThrows(IllegalArgumentException.class, () -> mcp.setNamespace(tooLong.toString()));
    }

    /** servers 整体为 null 没有合法语义，绑定期明确失败。 */
    @Test
    void mcpServersMustNotBeNull() {
        PatchBridgeAgentProperties.Mcp mcp = new PatchBridgeAgentProperties.Mcp();

        assertThrows(IllegalArgumentException.class, () -> mcp.setServers(null));
    }

    /** 顶层和动态 MCP Server 内的未知配置键都必须让绑定上下文启动失败。 */
    @Test
    void springBinderRejectsUnknownTopLevelAndNestedFields() {
        bindingContext
                .withPropertyValues("patchbridge-agent.audit.payload-mdoe=full")
                .run(context -> assertNotNull(context.getStartupFailure()));

        bindingContext
                .withPropertyValues(
                        "patchbridge-agent.mcp.servers.inventory.url=https://mcp.example.test/mcp",
                        "patchbridge-agent.mcp.servers.inventory.auth.tokenn=secret")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    /** 最小 Spring 配置只负责启用目标 Properties，不引入 Starter 其他硬依赖。 */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PatchBridgeAgentProperties.class)
    static class BindingOnlyConfiguration {
    }
}
