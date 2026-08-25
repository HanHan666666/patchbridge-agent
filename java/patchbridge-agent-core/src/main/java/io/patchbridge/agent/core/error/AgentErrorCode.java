package io.patchbridge.agent.core.error;

/**
 * 框架级错误码。浏览器端据此做统一展示（重试 / 跳登录 / 提示冲突），
 * 不解析 HTTP 或 Provider 细节。
 */
public final class AgentErrorCode {

    /** 工具类只承载跨端稳定常量，不允许实例化。 */
    private AgentErrorCode() {
    }

    /** 未登录或登录态失效，浏览器应引导用户重新登录。 */
    public static final String AUTH_REQUIRED = "AUTH_REQUIRED";

    /** 无权调用该 Tool（服务端权限策略拒绝）。 */
    public static final String TOOL_FORBIDDEN = "TOOL_FORBIDDEN";

    /** 已登录但不具备管理端能力。 */
    public static final String ADMIN_FORBIDDEN = "ADMIN_FORBIDDEN";

    /** Tool 执行失败（业务异常或参数绑定失败）。 */
    public static final String TOOL_FAILED = "TOOL_FAILED";

    /** 模型网关失败（上游不可达、认证失败、流中断）。 */
    public static final String MODEL_FAILED = "MODEL_FAILED";

    /** 当前可信 owner 范围内找不到指定会话。 */
    public static final String CONVERSATION_NOT_FOUND = "CONVERSATION_NOT_FOUND";

    /** 会话保存冲突（多 Tab 并发写，revision 落后）。 */
    public static final String CONVERSATION_CONFLICT = "CONVERSATION_CONFLICT";

    /** 网络错误（请求未到达服务器或被中断）。 */
    public static final String NETWORK_ERROR = "NETWORK_ERROR";

    /** 调用被用户主动中止。 */
    public static final String ABORTED = "ABORTED";

    /** 请求非法（参数校验失败、结构错误）。 */
    public static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";
}
