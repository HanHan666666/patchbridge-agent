package io.patchbridge.agent.demo.model;

import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.invocation.ModelGateway;
import io.patchbridge.agent.core.invocation.ModelInvocation;
import io.patchbridge.agent.core.model.ImageSource;
import io.patchbridge.agent.core.model.ModelRequest;
import io.patchbridge.agent.core.model.ModelRequests;
import org.springframework.stereotype.Service;

/**
 * Demo 的后端单次模型调用业务服务。
 *
 * <p>该服务展示存量 Java Service 如何直接注入同 JVM {@link ModelGateway}，分别构造文本和
 * 图片请求。它不创建 Agent Loop、不调用浏览器 SSE 端点，也不保存 Conversation、Audit 或
 * 模型结果；宿主拿到 {@link ModelInvocation} 后自行选择异步、阻塞、取消和业务持久化方式。
 */
@Service
public class DemoModelInvocationService {

    /** Demo 文本任务自己的提示词；业务规则必须留在宿主而不是框架 Core。 */
    private static final String TEXT_SYSTEM_PROMPT =
            "你正在执行企业文本检查示例。请准确概括输入内容，并指出需要人工复核的风险。";

    /** Demo 图片任务自己的提示词；Provider 只负责协议，不内置审核语义。 */
    private static final String IMAGE_SYSTEM_PROMPT =
            "你正在执行企业图片检查示例。请客观描述图片，并指出需要人工复核的风险。";

    /** Demo 对输出长度的明确业务预算，避免把无限输出当成框架默认行为。 */
    private static final int MAX_OUTPUT_TOKENS = 300;

    /** 同 JVM 模型调用入站端口；Starter 默认 Bean 可由宿主同类型 Bean 替换。 */
    private final ModelGateway modelGateway;

    /**
     * 创建只依赖公共模型门面的 Demo Service。
     *
     * @param modelGateway Starter 装配或宿主替换的模型调用门面
     */
    public DemoModelInvocationService(ModelGateway modelGateway) {
        this.modelGateway = modelGateway;
    }

    /**
     * 发起一次真实文本模型调用，不等待也不保存结果。
     *
     * @param content 需要交给模型处理的宿主业务文本
     * @param context 由调用边界构造的可信请求上下文
     * @return 由调用者控制等待、取消和结果处理的单次调用句柄
     */
    public ModelInvocation invokeText(String content, AiRequestContext context) {
        requireText(content, "content");
        ModelRequest request = ModelRequests.builder()
                .systemText(TEXT_SYSTEM_PROMPT)
                .userText(content)
                .maxTokens(MAX_OUTPUT_TOKENS)
                .build();
        return modelGateway.invoke(request, context);
    }

    /**
     * 发起一次真实多模态模型调用，不把图片或响应写入 Demo 数据库。
     *
     * @param prompt 图片相关的业务问题
     * @param imageSource 已明确为 URL 或 Base64 的图片来源
     * @param context 由 HTTP 边界从宿主登录态构造的可信请求上下文
     * @return 由调用者控制等待、取消和结果处理的单次调用句柄
     */
    public ModelInvocation invokeImage(
            String prompt, ImageSource imageSource, AiRequestContext context) {
        requireText(prompt, "prompt");
        if (imageSource == null) {
            throw new IllegalArgumentException("imageSource 不可为空");
        }
        ModelRequest request = ModelRequests.builder()
                .systemText(IMAGE_SYSTEM_PROMPT)
                .userText(prompt)
                .userImage(imageSource)
                .maxTokens(MAX_OUTPUT_TOKENS)
                .build();
        return modelGateway.invoke(request, context);
    }

    /**
     * 在 Demo 业务边界拒绝空输入，避免把无意义请求和模型费用推给 Provider。
     *
     * @param value 待检查文本
     * @param fieldName 对外错误中的稳定字段名
     */
    private static void requireText(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " 不可为空");
        }
    }
}
