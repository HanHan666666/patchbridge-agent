package io.patchbridge.agent.demo.model;

import io.patchbridge.agent.core.auth.CurrentUserProvider;
import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.invocation.ModelGateway;
import io.patchbridge.agent.core.invocation.ModelInvocation;
import io.patchbridge.agent.core.model.AgentMessage;
import io.patchbridge.agent.core.model.ImageBlock;
import io.patchbridge.agent.core.model.ImageSource;
import io.patchbridge.agent.core.model.MessageRole;
import io.patchbridge.agent.core.model.ModelRequest;
import io.patchbridge.agent.core.model.ModelResponse;
import io.patchbridge.agent.core.model.ModelStopReason;
import io.patchbridge.agent.core.model.TextBlock;
import io.patchbridge.agent.core.user.UserContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 Demo 后端单次模型调用只组装 Core 请求并选择同步或异步消费方式。
 *
 * <p>测试替换的是同 JVM {@link ModelGateway} Port，不提供 Mock AI HTTP 服务；真实 Demo
 * 启动时仍由 Starter 注入当前配置的真实 Provider。
 */
class DemoModelInvocationTest {

    /** 文本 Demo 应使用结构化消息并把调用句柄原样交给 Controller。 */
    @Test
    void 文本示例构造单次Core请求() {
        ModelGateway gateway = mock(ModelGateway.class);
        ModelInvocation invocation = mock(ModelInvocation.class);
        AiRequestContext context = requestContext(user());
        when(gateway.invoke(any(ModelRequest.class), same(context))).thenReturn(invocation);
        DemoModelInvocationService service = new DemoModelInvocationService(gateway);

        assertSame(invocation, service.invokeText("检查这段企业公告", context));

        ArgumentCaptor<ModelRequest> requestCaptor = ArgumentCaptor.forClass(ModelRequest.class);
        verify(gateway).invoke(requestCaptor.capture(), same(context));
        ModelRequest request = requestCaptor.getValue();
        assertEquals(Integer.valueOf(300), request.getMaxTokens());
        assertEquals(2, request.getMessages().size());
        AgentMessage userMessage = request.getMessages().get(1);
        assertEquals("检查这段企业公告",
                ((TextBlock) userMessage.getBlocks().get(0)).getText());
        assertEquals(0, request.getTools().size(), "Java 单次 Demo 不应声明 Tool");
    }

    /** 图片 Demo 应复用 ImageSource，并保持文本与图片的块顺序。 */
    @Test
    void 图片示例构造多模态Core请求() {
        ModelGateway gateway = mock(ModelGateway.class);
        ModelInvocation invocation = mock(ModelInvocation.class);
        AiRequestContext context = requestContext(user());
        when(gateway.invoke(any(ModelRequest.class), same(context))).thenReturn(invocation);
        DemoModelInvocationService service = new DemoModelInvocationService(gateway);
        ImageSource source = ImageSource.base64("image/png", "QUJD");

        assertSame(invocation, service.invokeImage("检查图片", source, context));

        ArgumentCaptor<ModelRequest> requestCaptor = ArgumentCaptor.forClass(ModelRequest.class);
        verify(gateway).invoke(requestCaptor.capture(), same(context));
        AgentMessage userMessage = requestCaptor.getValue().getMessages().get(1);
        assertEquals(2, userMessage.getBlocks().size());
        assertEquals("检查图片", ((TextBlock) userMessage.getBlocks().get(0)).getText());
        assertSame(source, ((ImageBlock) userMessage.getBlocks().get(1)).getSource());
    }

    /** 文本 Controller 应显式使用约定超时，而不是无限阻塞。 */
    @Test
    void 文本入口使用二十秒阻塞超时() {
        DemoModelInvocationService service = mock(DemoModelInvocationService.class);
        ModelInvocation invocation = mock(ModelInvocation.class);
        ModelResponse response = response();
        UserContext user = user();
        when(service.invokeText(eq("同步文本"), any(AiRequestContext.class)))
                .thenReturn(invocation);
        when(invocation.await(Duration.ofSeconds(20))).thenReturn(response);
        DemoModelInvocationController controller = new DemoModelInvocationController(
                service, currentUserProvider(user));
        DemoModelInvocationController.TextInvocationRequest request =
                new DemoModelInvocationController.TextInvocationRequest();
        request.setContent("同步文本");

        assertSame(response, controller.invokeText(request));
        verify(invocation).await(Duration.ofSeconds(20));
        ArgumentCaptor<AiRequestContext> contextCaptor =
                ArgumentCaptor.forClass(AiRequestContext.class);
        verify(service).invokeText(eq("同步文本"), contextCaptor.capture());
        assertAuthenticatedContext(contextCaptor.getValue(), user);
    }

    /** 图片 Controller 应返回 MVC 异步结果，并显式传递当前身份与链路。 */
    @Test
    void 图片入口使用Mvc异步结果和可信上下文() {
        DemoModelInvocationService service = mock(DemoModelInvocationService.class);
        ModelInvocation invocation = mock(ModelInvocation.class);
        ModelResponse response = response();
        UserContext user = user();
        CompletableFuture<ModelResponse> upstreamResult = new CompletableFuture<ModelResponse>();
        when(service.invokeImage(
                eq("识别设备"), any(ImageSource.class), any(AiRequestContext.class)))
                .thenReturn(invocation);
        when(invocation.result()).thenReturn(upstreamResult);
        DemoModelInvocationController controller = new DemoModelInvocationController(
                service, currentUserProvider(user));
        DemoModelInvocationController.ImageInvocationRequest request =
                new DemoModelInvocationController.ImageInvocationRequest();
        request.setPrompt("识别设备");
        request.setImageUrl("https://example.test/device.png");

        DeferredResult<ModelResponse> result = controller.invokeImage(request);
        assertFalse(result.hasResult());
        upstreamResult.complete(response);
        assertSame(response, result.getResult());

        ArgumentCaptor<ImageSource> sourceCaptor = ArgumentCaptor.forClass(ImageSource.class);
        ArgumentCaptor<AiRequestContext> contextCaptor =
                ArgumentCaptor.forClass(AiRequestContext.class);
        verify(service).invokeImage(
                eq("识别设备"), sourceCaptor.capture(), contextCaptor.capture());
        assertEquals(ImageSource.Type.URL, sourceCaptor.getValue().getType());
        assertEquals("https://example.test/device.png", sourceCaptor.getValue().getUrl());
        assertAuthenticatedContext(contextCaptor.getValue(), user);
    }

    /** 容器超时、异常和完成回调都必须取消不再需要的真实上游。 */
    @Test
    void Mvc异步终止路径均取消模型调用() {
        ModelInvocation timeoutInvocation = pendingInvocation();
        ModelInvocationDeferredResult timeoutResult =
                new ModelInvocationDeferredResult(timeoutInvocation, Duration.ofSeconds(20));
        timeoutResult.handleTimeout();
        assertTrue(timeoutResult.getResult() instanceof AsyncRequestTimeoutException);
        verify(timeoutInvocation).cancel();

        ModelInvocation errorInvocation = pendingInvocation();
        ModelInvocationDeferredResult errorResult =
                new ModelInvocationDeferredResult(errorInvocation, Duration.ofSeconds(20));
        RuntimeException asyncError = new RuntimeException("client disconnected");
        errorResult.handleAsyncError(asyncError);
        assertSame(asyncError, errorResult.getResult());
        verify(errorInvocation).cancel();

        ModelInvocation completedInvocation = pendingInvocation();
        ModelInvocationDeferredResult completedResult =
                new ModelInvocationDeferredResult(completedInvocation, Duration.ofSeconds(20));
        completedResult.handleCompletion();
        verify(completedInvocation).cancel();
    }

    /** 图片请求来源含糊时应在 Demo HTTP 边界明确失败，而不是猜测优先级。 */
    @Test
    void 图片入口拒绝同时提供Url与Base64() {
        DemoModelInvocationService service = mock(DemoModelInvocationService.class);
        DemoModelInvocationController controller = new DemoModelInvocationController(
                service, currentUserProvider(user()));
        DemoModelInvocationController.ImageInvocationRequest request =
                new DemoModelInvocationController.ImageInvocationRequest();
        request.setPrompt("检查图片");
        request.setImageUrl("https://example.test/device.png");
        request.setMediaType("image/png");
        request.setImageBase64("QUJD");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> controller.invokeImage(request));
        assertEquals("图片必须且只能提供 imageUrl，或同时提供 mediaType 与 imageBase64",
                error.getMessage());
    }

    /**
     * 创建一个保持运行中的调用句柄，用于单独验证下游终止取消。
     *
     * @return 结果不会自行完成的 mock 调用
     */
    private static ModelInvocation pendingInvocation() {
        ModelInvocation invocation = mock(ModelInvocation.class);
        when(invocation.result()).thenReturn(new CompletableFuture<ModelResponse>());
        return invocation;
    }

    /**
     * 创建 Demo HTTP 入口能够解析的已认证用户。
     *
     * @return 同时包含用户和租户标识的可信用户
     */
    private static UserContext user() {
        return UserContext.builder()
                .userId("user-1")
                .username("demo-user")
                .tenantId("tenant-1")
                .build();
    }

    /**
     * 用固定用户模拟宿主安全适配器，不伪造浏览器身份字段。
     *
     * @param user 宿主已认证的用户
     * @return 只从宿主边界返回该用户的解析器
     */
    private static CurrentUserProvider currentUserProvider(UserContext user) {
        CurrentUserProvider provider = mock(CurrentUserProvider.class);
        when(provider.currentUser(any(AiRequestContext.class))).thenReturn(user);
        return provider;
    }

    /**
     * 创建 Service 单元测试使用的显式上下文。
     *
     * @param user 宿主提供的可信用户
     * @return 包含固定测试链路的上下文
     */
    private static AiRequestContext requestContext(UserContext user) {
        return new AiRequestContext(user, "trace-test", null, null, null);
    }

    /**
     * 验证 Controller 将宿主用户原样传递，并为每次模型调用创建可解析的 UUID traceId。
     *
     * @param context Controller 传入 Service 的上下文
     * @param expectedUser 宿主安全适配器返回的用户
     */
    private static void assertAuthenticatedContext(
            AiRequestContext context, UserContext expectedUser) {
        assertSame(expectedUser, context.getUser());
        assertEquals(UUID.fromString(context.getTraceId()).toString(), context.getTraceId());
    }

    /**
     * 创建用于验证 Controller 传递语义的真实不可变响应，避免测试依赖 final 类型 mock。
     *
     * @return 最小完整文本响应
     */
    private static ModelResponse response() {
        AgentMessage message = new AgentMessage(
                "response-1",
                MessageRole.ASSISTANT,
                Collections.singletonList(new TextBlock("完成")));
        return new ModelResponse(message, ModelStopReason.END_TURN, null, null);
    }
}
