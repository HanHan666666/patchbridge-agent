package io.patchbridge.agent.mcp;

/**
 * Admin 更新 MCP 配置时的凭据处理指令。
 *
 * <p>使用显式指令而不使用“凭据为空就自动保留”的隐式规则，
 * 避免 Admin 在表单丢字段时意外改变或清除认证边界。
 */
public enum McpCredentialUpdate {

    /** 继续使用已安全存储的凭据，不允许同时改变 auth.type。 */
    KEEP,

    /** 用请求中的完整凭据替换已存储值。 */
    REPLACE,

    /** 清除凭据；只能与 auth.type=none 一起使用。 */
    CLEAR
}
