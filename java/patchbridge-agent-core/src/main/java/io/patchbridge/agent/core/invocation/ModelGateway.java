package io.patchbridge.agent.core.invocation;

import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.model.ModelRequest;

/**
 * 宿主 Java Service 发起单次模型调用的入站端口。
 *
 * <p>该端口只负责创建一次模型调用，不执行 Agent Loop、Tool 调度或任何持久化。需要用户、租户或宿主链路信息时，调用方必须显式传入可信上下文，框架不会读取 Web
 * 登录态进行猜测。
 */
public interface ModelGateway {

    /**
     * 使用仅包含临时链路标识的上下文发起调用。
     *
     * <p>该入口适合不需要用户身份的 JVM 内部任务；如 Interceptor 依赖用户或租户信息，应改用显式上下文重载。
     */
    ModelInvocation invoke(ModelRequest request);

    /** 使用调用方提供的可信上下文发起一次模型调用。 */
    ModelInvocation invoke(ModelRequest request, AiRequestContext context);
}
