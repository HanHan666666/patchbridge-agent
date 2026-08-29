package io.patchbridge.agent.starter;

import io.patchbridge.agent.core.audit.AuditSink;
import io.patchbridge.agent.core.auth.CurrentUserProvider;
import io.patchbridge.agent.core.conversation.ConversationRepository;
import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.invocation.DefaultModelGateway;
import io.patchbridge.agent.core.invocation.ModelGateway;
import io.patchbridge.agent.core.invocation.ModelInvocation;
import io.patchbridge.agent.core.model.AgentMessage;
import io.patchbridge.agent.core.model.BlockType;
import io.patchbridge.agent.core.model.MessageRole;
import io.patchbridge.agent.core.model.ModelBlockDeltaEvent;
import io.patchbridge.agent.core.model.ModelBlockStartEvent;
import io.patchbridge.agent.core.model.ModelBlockStopEvent;
import io.patchbridge.agent.core.model.ModelCall;
import io.patchbridge.agent.core.model.ModelMessageStopEvent;
import io.patchbridge.agent.core.model.ModelProvider;
import io.patchbridge.agent.core.model.ModelRequest;
import io.patchbridge.agent.core.model.ModelStopReason;
import io.patchbridge.agent.core.model.ModelStreamListener;
import io.patchbridge.agent.core.model.ModelStateProjector;
import io.patchbridge.agent.core.model.TextBlock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Java 单次模型门面的 Starter 装配契约测试。
 *
 * <p>该测试使用完整 MVC 自动装配，以同时证明默认 Bean 可在无请求线程中使用，且新增的同 JVM API 没有被意外发布成 HTTP 端点。会话与审计均使用
 * 可观察的宿主 Bean，确保默认门面不会借现有 Starter 基础设施产生隐式持久化。
 */
class ModelGatewayAutoConfigurationIntegrationTest {

    /**
     * 最小宿主上下文：模型 Provider、身份、会话和审计都由宿主显式提供，MCP 关闭以排除无关装配。
     */
    private final WebApplicationContextRunner runner =
            new WebApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(
                                    ConfigurationPropertiesAutoConfiguration.class,
                                    WebMvcAutoConfiguration.class,
                                    JacksonAutoConfiguration.class,
                                    PatchBridgeAgentAutoConfiguration.class))
                    .withBean(ModelProvider.class, SuccessfulModelProvider::new)
                    .withBean(CurrentUserProvider.class, () -> mock(CurrentUserProvider.class))
                    .withBean(
                            ConversationRepository.class,
                            () -> mock(ConversationRepository.class))
                    .withBean(AuditSink.class, () -> mock(AuditSink.class))
                    .withBean(
                            ModelStateProjector.class,
                            () -> (state, retainedMessages) -> state)
                    .withPropertyValues(
                            "patchbridge-agent.mcp.enabled=false",
                            "patchbridge-agent.model.base-url=http://localhost:0/v1",
                            "patchbridge-agent.model.model=test-model",
                            "patchbridge-agent.model.context-window-tokens=128000");

    /** 引入 Starter 后应只有一个公共 ModelGateway，且默认实现来自不依赖 Spring 的 Core。 */
    @Test
    void defaultModelGatewayIsUniquelyAutoConfigured() {
        runner.run(
                context -> {
                    Map<String, ModelGateway> gateways = context.getBeansOfType(ModelGateway.class);
                    assertEquals(1, gateways.size());
                    assertTrue(
                            gateways.get("patchbridgeAgentModelGateway")
                                    instanceof DefaultModelGateway);
                });
    }

    /** 宿主已有同名异类型 Bean 时不得发生名称冲突，框架仍应按自己的前缀名称装配默认门面。 */
    @Test
    void hostBeanWithGenericModelGatewayNameDoesNotConflict() {
        runner.withBean("modelGateway", String.class, () -> "host-model-gateway")
                .run(
                        context -> {
                            assertEquals("host-model-gateway", context.getBean("modelGateway"));
                            Map<String, ModelGateway> gateways =
                                    context.getBeansOfType(ModelGateway.class);
                            assertEquals(1, gateways.size());
                            assertTrue(
                                    gateways.get("patchbridgeAgentModelGateway")
                                            instanceof DefaultModelGateway);
                        });
    }

    /** 宿主提供同类型 Bean 后默认实现必须完全让位，避免存在两个候选门面。 */
    @Test
    void hostModelGatewayMakesDefaultImplementationBackOff() {
        ModelGateway hostGateway = hostModelGateway();
        runner.withBean("hostModelGateway", ModelGateway.class, () -> hostGateway)
                .run(
                        context -> {
                            Map<String, ModelGateway> gateways =
                                    context.getBeansOfType(ModelGateway.class);
                            assertEquals(Collections.singleton("hostModelGateway"), gateways.keySet());
                            assertSame(hostGateway, gateways.get("hostModelGateway"));
                            assertFalse(context.containsBean("patchbridgeAgentModelGateway"));
                        });
    }

    /**
     * 默认门面可在没有 ServletRequest 和 SecurityContext 的后台线程中调用，并且整个单次调用不读写 Conversation 或 Audit。
     */
    @Test
    void backgroundInvocationDoesNotDependOnWebContextOrPersistence() {
        runner.run(
                context -> {
                    ConversationRepository conversations =
                            context.getBean(ConversationRepository.class);
                    AuditSink audits = context.getBean(AuditSink.class);
                    CurrentUserProvider currentUser = context.getBean(CurrentUserProvider.class);

                    String text =
                            CompletableFuture.supplyAsync(
                                            () -> {
                                                SecurityContextHolder.clearContext();
                                                return context.getBean(ModelGateway.class)
                                                        .invoke(textRequest())
                                                        .await(Duration.ofSeconds(1))
                                                        .getText();
                                            })
                                    .get(2, TimeUnit.SECONDS);

                    assertEquals("ok", text);
                    verifyNoInteractions(currentUser, conversations, audits);
                });
    }

    /** ModelGateway 是同 JVM Port，Spring MVC 的 Handler 集合中不得出现其实现类。 */
    @Test
    void modelGatewayDoesNotPublishHttpEndpoint() {
        runner.run(
                context -> {
                    RequestMappingHandlerMapping mappings =
                            context.getBean(RequestMappingHandlerMapping.class);
                    for (HandlerMethod method : mappings.getHandlerMethods().values()) {
                        assertFalse(
                                ModelGateway.class.isAssignableFrom(method.getBeanType()),
                                "ModelGateway 不得注册为 HTTP Handler: " + method);
                    }
                });
    }

    /** 构造不执行任何行为的宿主门面，用于只验证 Spring 条件让位。 */
    private static ModelGateway hostModelGateway() {
        return new ModelGateway() {
            /** 装配测试不执行模型调用。 */
            @Override
            public ModelInvocation invoke(ModelRequest request) {
                throw new UnsupportedOperationException("装配测试不会调用宿主门面");
            }

            /** 装配测试不执行带上下文的模型调用。 */
            @Override
            public ModelInvocation invoke(ModelRequest request, AiRequestContext context) {
                throw new UnsupportedOperationException("装配测试不会调用宿主门面");
            }
        };
    }

    /** 构造不含 Tool 的最小单次请求，避免测试绕过 Java 门面的边界校验。 */
    private static ModelRequest textRequest() {
        AgentMessage userMessage =
                new AgentMessage(
                        "user-1",
                        MessageRole.USER,
                        Collections.singletonList(new TextBlock("hello")));
        return new ModelRequest(
                "assistant-1",
                "test-model",
                Collections.singletonList(userMessage),
                Collections.emptyList(),
                null,
                null,
                16);
    }

    /**
     * 立即回放一个合法文本响应的测试 Provider；它只用于验证 Starter 连线，不承担 Core 聚合器的协议测试。
     */
    private static final class SuccessfulModelProvider implements ModelProvider {

        /** 回放完整结构化事件，返回无需释放网络资源的取消句柄。 */
        @Override
        public ModelCall stream(ModelRequest request, ModelStreamListener listener) {
            listener.onEvent(
                    new ModelBlockStartEvent(
                            0, ModelBlockStartEvent.Block.content(BlockType.TEXT)));
            listener.onEvent(
                    new ModelBlockDeltaEvent(
                            0, ModelBlockDeltaEvent.Delta.text(BlockType.TEXT, "ok")));
            listener.onEvent(new ModelBlockStopEvent(0));
            listener.onEvent(new ModelMessageStopEvent(ModelStopReason.END_TURN, null, null));
            listener.onCompleted();
            return () -> {};
        }
    }
}
