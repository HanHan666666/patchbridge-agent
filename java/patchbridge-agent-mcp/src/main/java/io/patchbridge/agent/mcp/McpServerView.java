package io.patchbridge.agent.mcp;

/**
 * MCP Server 运行状态视图（Admin 管理页数据）。
 * v0.1 配置来自 YAML，后台只读展示 + 手工刷新 + 连接测试（设计文档第 25 节）。
 */
public final class McpServerView {

    private final String name;
    private final String url;
    private final String transport;
    private final boolean enabled;
    private final String status;
    private final int toolCount;
    private final Long lastRefreshAt;
    private final long lastLatencyMs;
    private final String lastError;

    public McpServerView(String name, String url, String transport, boolean enabled,
                         String status, int toolCount, Long lastRefreshAt,
                         long lastLatencyMs, String lastError) {
        this.name = name;
        this.url = url;
        this.transport = transport;
        this.enabled = enabled;
        this.status = status;
        this.toolCount = toolCount;
        this.lastRefreshAt = lastRefreshAt;
        this.lastLatencyMs = lastLatencyMs;
        this.lastError = lastError;
    }

    public String getName() { return name; }
    public String getUrl() { return url; }
    public String getTransport() { return transport; }
    public boolean isEnabled() { return enabled; }
    /** UP / DOWN。 */
    public String getStatus() { return status; }
    public int getToolCount() { return toolCount; }
    public Long getLastRefreshAt() { return lastRefreshAt; }
    public long getLastLatencyMs() { return lastLatencyMs; }
    public String getLastError() { return lastError; }
}
