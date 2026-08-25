package io.patchbridge.agent.core.tool;

/**
 * Tool 来源。进入审计与 Admin 展示，用于回答“这个 Tool 来自本地还是 MCP”。
 */
public enum ToolSource {

    /** 本地 @AiTool（Native Tool）。 */
    LOCAL,

    /** 由现有 REST API 转换而来的 Tool（@AiExpose，v0.2 范围）。 */
    OPENAPI,

    /** 由后端 MCP Gateway 代理的远程 MCP Tool。 */
    MCP
}
