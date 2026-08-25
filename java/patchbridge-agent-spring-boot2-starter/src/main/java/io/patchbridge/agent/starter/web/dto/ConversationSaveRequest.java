package io.patchbridge.agent.starter.web.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PUT /ai/conversations/{id} 的唯一请求 DTO。
 *
 * <p>revision 使用包装类型以区分“缺失”和合法的 0；context 保留显式 modelState 字段，因此 null
 * 表示当前无状态，而字段缺失会被稳定协议映射器拒绝。未知顶层字段 也会被收集并在校验阶段拒绝，避免旧 messages 协议被 Jackson 静默忽略。
 */
public class ConversationSaveRequest {

    /** 可选会话标题；null 表示不修改现有标题。 */
    private String title;

    /** 浏览器持有的乐观锁版本；必须显式提供。 */
    private Long revision;

    /** 包含 messages 与显式 modelState 的完整稳定上下文。 */
    private Map<String, Object> context;

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

    /** 返回客户端显式提供的乐观锁版本。 */
    public Long getRevision() {
        return revision;
    }

    /** 设置客户端乐观锁版本。 */
    public void setRevision(Long revision) {
        this.revision = revision;
    }

    /** 返回完整会话上下文 JSON 对象。 */
    public Map<String, Object> getContext() {
        return context;
    }

    /** 设置完整会话上下文 JSON 对象。 */
    public void setContext(Map<String, Object> context) {
        this.context = context;
    }

    /** 收集 Jackson 全局配置可能忽略的未知字段，防止错误输入悄然通过。 */
    @JsonAnySetter
    public void captureUnknownField(String name, Object value) {
        unknownFields.put(name, value);
    }

    /**
     * 校验保存请求的聚合边界字段。
     *
     * <p>Context 内部消息与 Block 的严格结构由 Core 唯一映射入口继续校验。
     */
    public void validate() {
        if (!unknownFields.isEmpty()) {
            throw new IllegalArgumentException("会话保存请求包含未知字段: " + unknownFields.keySet());
        }
        if (revision == null) {
            throw new IllegalArgumentException("缺少 revision");
        }
        if (revision.longValue() < 0) {
            throw new IllegalArgumentException("revision 不能小于 0");
        }
        if (context == null) {
            throw new IllegalArgumentException("缺少 context");
        }
        ConversationRequestValidator.validateTitle(title);
    }
}
