package io.patchbridge.agent.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 厂商中立的单次模型请求。
 *
 * <p>Core 只表达 Agent 领域消息、工具和连续状态，不携带任何模型厂商字段。 {@link ModelProvider} 必须在自己的边界内完成请求编码，并且不得把 {@code
 * responseMessageId} 等框架关联字段发送给上游模型。
 */
public final class ModelRequest {

    /** 本次模型响应将使用的稳定消息标识，用于关联不可见的模型连续状态。 */
    private final String responseMessageId;

    /** 可选模型名称；为空时由 Provider 选择其服务端默认模型。 */
    private final String model;

    /** 发起本轮调用前已经稳定提交的领域消息。 */
    private final List<AgentMessage> messages;

    /** 本轮模型可见的工具定义快照。 */
    private final List<ModelToolDefinition> tools;

    /** 上一轮由相同 Provider 生成、Runtime 不解释的连续状态。 */
    private final ModelState modelState;

    /** 可选采样温度，具体支持范围由 Provider 校验。 */
    private final Double temperature;

    /** 可选最大输出 token 数，具体参数名由 Provider 决定。 */
    private final Integer maxTokens;

    /** 创建不可变模型请求。 */
    public ModelRequest(
            String responseMessageId,
            String model,
            List<AgentMessage> messages,
            List<ModelToolDefinition> tools,
            ModelState modelState,
            Double temperature,
            Integer maxTokens) {
        if (responseMessageId == null || responseMessageId.trim().isEmpty()) {
            throw new IllegalArgumentException("responseMessageId 不可为空");
        }
        if (messages == null) {
            throw new IllegalArgumentException("messages 不可为空");
        }
        if (messages.contains(null)) {
            throw new IllegalArgumentException("messages 不允许包含 null");
        }
        if (tools != null && tools.contains(null)) {
            throw new IllegalArgumentException("tools 不允许包含 null");
        }
        this.responseMessageId = responseMessageId;
        this.model = model;
        this.messages = Collections.unmodifiableList(new ArrayList<AgentMessage>(messages));
        this.tools =
                tools == null
                        ? Collections.<ModelToolDefinition>emptyList()
                        : Collections.unmodifiableList(new ArrayList<ModelToolDefinition>(tools));
        this.modelState = modelState;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    /** 返回本次响应的框架消息标识；Provider 只能用于状态关联。 */
    public String getResponseMessageId() {
        return responseMessageId;
    }

    /** 返回可选模型覆盖名。 */
    public String getModel() {
        return model;
    }

    /** 返回不可变领域消息快照。 */
    public List<AgentMessage> getMessages() {
        return messages;
    }

    /** 返回不可变工具定义快照。 */
    public List<ModelToolDefinition> getTools() {
        return tools;
    }

    /** 返回可选的 Provider 连续状态。 */
    public ModelState getModelState() {
        return modelState;
    }

    /** 返回可选采样温度。 */
    public Double getTemperature() {
        return temperature;
    }

    /** 返回可选最大输出 token 数。 */
    public Integer getMaxTokens() {
        return maxTokens;
    }
}
