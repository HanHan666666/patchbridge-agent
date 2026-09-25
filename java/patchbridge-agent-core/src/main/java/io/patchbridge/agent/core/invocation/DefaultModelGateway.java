package io.patchbridge.agent.core.invocation;

import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.error.ModelGatewayException;
import io.patchbridge.agent.core.model.ModelCall;
import io.patchbridge.agent.core.model.ModelRequest;

import io.patchbridge.agent.core.model.target.ModelAccessContext;
import io.patchbridge.agent.core.model.target.ModelTargetException;
import java.util.UUID;

/**
 * 基于现有 {@link ModelInvocationPipeline} 的默认 Java 单次模型调用门面。
 *
 * <p>实现只在当前调用内创建响应聚合器和生命周期句柄，不读取 Web 登录态，不执行 Tool，也不保存 Conversation、Audit 或调用历史。
 */
public final class DefaultModelGateway implements ModelGateway {

    /** 复用既有 Interceptor 与 Provider 调用边界的模型管线。 */
    private final ModelInvocationPipeline invocationPipeline;

    /** 创建不持有任何调用历史的默认门面。 */
    public DefaultModelGateway(ModelInvocationPipeline invocationPipeline) {
        if (invocationPipeline == null) {
            throw new IllegalArgumentException("invocationPipeline 不可为空");
        }
        this.invocationPipeline = invocationPipeline;
    }

    /** 使用匿名临时链路上下文发起调用，不读取任何 Web 请求状态。 */
    @Override
    public ModelInvocation invoke(ModelRequest request) {
        return invoke(
                request,
                new AiRequestContext(null, UUID.randomUUID().toString(), null, null, null));
    }

    /** 使用宿主显式提供的可信上下文发起一次调用。 */
    @Override
    public ModelInvocation invoke(ModelRequest request, AiRequestContext context) {
        validateRequest(request);
        if (context == null) {
            throw new IllegalArgumentException("context 不可为空");
        }

        DefaultModelInvocation invocation = new DefaultModelInvocation();
        ModelResponseAssembler assembler =
                new ModelResponseAssembler(request.getResponseMessageId(), invocation);
        invocation.bindAssembler(assembler);
        final ModelCall upstream;
        try {
            upstream = invocationPipeline.stream(request, context,
                    context.getUser() == null ? ModelAccessContext.trustedJvm()
                            : ModelAccessContext.authenticated(context.getUser()), assembler);
        } catch (ModelTargetException e) {
            invocation.discardAssembler();
            throw e;
        } catch (ModelGatewayException e) {
            invocation.discardAssembler();
            throw e;
        } catch (RuntimeException e) {
            invocation.discardAssembler();
            throw new ModelGatewayException("模型调用启动失败", e, false);
        }
        invocation.bind(upstream);
        return invocation;
    }

    /** 在启动 Provider 前拒绝超出单次无 Tool 语义的请求。 */
    private static void validateRequest(ModelRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不可为空");
        }
        if (!request.getTools().isEmpty()) {
            throw new IllegalArgumentException("Java 单次模型调用不支持 Tool 定义");
        }
    }
}
