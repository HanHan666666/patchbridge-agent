package io.patchbridge.agent.core.tool;

/**
 * Tool 命名空间策略。
 *
 * <p>命名规则从第一版就必须稳定：Tool 全名会进入 LLM Schema、RBAC、审计、
 * Conversation 历史与审批策略，后改名意味着上述链路全部断裂。
 *
 * <p>契约：全名 = namespace + "." + 本地名；namespace 本身可含点
 * （如 mcp.inventory），Registry 按最长前缀路由，因此禁止出现
 * “一个 namespace 是另一个的前缀且都注册 Tool”的配置。
 */
public interface ToolNamingStrategy {

    /** 本地 @AiTool 的命名空间（默认 local）。 */
    String localToolNamespace();

    /** 某个远程 MCP Server 的命名空间（默认 mcp.&lt;serverKey&gt;）。 */
    String mcpToolNamespace(String serverKey);
}
