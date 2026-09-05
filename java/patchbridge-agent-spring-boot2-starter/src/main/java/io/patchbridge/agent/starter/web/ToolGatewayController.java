package io.patchbridge.agent.starter.web;

import io.patchbridge.agent.core.audit.AuditInvocationType;
import io.patchbridge.agent.core.auth.CurrentUserProvider;
import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.error.AgentErrorCode;
import io.patchbridge.agent.core.error.ToolAccessDeniedException;
import io.patchbridge.agent.core.error.ToolExecutionException;
import io.patchbridge.agent.core.error.ToolVersionMismatchException;
import io.patchbridge.agent.core.invocation.ToolInvocationPipeline;
import io.patchbridge.agent.core.tool.ToolCallResult;
import io.patchbridge.agent.core.tool.ToolContent;
import io.patchbridge.agent.core.tool.ToolDefinition;
import io.patchbridge.agent.core.tool.ToolRegistry;
import io.patchbridge.agent.core.tool.ToolSource;
import io.patchbridge.agent.core.user.UserContext;
import io.patchbridge.agent.starter.audit.AuditRecorder;
import io.patchbridge.agent.starter.web.dto.ToolCallRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Unified Tool Gateway：浏览器只面对这两个端点，不关心 Tool 来自哪里。
 *
 * <p>GET /ai/tools —— 按当前用户过滤后的可发现列表；
 * POST /ai/tools/call —— 每次调用重新执行 canInvoke（浏览器不是安全边界）。
 * Tool 默认不自动重试；每次调用生成 traceId / requestId / toolCallId 供审计串联。
 */
@RestController
@ConditionalOnProperty(prefix = "patchbridge-agent", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@RequestMapping("${patchbridge-agent.base-path:/ai}")
public class ToolGatewayController {

    private final ToolRegistry toolRegistry;
    private final ToolInvocationPipeline invocationPipeline;
    private final CurrentUserResolver currentUser;
    private final AuditRecorder audit;

    public ToolGatewayController(ToolRegistry toolRegistry,
                                 ToolInvocationPipeline invocationPipeline,
                                 CurrentUserProvider userProvider, AuditRecorder audit) {
        this.toolRegistry = toolRegistry;
        this.invocationPipeline = invocationPipeline;
        this.currentUser = new CurrentUserResolver(userProvider);
        this.audit = audit;
    }

    @GetMapping("/tools")
    public Map<String, Object> tools() {
        UserContext user = currentUser.requiredUser();
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("tools", toolRegistry.list(user));
        return body;
    }

    @PostMapping("/tools/call")
    public ResponseEntity<Map<String, Object>> call(@RequestBody ToolCallRequest request) {
        UserContext user = currentUser.requiredUser();
        // 身份解析之后、业务查找之前执行严格契约校验：
        // 未通过校验的请求不进入 Tool 目录和审计统计。
        request.validate();
        String traceId = blankToRandom(request.getTraceId());
        AiRequestContext context = new AiRequestContext(user, traceId,
                request.getRequestId(), request.getToolCallId(), request.getConversationId());

        ToolDefinition tool = toolRegistry.find(request.getName());
        long start = System.currentTimeMillis();
        if (tool == null) {
            audit.record(traceId, request.getConversationId(), user, AuditInvocationType.TOOL,
                    "UNKNOWN", request.getName(), false, AgentErrorCode.TOOL_FAILED,
                    "Tool 不存在", System.currentTimeMillis() - start, null, null);
            return error(HttpStatus.NOT_FOUND, AgentErrorCode.TOOL_FAILED,
                    "Tool 不存在: " + request.getName());
        }

        try {
            ToolCallResult result = invocationPipeline.invoke(
                    request.getName(), request.getVersion(), request.getArguments(), context);
            String contentText = toText(result.getContent());
            boolean success = !result.isError();
            auditTool(traceId, request, user, tool, success,
                    success ? null : AgentErrorCode.TOOL_FAILED,
                    success ? null : contentText,
                    System.currentTimeMillis() - start, request.getArguments(), contentText);
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("toolCallId", request.getToolCallId());
            body.put("content", contentText);
            body.put("isError", result.isError());
            return ResponseEntity.ok(body);
        } catch (ToolAccessDeniedException e) {
            // 权限拒绝属于安全事件，必须留下审计痕迹
            auditTool(traceId, request, user, tool, false, AgentErrorCode.TOOL_FORBIDDEN,
                    e.getMessage(), System.currentTimeMillis() - start, request.getArguments(), null);
            return error(HttpStatus.FORBIDDEN, AgentErrorCode.TOOL_FORBIDDEN, e.getMessage());
        } catch (ToolVersionMismatchException e) {
            // 过期版本引用是客户端状态冲突：明确 409 让浏览器重新发现工具，绝不放行旧调用。
            auditTool(traceId, request, user, tool, false, AgentErrorCode.TOOL_VERSION_MISMATCH,
                    e.getMessage(), System.currentTimeMillis() - start, request.getArguments(), null);
            return error(HttpStatus.CONFLICT, AgentErrorCode.TOOL_VERSION_MISMATCH, e.getMessage());
        } catch (ToolExecutionException e) {
            auditTool(traceId, request, user, tool, false, AgentErrorCode.TOOL_FAILED,
                    e.getMessage(), System.currentTimeMillis() - start, request.getArguments(), null);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, AgentErrorCode.TOOL_FAILED, e.getMessage());
        }
    }

    /**
     * content 块数组 → 纯文本：HTTP 网关对浏览器的输出契约是字符串
     * （前端 ToolCallResult.content 与模型 tool 消息都只消费文本）。
     * MCP 结构化块属于内部语义，不透出到网关响应。
     */
    private static String toText(java.util.List<ToolContent> contents) {
        StringBuilder text = new StringBuilder();
        for (ToolContent content : contents) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(content.getText());
        }
        return text.toString();
    }

    private void auditTool(String traceId, ToolCallRequest request, UserContext user,
                           ToolDefinition tool, boolean success, String errorCode, String errorMessage,
                           long durationMs, Object arguments, String resultText) {
        AuditInvocationType type = tool.getSource() == ToolSource.MCP
                ? AuditInvocationType.MCP_TOOL : AuditInvocationType.TOOL;
        audit.record(traceId, request.getConversationId(), user, type,
                tool.getSource().name(), tool.getName(), success, errorCode, errorMessage,
                durationMs, arguments, resultText);
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        Map<String, Object> error = new LinkedHashMap<String, Object>();
        error.put("code", code);
        error.put("message", message);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("error", error);
        return ResponseEntity.status(status).body(body);
    }

    private static String blankToRandom(String value) {
        return value == null || value.trim().isEmpty()
                ? UUID.randomUUID().toString().replace("-", "") : value;
    }
}
