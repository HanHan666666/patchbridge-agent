package io.patchbridge.agent.starter.web.dto;

import io.patchbridge.agent.core.compaction.ContextCompactionRequest;
import io.patchbridge.agent.core.model.target.ModelTargetRef;
import io.patchbridge.agent.core.conversation.ContextCompactionTrigger;
import io.patchbridge.agent.core.conversation.ConversationContextValues;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * POST /ai/model/compact 的严格浏览器请求信封。
 *
 * <p>DTO 只承载 HTTP 形状；消息、ContentBlock 与 ModelState 的唯一解析入口仍是 Core
 * ConversationContextValues。未知字段和缺失的显式 null 均在启动模型调用前失败。
 */
public class ContextCompactionEnvelope {

    /** 可选浏览器链路标识。 */
    private String traceId;
    /** 可选会话标识，仅供可信上下文与审计关联。 */
    private String conversationId;
    /** 压缩请求主体。 */
    private Request request;
    /** 捕获未知信封字段。 */
    private final Map<String, Object> unknownFields = new LinkedHashMap<String, Object>();

    /** 上下文压缩请求 DTO。 */
    public static class Request {
        /** 与摘要和状态投影绑定的公开目标引用。 */
        private Map<String, Object> modelTarget;
        /** 返回摘要目标。 */
        public Map<String, Object> getModelTarget() { return modelTarget; }
        /** 绑定目标，不从服务端默认值推测。 */
        public void setModelTarget(Map<String, Object> value) { modelTarget = value; }

        /** automatic / manual。 */
        private String trigger;
        /** 固定 system 消息和被淘汰历史。 */
        private List<Map<String, Object>> messagesToSummarize;
        /** 固定 system 消息和保留近期消息。 */
        private List<Map<String, Object>> retainedMessages;
        /** 可空上一份摘要。 */
        private String previousSummary;
        /** 区分 previousSummary 缺失与显式 null。 */
        private boolean previousSummarySet;
        /** 当前 Provider 私有状态。 */
        private Map<String, Object> modelState;
        /** 区分 modelState 缺失与显式 null。 */
        private boolean modelStateSet;
        /** 摘要响应稳定 ID。 */
        private String responseMessageId;
        /** 切分点是否位于 assistant 起始处。 */
        private Boolean splitTurn;
        /** 捕获未知请求字段。 */
        private final Map<String, Object> unknownFields = new LinkedHashMap<String, Object>();

        /** 校验 HTTP 字段并转换为不可变 Core 请求。 */
        public ContextCompactionRequest toDomain() {
            rejectUnknownFields(unknownFields, "request");
            requireText(trigger, "request.trigger 不可为空");
            requireText(responseMessageId, "request.responseMessageId 不可为空");
            if (messagesToSummarize == null || messagesToSummarize.isEmpty()) {
                throw new IllegalArgumentException("request.messagesToSummarize 不可为空");
            }
            if (retainedMessages == null || retainedMessages.isEmpty()) {
                throw new IllegalArgumentException("request.retainedMessages 不可为空");
            }
            if (!previousSummarySet) {
                throw new IllegalArgumentException("request.previousSummary 必须显式提供");
            }
            if (!modelStateSet) {
                throw new IllegalArgumentException("request.modelState 必须显式提供");
            }
            if (splitTurn == null) {
                throw new IllegalArgumentException("request.splitTurn 必须显式提供");
            }
            return new ContextCompactionRequest(
                    ModelTargetRef.fromValue(modelTarget),
                    ContextCompactionTrigger.fromWireValue(trigger),
                    ConversationContextValues.fromMessagesValue(messagesToSummarize),
                    ConversationContextValues.fromMessagesValue(retainedMessages),
                    previousSummary,
                    ConversationContextValues.fromModelStateValue(modelState),
                    responseMessageId,
                    splitTurn.booleanValue());
        }

        /** 返回触发来源。 */
        public String getTrigger() { return trigger; }
        /** 设置触发来源。 */
        public void setTrigger(String value) { this.trigger = value; }
        /** 返回被摘要消息 JSON。 */
        public List<Map<String, Object>> getMessagesToSummarize() {
            return messagesToSummarize;
        }
        /** 设置被摘要消息 JSON。 */
        public void setMessagesToSummarize(List<Map<String, Object>> value) {
            this.messagesToSummarize = value;
        }
        /** 返回保留消息 JSON。 */
        public List<Map<String, Object>> getRetainedMessages() { return retainedMessages; }
        /** 设置保留消息 JSON。 */
        public void setRetainedMessages(List<Map<String, Object>> value) {
            this.retainedMessages = value;
        }
        /** 返回上一份摘要。 */
        public String getPreviousSummary() { return previousSummary; }
        /** 设置可空上一份摘要并记录字段出现。 */
        public void setPreviousSummary(String value) {
            this.previousSummary = value;
            this.previousSummarySet = true;
        }
        /** 返回模型状态 JSON。 */
        public Map<String, Object> getModelState() { return modelState; }
        /** 设置可空模型状态并记录字段出现。 */
        public void setModelState(Map<String, Object> value) {
            this.modelState = value;
            this.modelStateSet = true;
        }
        /** 返回响应消息 ID。 */
        public String getResponseMessageId() { return responseMessageId; }
        /** 设置响应消息 ID。 */
        public void setResponseMessageId(String value) { this.responseMessageId = value; }
        /** 返回是否为拆分回合。 */
        public Boolean getSplitTurn() { return splitTurn; }
        /** 设置是否为拆分回合。 */
        public void setSplitTurn(Boolean value) { this.splitTurn = value; }
        /** 捕获请求未知字段。 */
        @JsonAnySetter
        public void captureUnknownField(String name, Object value) {
            unknownFields.put(name, value);
        }
    }

    /** 校验信封并返回不可变 Core 请求。 */
    public ContextCompactionRequest toDomain() {
        rejectUnknownFields(unknownFields, "contextCompactionEnvelope");
        if (request == null) {
            throw new IllegalArgumentException("上下文压缩请求缺少 request");
        }
        return request.toDomain();
    }

    /** 返回可选 traceId。 */
    public String getTraceId() { return traceId; }
    /** 设置可选 traceId。 */
    public void setTraceId(String value) { this.traceId = value; }
    /** 返回可选会话 ID。 */
    public String getConversationId() { return conversationId; }
    /** 设置可选会话 ID。 */
    public void setConversationId(String value) { this.conversationId = value; }
    /** 返回请求 DTO。 */
    public Request getRequest() { return request; }
    /** 设置请求 DTO。 */
    public void setRequest(Request value) { this.request = value; }

    /** 捕获信封未知字段。 */
    @JsonAnySetter
    public void captureUnknownField(String name, Object value) {
        unknownFields.put(name, value);
    }

    /** 未知字段必须明确拒绝，不能受宿主 Jackson 宽松配置影响。 */
    private static void rejectUnknownFields(Map<String, Object> fields, String path) {
        if (!fields.isEmpty()) {
            throw new IllegalArgumentException(path + " 包含未知字段: " + fields.keySet());
        }
    }

    /** 协议身份字段必须是非空白文本。 */
    private static void requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }
}
