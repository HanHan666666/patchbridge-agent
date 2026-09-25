package io.patchbridge.agent.starter.web.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POST /ai/conversations 的唯一请求 DTO。
 *
 * <p>创建会话必须携带完整首轮 Context；title 可选，未知字段在 {@link #validate()} 统一拒绝。
 * 之前直接绑定 {@code Map} 的写法无法区分“合法 title”与拼写错误的额外字段，
 * 会让前后端协议错配被静默吞掉。
 */
public class ConversationCreateRequest {

    /** 首轮稳定的完整消息、目标与工作上下文，必须原子创建。 */
    private Map<String, Object> context;
    /** 返回待创建的完整上下文。 */
    public Map<String, Object> getContext() { return context; }
    /** 绑定完整上下文，由 Core 严格解析。 */
    public void setContext(Map<String, Object> value) { context = value; }

    /** 可选会话标题；null 表示创建未命名会话。 */
    private String title;

    /** Jackson 收集的未知字段，校验时统一拒绝。 */
    private final Map<String, Object> unknownFields = new LinkedHashMap<String, Object>();

    /** 返回可选会话标题。 */
    public String getTitle() {
        return title;
    }

    /** 设置可选会话标题。 */
    public void setTitle(String title) {
        this.title = title;
    }

    /** 收集 Jackson 全局配置可能忽略的未知字段，防止错误输入悄然通过。 */
    @JsonAnySetter
    public void captureUnknownField(String name, Object value) {
        unknownFields.put(name, value);
    }

    /** 校验创建请求只包含契约内字段，且标题可由 v0.1 Schema 持久化。 */
    public void validate() {
        if (context == null) throw new IllegalArgumentException("创建会话必须包含 context");
        if (!unknownFields.isEmpty()) {
            throw new IllegalArgumentException(
                    "会话创建请求包含未知字段: " + unknownFields.keySet());
        }
        ConversationRequestValidator.validateTitle(title);
    }
}
