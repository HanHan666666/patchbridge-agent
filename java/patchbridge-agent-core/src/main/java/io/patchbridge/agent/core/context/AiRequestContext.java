package io.patchbridge.agent.core.context;

import io.patchbridge.agent.core.user.UserContext;

/**
 * 一次 AI 请求链路的上下文（不可变）。
 *
 * <p>出现在 @AiTool 方法签名中时由服务器自动注入：
 * 模型只能生成业务参数，本对象携带的全部是可信信息（当前用户、traceId 等），
 * 从协议上杜绝 LLM 伪造 userId / tenantId 的可能。
 */
public final class AiRequestContext {

    private final UserContext user;
    private final String traceId;
    private final String requestId;
    private final String toolCallId;
    private final String conversationId;

    public AiRequestContext(UserContext user, String traceId, String requestId,
                            String toolCallId, String conversationId) {
        this.user = user;
        this.traceId = traceId;
        this.requestId = requestId;
        this.toolCallId = toolCallId;
        this.conversationId = conversationId;
    }

    /** 当前登录用户；可能为 null（匿名），由权限策略决定是否放行。 */
    public UserContext getUser() {
        return user;
    }

    /** 本次 Agent 运行的链路 ID，贯穿 Model / Tool / 审计，用于 Admin Trace 串联。 */
    public String getTraceId() {
        return traceId;
    }

    /** 浏览器生成的请求幂等 ID。 */
    public String getRequestId() {
        return requestId;
    }

    /** 当前 Tool 调用 ID（对应 LLM 的 tool_call id）。 */
    public String getToolCallId() {
        return toolCallId;
    }

    /** 所属会话 ID（可获得时）。 */
    public String getConversationId() {
        return conversationId;
    }
}
