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

    /**
     * Tool 定义/路由版本引用已过期（配置或定义发生语义变化）。
     * 浏览器应重新发现工具后以新版本重试，而不是原样重放旧调用。
     */
    public static final String TOOL_VERSION_MISMATCH = "TOOL_VERSION_MISMATCH";

    /** 模型网关失败（上游不可达、认证失败、流中断）。 */
    public static final String MODEL_FAILED = "MODEL_FAILED";

    /**
     * 模型输入超过“窗口 − 输出预留”预算（Browser 最终检查或服务端摘要请求超限）。
     * 浏览器应终止本次请求并保留完整历史，由用户调整输入后重新发起。
     */
    public static final String CONTEXT_WINDOW_EXCEEDED = "CONTEXT_WINDOW_EXCEEDED";

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
