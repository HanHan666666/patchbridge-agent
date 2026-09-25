package io.patchbridge.agent.core.model;

import io.patchbridge.agent.core.model.target.ModelTargetRef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Java 单次模型请求的便捷构建入口。
 *
 * <p>Builder 只组合现有 {@link AgentMessage} 与 {@link ContentBlock}，不创建第二套后端 DTO，也不注入提示词、业务规则或厂商字段。复杂历史消息仍可直接使用 {@link
 * ModelRequest} 构造器表达。
 */
public final class ModelRequests {

    /** 工具入口不允许实例化。 */
    private ModelRequests() {}

    /** 创建一次性的请求 Builder；消息标识在构建时自动生成。 */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 面向常见 System + User 多模态输入的请求 Builder。
     *
     * <p>同一角色的多次调用会按调用顺序组成一条消息，图片和用户文本因此能自然位于同一个多模态 User 消息中。
     */
    public static final class Builder {

        /** 可选的显式响应消息标识；未设置时在 build 阶段生成。 */
        private String responseMessageId;

        /** 必须由宿主显式选择的部署目标。 */
        private ModelTargetRef modelTarget;

        /** 按调用顺序积累的 System 文本块。 */
        private final List<ContentBlock> systemBlocks = new ArrayList<ContentBlock>();

        /** 按调用顺序积累的 User 文本与图片块。 */
        private final List<ContentBlock> userBlocks = new ArrayList<ContentBlock>();

        /** 可选 Provider 连续状态。 */
        private ModelState modelState;

        /** 可选采样温度。 */
        private Double temperature;

        /** 可选最大输出 token 数。 */
        private Integer maxTokens;

        /** 仅由外层工厂创建 Builder，避免入口形状分散。 */
        private Builder() {}

        /** 设置需要由宿主稳定关联的响应消息标识。 */
        public Builder responseMessageId(String responseMessageId) {
            this.responseMessageId = responseMessageId;
            return this;
        }

        /** 追加一段宿主定义的 System 文本。 */
        public Builder systemText(String text) {
            systemBlocks.add(new TextBlock(text));
            return this;
        }

        /** 追加一段用户文本输入。 */
        public Builder userText(String text) {
            userBlocks.add(new TextBlock(text));
            return this;
        }

        /** 追加一个 URL 或 Base64 图片输入。 */
        public Builder userImage(ImageSource source) {
            userBlocks.add(new ImageBlock(source));
            return this;
        }

        /** 设置目录中的目标身份，避免业务调用绕过统一路由。 */
        public Builder modelTarget(ModelTargetRef modelTarget) {
            this.modelTarget = modelTarget;
            return this;
        }

        /** 设置需要原样交回相同 Provider 的连续状态。 */
        public Builder modelState(ModelState modelState) {
            this.modelState = modelState;
            return this;
        }

        /** 设置由 Provider 校验支持范围的采样温度。 */
        public Builder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        /** 设置由 Provider 转换并校验的最大输出 token 数。 */
        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        /** 构建只包含现有领域对象的不可变请求。 */
        public ModelRequest build() {
            if (modelTarget == null) {
                throw new IllegalStateException("modelTarget 不可为空");
            }
            List<AgentMessage> messages = new ArrayList<AgentMessage>(2);
            if (!systemBlocks.isEmpty()) {
                messages.add(
                        new AgentMessage(
                                newMessageId(),
                                MessageRole.SYSTEM,
                                new ArrayList<ContentBlock>(systemBlocks)));
            }
            if (!userBlocks.isEmpty()) {
                messages.add(
                        new AgentMessage(
                                newMessageId(),
                                MessageRole.USER,
                                new ArrayList<ContentBlock>(userBlocks)));
            }
            String resultMessageId =
                    responseMessageId == null ? newMessageId() : responseMessageId;
            return new ModelRequest(
                    resultMessageId,
                    modelTarget,
                    messages,
                    Collections.<ModelToolDefinition>emptyList(),
                    modelState,
                    temperature,
                    maxTokens);
        }

        /** 生成只承担单次请求关联职责的随机消息标识。 */
        private static String newMessageId() {
            return UUID.randomUUID().toString();
        }
    }
}
