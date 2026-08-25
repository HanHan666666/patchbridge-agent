package io.patchbridge.agent.mcp;

import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.tool.ToolCallResult;

import java.util.List;
import java.util.Map;

/**
 * 极窄的远程 MCP Client SPI（设计文档第 22 节：只做协议适配，不做基础设施）。
 *
 * <p>v0.1 只覆盖 Streamable HTTP 的 tools/list 与 tools/call；
 * 企业需要自定义传输（代理、超时、内部网关）时可整体替换实现。
 */
public interface RemoteMcpClient {

    /**
     * 拉取远程 Server 的 Tool 列表。
     *
     * @return 远程 Tool 定义与服务端声明的缓存 TTL（毫秒，无则 0）
     */
    ListToolsResult listTools(McpServerConfig config);

    /**
     * 调用远程 Tool。业务失败以 isError 的 ToolCallResult 返回，
     * 协议 / 网络失败抛 {@link McpException}。
     */
    ToolCallResult callTool(McpServerConfig config, String remoteToolName,
                            Map<String, Object> arguments, AiRequestContext context);

    /** tools/list 结果。 */
    final class ListToolsResult {
        private final List<RemoteToolDefinition> tools;
        private final long serverTtlMs;

        public ListToolsResult(List<RemoteToolDefinition> tools, long serverTtlMs) {
            this.tools = tools;
            this.serverTtlMs = serverTtlMs;
        }

        public List<RemoteToolDefinition> getTools() { return tools; }

        /** 服务端通过 ListToolsResult.ttlMs 声明的缓存时长；0 表示未声明。 */
        public long getServerTtlMs() { return serverTtlMs; }
    }
}
