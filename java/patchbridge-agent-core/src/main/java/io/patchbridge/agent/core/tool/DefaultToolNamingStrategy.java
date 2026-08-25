package io.patchbridge.agent.core.tool;

/**
 * 默认命名策略：local.xxx / mcp.&lt;serverKey&gt;.xxx。
 */
public class DefaultToolNamingStrategy implements ToolNamingStrategy {

    @Override
    public String localToolNamespace() {
        return "local";
    }

    @Override
    public String mcpToolNamespace(String serverKey) {
        return "mcp." + serverKey;
    }
}
