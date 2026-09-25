package io.patchbridge.agent.starter.web.dto;

import io.patchbridge.agent.core.conversation.ConversationContextValues;
import io.patchbridge.agent.core.model.ModelRequest;
import io.patchbridge.agent.core.model.target.ModelTargetRef;
import io.patchbridge.agent.core.model.ModelToolDefinition;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * POST /ai/model/stream 的浏览器请求信封。
 *
 * <p>该 DTO 保留信封和模型参数的 HTTP 形状；AgentMessage / ContentBlock 的严格校验 统一委托给 Core 的 {@link
 * ConversationContextValues}，避免模型端点和会话端点各自 维护一套判别联合规则。traceId 等框架字段不会进入 Provider 请求。
 */
public class ModelStreamEnvelope {
    /** 可选浏览器链路标识；缺失时由服务端生成。 */
    private String traceId;

    /** 可选会话标识，仅供上下文和审计关联。 */
    private String conversationId;

    /** 厂商中立模型请求。 */
    private Request request;

    /** 收集信封未知字段，避免拼写错误被 Jackson 全局配置忽略。 */
    private final Map<String, Object> unknownFields = new LinkedHashMap<String, Object>();

    /** 厂商中立模型请求 DTO。 */
    public static class Request {
        /** 本次 assistant 稳定消息 ID，供 ModelState 关联。 */
        private String responseMessageId;

        /** 精确目标引用，不接受上游模型名称。 */
        private Map<String, Object> modelTarget;

        /** 历史领域消息的 JSON 值；具体块结构由 Core 唯一映射器校验。 */
        private List<Map<String, Object>> messages;

        /** 本轮工具定义快照。 */
        private List<Tool> tools;

        /** 上一轮 Provider 连续状态的 JSON 值。 */
        private Map<String, Object> modelState;

        /** 区分显式 JSON null 与字段缺失；两者对续接语义不同。 */
        private boolean modelStateSet;

        /** 可选采样温度。 */
        private Double temperature;

        /** 可选最大输出 token。 */
        private Integer maxTokens;

        /** 收集请求未知字段，校验时明确拒绝。 */
        private final Map<String, Object> unknownFields = new LinkedHashMap<String, Object>();

        /** 校验完整请求并映射为不可变 Core 模型。 */
        public ModelRequest toModelRequest() {
            rejectUnknownFields(unknownFields, "request");
            requireText(responseMessageId, "模型请求缺少 responseMessageId");
            if (messages == null || messages.isEmpty()) {
                throw new IllegalArgumentException("模型请求缺少 messages");
            }
            if (tools == null) {
                throw new IllegalArgumentException("模型请求缺少 tools");
            }
            if (!modelStateSet) {
                throw new IllegalArgumentException("模型请求缺少 modelState");
            }
            if (maxTokens != null && maxTokens.intValue() <= 0) {
                throw new IllegalArgumentException("maxTokens 必须大于 0");
            }
            List<ModelToolDefinition> mappedTools = new ArrayList<ModelToolDefinition>();
            for (int index = 0; index < tools.size(); index += 1) {
                Tool tool = tools.get(index);
                if (tool == null) {
                    throw new IllegalArgumentException("tools[" + index + "] 不可为空");
                }
                mappedTools.add(tool.toDomain("tools[" + index + "]"));
            }
            return new ModelRequest(
                    responseMessageId,
                    ModelTargetRef.fromValue(modelTarget),
                    ConversationContextValues.fromMessagesValue(messages),
                    mappedTools,
                    ConversationContextValues.fromModelStateValue(modelState),
                    temperature,
                    maxTokens);
        }

        /** 返回响应消息标识。 */
        public String getResponseMessageId() {
            return responseMessageId;
        }

        /** 设置响应消息标识。 */
        public void setResponseMessageId(String value) {
            this.responseMessageId = value;
        }

        /** 返回精确目标 JSON。 */
        public Map<String, Object> getModelTarget() { return modelTarget; }
        /** 绑定公开目标引用。 */
        public void setModelTarget(Map<String, Object> value) { modelTarget = value; }

        /** 返回消息 JSON 值。 */
        public List<Map<String, Object>> getMessages() {
            return messages;
        }

        /** 设置消息 JSON 值。 */
        public void setMessages(List<Map<String, Object>> value) {
            this.messages = value;
        }

        /** 返回工具 DTO。 */
        public List<Tool> getTools() {
            return tools;
        }

        /** 设置工具 DTO。 */
        public void setTools(List<Tool> value) {
            this.tools = value;
        }

        /** 返回模型状态 JSON 值。 */
        public Map<String, Object> getModelState() {
            return modelState;
        }

        /** 设置模型状态 JSON 值。 */
        public void setModelState(Map<String, Object> value) {
            this.modelState = value;
            this.modelStateSet = true;
        }

        /** 返回采样温度。 */
        public Double getTemperature() {
            return temperature;
        }

        /** 设置采样温度。 */
        public void setTemperature(Double value) {
            this.temperature = value;
        }

        /** 返回最大输出 token。 */
        public Integer getMaxTokens() {
            return maxTokens;
        }

        /** 设置最大输出 token。 */
        public void setMaxTokens(Integer value) {
            this.maxTokens = value;
        }

        /** 捕获请求层未知字段，防止 Jackson 全局宽松配置吞掉输入错误。 */
        @JsonAnySetter
        public void captureUnknownField(String name, Object value) {
            unknownFields.put(name, value);
        }
    }

    /** 模型可见工具定义 DTO。 */
    public static class Tool {
        /** 工具完整名。 */
        private String name;

        /** 工具描述。 */
        private String description;

        /** 输入 JSON Schema。 */
        private Map<String, Object> inputSchema;

        /** 收集单条 Tool 的未知字段，避免定义与模型实际所见不一致。 */
        private final Map<String, Object> unknownFields = new LinkedHashMap<String, Object>();

        /** 校验并映射工具定义。 */
        public ModelToolDefinition toDomain(String path) {
            rejectUnknownFields(unknownFields, path);
            requireText(name, path + ".name 不可为空");
            if (description == null) {
                throw new IllegalArgumentException(path + ".description 不可为空");
            }
            if (inputSchema == null) {
                throw new IllegalArgumentException(path + ".inputSchema 不可为空");
            }
            return new ModelToolDefinition(name, description, inputSchema);
        }

        /** 返回工具名。 */
        public String getName() {
            return name;
        }

        /** 设置工具名。 */
        public void setName(String value) {
            this.name = value;
        }

        /** 返回工具描述。 */
        public String getDescription() {
            return description;
        }

        /** 设置工具描述。 */
        public void setDescription(String value) {
            this.description = value;
        }

        /** 返回输入 Schema。 */
        public Map<String, Object> getInputSchema() {
            return inputSchema;
        }

        /** 设置输入 Schema。 */
        public void setInputSchema(Map<String, Object> value) {
            this.inputSchema = value;
        }

        /** 捕获 Tool 定义中的未知字段，并在带位置的映射阶段统一拒绝。 */
        @JsonAnySetter
        public void captureUnknownField(String name, Object value) {
            unknownFields.put(name, value);
        }
    }

    /** 返回 traceId。 */
    public String getTraceId() {
        return traceId;
    }

    /** 设置 traceId。 */
    public void setTraceId(String value) {
        this.traceId = value;
    }

    /** 返回 conversationId。 */
    public String getConversationId() {
        return conversationId;
    }

    /** 设置 conversationId。 */
    public void setConversationId(String value) {
        this.conversationId = value;
    }

    /** 返回模型请求。 */
    public Request getRequest() {
        return request;
    }

    /** 设置模型请求。 */
    public void setRequest(Request value) {
        this.request = value;
    }

    /** 校验最外层信封；请求内容继续由 Request 唯一映射入口校验。 */
    public void validate() {
        rejectUnknownFields(unknownFields, "模型请求信封");
        if (request == null) {
            throw new IllegalArgumentException("模型请求缺少 request");
        }
    }

    /** 捕获信封未知字段，防止 trace/request 之外的数据被静默接受。 */
    @JsonAnySetter
    public void captureUnknownField(String name, Object value) {
        unknownFields.put(name, value);
    }

    /** 校验非空白协议标识。 */
    private static void requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }

    /** 未知字段通常表示客户端版本错配，必须带所在路径明确拒绝。 */
    private static void rejectUnknownFields(Map<String, Object> fields, String path) {
        if (!fields.isEmpty()) {
            throw new IllegalArgumentException(path + " 包含未知字段: " + fields.keySet());
        }
    }
}
