package io.patchbridge.agent.core.invocation;

import io.patchbridge.agent.core.model.ModelTestTargets;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.error.ToolAccessDeniedException;
import io.patchbridge.agent.core.error.ToolExecutionException;
import io.patchbridge.agent.core.interceptor.ModelCallInterceptor;
import io.patchbridge.agent.core.interceptor.ToolCallInterceptor;
import io.patchbridge.agent.core.model.ModelBlockStopEvent;
import io.patchbridge.agent.core.model.ModelCall;
import io.patchbridge.agent.core.model.ModelProvider;
import io.patchbridge.agent.core.model.ModelRequest;
import io.patchbridge.agent.core.model.ModelStreamEvent;
import io.patchbridge.agent.core.model.ModelStreamListener;
import io.patchbridge.agent.core.tool.ToolAnnotations;
import io.patchbridge.agent.core.tool.ToolCallResult;
import io.patchbridge.agent.core.tool.ToolDefinition;
import io.patchbridge.agent.core.tool.ToolRegistry;
import io.patchbridge.agent.core.tool.ToolSource;
import io.patchbridge.agent.core.user.UserContext;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 验证 Tool / Model 统一调用管线的顺序、上下文透传和失败退出语义。 */
class InvocationPipelineTest {

    /** Tool before 顺序进入、after 逆序退出，且共享同一个可信上下文。 */
    @Test
    void toolInterceptorsWrapRegistryInRegistrationOrder() throws Exception {
        List<String> events = new ArrayList<String>();
        AiRequestContext context = context();
        ToolInvocationPipeline pipeline =
                new ToolInvocationPipeline(
                        new StubToolRegistry(events),
                        Arrays.asList(
                                toolInterceptor("first", events, context),
                                toolInterceptor("second", events, context)));

        ToolCallResult result =
                pipeline.invoke(
                        "local.echo",
                        null,
                        Collections.<String, Object>singletonMap("text", "hello"),
                        context);

        assertEquals("ok", result.getContent().get(0).getText());
        assertEquals(
                Arrays.asList(
                        "first.before",
                        "second.before",
                        "registry",
                        "second.after.success",
                        "first.after.success"),
                events);
    }

    /** Registry 失败仍逆序执行 after，并向后置钩子传递原始失败。 */
    @Test
    void toolInterceptorsReceiveRegistryFailure() {
        List<String> events = new ArrayList<String>();
        AiRequestContext context = context();
        StubToolRegistry registry = new StubToolRegistry(events);
        registry.failure = new ToolExecutionException("boom");
        ToolInvocationPipeline pipeline =
                new ToolInvocationPipeline(
                        registry,
                        Collections.singletonList(toolInterceptor("only", events, context)));

        ToolExecutionException thrown =
                assertThrows(
                        ToolExecutionException.class,
                        () ->
                                pipeline.invoke(
                                        "local.echo",
                                        null,
                                        Collections.<String, Object>emptyMap(),
                                        context));

        assertSame(registry.failure, thrown);
        assertEquals(Arrays.asList("only.before", "registry", "only.after.failure"), events);
    }

    /** 模型拦截器拿到用户上下文，并在 Provider 完成后逆序退出。 */
    @Test
    void modelInterceptorsWrapProviderAndReceiveContext() {
        List<String> events = new ArrayList<String>();
        AiRequestContext context = context();
        ModelProvider provider =
                new ModelProvider() {
                    @Override
                    public ModelCall stream(ModelRequest request, ModelStreamListener listener) {
                        events.add("provider");
                        listener.onEvent(new ModelBlockStopEvent(0));
                        listener.onCompleted();
                        return () -> {};
                    }
                };
        ModelInvocationPipeline pipeline =
                new ModelInvocationPipeline(
                        ModelTestTargets.router(provider),
                        Arrays.asList(
                                modelInterceptor("first", events, context),
                                modelInterceptor("second", events, context)));
        ModelRequest request =
                new ModelRequest(
                        "response-1",
                        ModelTestTargets.REF,
                        Collections.emptyList(),
                        Collections.emptyList(),
                        null,
                        null,
                        null);

        pipeline.stream(request, context, new NoOpModelStreamListener());

        assertEquals(
                Arrays.asList(
                        "first.before",
                        "second.before",
                        "provider",
                        "second.after.success",
                        "first.after.success"),
                events);
    }

    /** 模型异步失败必须传给侦听器，并且仍然逆序退出全部已进入拦截器。 */
    @Test
    void modelInterceptorsReceiveAsynchronousFailure() {
        List<String> events = new ArrayList<String>();
        AiRequestContext context = context();
        IllegalStateException failure = new IllegalStateException("upstream failed");
        ModelProvider provider =
                new ModelProvider() {
                    @Override
                    public ModelCall stream(ModelRequest request, ModelStreamListener listener) {
                        events.add("provider");
                        listener.onError(failure);
                        return () -> {};
                    }
                };
        AtomicReference<Throwable> received = new AtomicReference<Throwable>();
        ModelInvocationPipeline pipeline =
                new ModelInvocationPipeline(
                        ModelTestTargets.router(provider),
                        Arrays.asList(
                                modelInterceptor("first", events, context),
                                modelInterceptor("second", events, context)));

        pipeline.stream(modelRequest(), context, new RecordingModelStreamListener(received));

        assertSame(failure, received.get());
        assertEquals(
                Arrays.asList(
                        "first.before",
                        "second.before",
                        "provider",
                        "second.after.failure",
                        "first.after.failure"),
                events);
    }

    /** 取消是唯一终止信号：after 和 Provider cancel 各执行一次，不伪造下游回调。 */
    @Test
    void modelCancellationTerminatesLifecycleOnce() {
        List<String> events = new ArrayList<String>();
        AiRequestContext context = context();
        AtomicInteger cancelled = new AtomicInteger();
        ModelProvider provider =
                new ModelProvider() {
                    @Override
                    public ModelCall stream(ModelRequest request, ModelStreamListener listener) {
                        events.add("provider");
                        return () -> {
                            cancelled.incrementAndGet();
                            events.add("provider.cancel");
                        };
                    }
                };
        AtomicReference<Throwable> received = new AtomicReference<Throwable>();
        ModelInvocationPipeline pipeline =
                new ModelInvocationPipeline(
                        ModelTestTargets.router(provider),
                        Arrays.asList(
                                modelInterceptor("first", events, context),
                                modelInterceptor("second", events, context)));

        ModelCall call =
                pipeline.stream(
                        modelRequest(), context, new RecordingModelStreamListener(received));
        call.cancel();
        call.cancel();

        assertEquals(1, cancelled.get());
        assertNull(received.get());
        assertEquals(
                Arrays.asList(
                        "first.before",
                        "second.before",
                        "provider",
                        "second.after.failure",
                        "first.after.failure",
                        "provider.cancel"),
                events);
    }

    /** 前置钩子失败时只退出已成功进入的拦截器，Provider 不得启动。 */
    @Test
    void modelBeforeFailureOnlyExitsEnteredInterceptors() {
        List<String> events = new ArrayList<String>();
        AiRequestContext context = context();
        ModelCallInterceptor failing =
                new ModelCallInterceptor() {
                    @Override
                    public void before(ModelRequest request, AiRequestContext requestContext) {
                        events.add("second.before");
                        throw new IllegalStateException("rejected");
                    }

                    @Override
                    public void after(
                            ModelRequest request,
                            AiRequestContext requestContext,
                            boolean success,
                            long durationMs,
                            Throwable failure) {
                        events.add("second.after");
                    }
                };
        ModelProvider provider =
                new ModelProvider() {
                    @Override
                    public ModelCall stream(ModelRequest request, ModelStreamListener listener) {
                        events.add("provider");
                        return () -> {};
                    }
                };
        ModelInvocationPipeline pipeline =
                new ModelInvocationPipeline(
                        ModelTestTargets.router(provider),
                        Arrays.asList(modelInterceptor("first", events, context), failing));

        assertThrows(
                io.patchbridge.agent.core.error.ModelGatewayException.class,
                () -> pipeline.stream(modelRequest(), context, new NoOpModelStreamListener()));

        assertEquals(Arrays.asList("first.before", "second.before", "first.after.failure"), events);
    }

    /** 创建测试用可信请求上下文。 */
    private static AiRequestContext context() {
        UserContext user = UserContext.builder().userId("u-1").tenantId("t-1").build();
        return new AiRequestContext(user, "trace-1", "request-1", "tool-call-1", "c-1");
    }

    /** 创建不含业务数据的最小模型请求。 */
    private static ModelRequest modelRequest() {
        return new ModelRequest(
                "response-1",
                ModelTestTargets.REF,
                Collections.emptyList(),
                Collections.emptyList(),
                null,
                null,
                null);
    }

    /** 创建记录顺序并校验上下文身份的 Tool 拦截器。 */
    private static ToolCallInterceptor toolInterceptor(
            String name, List<String> events, AiRequestContext expectedContext) {
        return new ToolCallInterceptor() {
            @Override
            public void before(
                    ToolDefinition tool, Map<String, Object> arguments, AiRequestContext context) {
                assertSame(expectedContext, context);
                events.add(name + ".before");
            }

            @Override
            public void after(
                    ToolDefinition tool,
                    Map<String, Object> arguments,
                    AiRequestContext context,
                    ToolCallResult result,
                    Throwable failure) {
                assertSame(expectedContext, context);
                events.add(name + (failure == null ? ".after.success" : ".after.failure"));
            }
        };
    }

    /** 创建记录顺序并校验上下文身份的 Model 拦截器。 */
    private static ModelCallInterceptor modelInterceptor(
            String name, List<String> events, AiRequestContext expectedContext) {
        return new ModelCallInterceptor() {
            @Override
            public void before(ModelRequest request, AiRequestContext context) {
                assertSame(expectedContext, context);
                events.add(name + ".before");
            }

            @Override
            public void after(
                    ModelRequest request,
                    AiRequestContext context,
                    boolean success,
                    long durationMs,
                    Throwable failure) {
                assertSame(expectedContext, context);
                events.add(name + (success ? ".after.success" : ".after.failure"));
            }
        };
    }

    /** 最小 ToolRegistry 假实现，用于隔离测试调用管线。 */
    private static final class StubToolRegistry implements ToolRegistry {
        private final List<String> events;
        private final ToolDefinition definition =
                new ToolDefinition(
                        "local.echo",
                        null,
                        "echo",
                        Collections.<String, Object>emptyMap(),
                        new ToolAnnotations(true, false, true, false),
                        ToolSource.LOCAL,
                        Collections.<String>emptyList(),
                        null);
        private ToolExecutionException failure;

        /** 创建记录 Registry 调用事件的假实现。 */
        private StubToolRegistry(List<String> events) {
            this.events = events;
        }

        /** 返回唯一测试工具。 */
        @Override
        public List<ToolDefinition> list(UserContext user) {
            return Collections.singletonList(definition);
        }

        /** 仅识别测试工具全名。 */
        @Override
        public ToolDefinition find(String fullName) {
            return definition.getName().equals(fullName) ? definition : null;
        }

        /** 记录调用并返回结果或抛出测试预设失败。 */
        @Override
        public ToolCallResult call(
                String fullName, String definitionVersion, Map<String, Object> arguments,
                AiRequestContext requestContext)
                throws ToolExecutionException, ToolAccessDeniedException {
            events.add("registry");
            if (failure != null) {
                throw failure;
            }
            return ToolCallResult.ofText("ok");
        }
    }

    /** 不关心输出内容的模型监听器。 */
    private static final class NoOpModelStreamListener implements ModelStreamListener {
        /** 忽略测试事件内容。 */
        @Override
        public void onEvent(ModelStreamEvent event) {}

        /** Provider 已正常完成，无需额外处理。 */
        @Override
        public void onCompleted() {}

        /** 失败会由调用管线抛出，本监听器无需记录。 */
        @Override
        public void onError(Throwable error) {}
    }

    /** 只记录错误终止信号的模型侦听器。 */
    private static final class RecordingModelStreamListener implements ModelStreamListener {
        /** 保存 Provider 传递的原始错误。 */
        private final AtomicReference<Throwable> error;

        /** 创建共享错误容器的记录器。 */
        private RecordingModelStreamListener(AtomicReference<Throwable> error) {
            this.error = error;
        }

        /** 本测试不关心事件内容。 */
        @Override
        public void onEvent(ModelStreamEvent event) {}

        /** 正常完成时不写入错误。 */
        @Override
        public void onCompleted() {}

        /** 保留原始错误引用供断言。 */
        @Override
        public void onError(Throwable failure) {
            error.set(failure);
        }
    }
}
