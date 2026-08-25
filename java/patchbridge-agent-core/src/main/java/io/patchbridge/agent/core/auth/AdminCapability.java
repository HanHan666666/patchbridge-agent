package io.patchbridge.agent.core.auth;

/**
 * 管理端能力枚举：框架只描述访问意图，不定义角色、权限码或租户规则。
 * 宿主应用可把这些能力映射到现有 RBAC、ABAC 或组织权限体系。
 */
public enum AdminCapability {

    /** 访问管理控制台静态页面。 */
    CONSOLE,

    /** 查询模型与工具调用审计。 */
    AUDIT_READ,

    /** 查看 MCP Server 与工具状态。 */
    MCP_READ,

    /** 刷新或测试 MCP Server 连接。 */
    MCP_MANAGE
}
