package io.patchbridge.agent.starter.web.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POST /ai/tools/call 请求体（MCP 风格）。
 *
 * <p>arguments 只允许业务参数；userId / tenantId 等可信上下文绝不在此出现。
 * 与 Model/ConversationContext 相同的严格契约：未知字段由 {@link JsonAnySetter}
 * 收集并在 {@link #validate()} 统一拒绝，避免宿主全局 Jackson 宽松配置把
 * 前后端版本错配静默吞掉。
 */
public class ToolCallRequest {

    /** 目标 Tool 名称。 */
    private String name;

    /**
     * 发现时取得的定义/路由版本引用；静态 Tool 为 null。
     * 服务端用它确认“模型看到的定义”与“本次实际路由的目标”仍然一致。
     */
    private String version;

    /** 业务参数；必须显式提供 JSON 对象，即使无参数也应传 {}。 */
    private Map<String, Object> arguments;

    /** 可选的调用链追踪标识。 */
    private String requestId;

    /** 可选的调用链追踪标识。 */
    private String traceId;

    /** 可选的 Tool 调用标识。 */
    private String toolCallId;

    /** 可选的关联会话标识。 */
    private String conversationId;

    /** Jackson 收集的未知字段，校验时统一拒绝。 */
    private final Map<String, Object> unknownFields = new LinkedHashMap<String, Object>();

    /** 返回目标 Tool 名称。 */
    public String getName() { return name; }

    /** 设置目标 Tool 名称。 */
    public void setName(String name) { this.name = name; }

    /** 返回定义/路由版本引用；静态 Tool 为 null。 */
    public String getVersion() { return version; }

    /** 设置定义/路由版本引用；由 Browser 从工具发现结果原样回传。 */
    public void setVersion(String version) { this.version = version; }

    /** 返回业务参数对象。 */
    public Map<String, Object> getArguments() { return arguments; }

    /** 设置业务参数对象。 */
    public void setArguments(Map<String, Object> arguments) { this.arguments = arguments; }

    /** 返回可选调用链追踪标识。 */
    public String getRequestId() { return requestId; }

    /** 设置可选调用链追踪标识。 */
    public void setRequestId(String requestId) { this.requestId = requestId; }

    /** 返回可选调用链追踪标识。 */
    public String getTraceId() { return traceId; }

    /** 设置可选调用链追踪标识。 */
    public void setTraceId(String traceId) { this.traceId = traceId; }

    /** 返回可选 Tool 调用标识。 */
    public String getToolCallId() { return toolCallId; }

    /** 设置可选 Tool 调用标识。 */
    public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }

    /** 返回可选关联会话标识。 */
    public String getConversationId() { return conversationId; }

    /** 设置可选关联会话标识。 */
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    /** 收集 Jackson 全局配置可能忽略的未知字段，防止错误输入悄然通过。 */
    @JsonAnySetter
    public void captureUnknownField(String name, Object value) {
        unknownFields.put(name, value);
    }

    /**
     * 校验调用请求的契约边界。
     *
     * <p>name 必须非空白；arguments 必须显式提供 JSON 对象，
     * 缺失（null）视为协议违规而不是“无参数”，防止客户端漏传被解释为空参。
     */
    public void validate() {
        if (!unknownFields.isEmpty()) {
            throw new IllegalArgumentException(
                    "Tool 调用请求包含未知字段: " + unknownFields.keySet());
        }
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少 Tool 名称");
        }
        if (arguments == null) {
            throw new IllegalArgumentException("arguments 必须是显式提供的 JSON 对象");
        }
    }
}
