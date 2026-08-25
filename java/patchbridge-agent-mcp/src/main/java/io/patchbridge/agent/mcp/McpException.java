package io.patchbridge.agent.mcp;

/**
 * MCP 协议 / 网络层异常：连接失败、HTTP 非 2xx、JSON-RPC error、响应不可解析。
 * 工具的业务失败（result.isError=true）不走本异常，而是转换为 isError 的 ToolCallResult。
 */
public class McpException extends RuntimeException {

    public McpException(String message) {
        super(message);
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
    }
}
