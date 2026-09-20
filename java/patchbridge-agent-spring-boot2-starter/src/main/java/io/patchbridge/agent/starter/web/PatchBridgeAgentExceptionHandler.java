package io.patchbridge.agent.starter.web;

import io.patchbridge.agent.core.conversation.ConversationConflictException;
import io.patchbridge.agent.core.conversation.ConversationNotFoundException;
import io.patchbridge.agent.core.compaction.ContextWindowExceededException;
import io.patchbridge.agent.core.error.AgentErrorCode;
import io.patchbridge.agent.core.error.ModelGatewayException;
import io.patchbridge.agent.core.error.ToolAccessDeniedException;
import io.patchbridge.agent.core.error.ToolExecutionException;
import io.patchbridge.agent.core.error.ToolVersionMismatchException;
import io.patchbridge.agent.mcp.McpConfigurationConflictException;
import io.patchbridge.agent.mcp.McpConfigurationReadOnlyException;
import io.patchbridge.agent.mcp.McpException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Starter 自有端点的统一错误模型：{ error: { code, message } }。
 * 浏览器只依据 code 决定展示与跳转（登录 / 冲突刷新 / 重试），不解析 HTTP 细节。
 * assignableTypes 明确限定作用域，避免 Starter 的异常映射截住宿主业务 Controller。
 */
@RestControllerAdvice(assignableTypes = {
        ToolGatewayController.class,
        ModelStreamController.class,
        ConversationController.class,
        ContextCompactionController.class,
        AdminApiController.class,
        McpAdminController.class
})
@ConditionalOnProperty(prefix = "patchbridge-agent", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class PatchBridgeAgentExceptionHandler {

    /** 未登录或登录态失效时要求 Browser 回到宿主登录流程。 */
    @ExceptionHandler(AuthRequiredException.class)
    public ResponseEntity<Map<String, Object>> authRequired(AuthRequiredException e) {
        return error(HttpStatus.UNAUTHORIZED, AgentErrorCode.AUTH_REQUIRED, e.getMessage(), null);
    }

    /** Tool 二次授权失败使用稳定 403，不与执行异常混淆。 */
    @ExceptionHandler(ToolAccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> forbidden(ToolAccessDeniedException e) {
        return error(HttpStatus.FORBIDDEN, AgentErrorCode.TOOL_FORBIDDEN, e.getMessage(), null);
    }

    /** 过期版本引用使用稳定 409：提示重新发现工具，而不是原样重放旧调用。 */
    @ExceptionHandler(ToolVersionMismatchException.class)
    public ResponseEntity<Map<String, Object>> toolVersionMismatch(ToolVersionMismatchException e) {
        return error(HttpStatus.CONFLICT, AgentErrorCode.TOOL_VERSION_MISMATCH, e.getMessage(), null);
    }

    /** Tool 业务执行失败使用稳定错误码，具体异常已在执行端完成脱敏。 */
    @ExceptionHandler(ToolExecutionException.class)
    public ResponseEntity<Map<String, Object>> toolFailed(ToolExecutionException e) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, AgentErrorCode.TOOL_FAILED,
                e.getMessage(), null);
    }

    /** 当前 owner 范围内会话不存在时返回稳定 404，不暴露其他 owner 的存在性。 */
    @ExceptionHandler(ConversationNotFoundException.class)
    public ResponseEntity<Map<String, Object>> conversationNotFound(
            ConversationNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, AgentErrorCode.CONVERSATION_NOT_FOUND,
                e.getMessage(), null);
    }

    /** 会话 revision 冲突要求客户端重新读取后再决策，禁止静默覆盖。 */
    @ExceptionHandler(ConversationConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(ConversationConflictException e) {
        Map<String, Object> extra = new LinkedHashMap<String, Object>();
        extra.put("currentRevision", e.getCurrentRevision());
        return error(HttpStatus.CONFLICT, AgentErrorCode.CONVERSATION_CONFLICT,
                "会话已在其他窗口更新，请刷新后重试", extra);
    }

    /** MCP 配置乐观锁或同名冲突，要求 Admin 重新读取后决策。 */
    @ExceptionHandler(McpConfigurationConflictException.class)
    public ResponseEntity<Map<String, Object>> mcpConfigurationConflict(
            McpConfigurationConflictException e) {
        return error(HttpStatus.CONFLICT, "MCP_CONFIG_CONFLICT", e.getMessage(), null);
    }

    /** properties 是显式只读配置源，不创建内存替代路径。 */
    @ExceptionHandler(McpConfigurationReadOnlyException.class)
    public ResponseEntity<Map<String, Object>> mcpConfigurationReadOnly(
            McpConfigurationReadOnlyException e) {
        return error(HttpStatus.CONFLICT, "MCP_CONFIG_READ_ONLY", e.getMessage(), null);
    }

    /** MCP 远程协议或连接失败使用稳定网关错误，不落入宿主默认错误页。 */
    @ExceptionHandler(McpException.class)
    public ResponseEntity<Map<String, Object>> mcpFailed(McpException e) {
        return error(HttpStatus.BAD_GATEWAY, "MCP_FAILED", e.getMessage(), null);
    }

    /** 模型网关同步失败使用稳定 502；SSE 建立后的错误由流内 error 事件承载。 */
    @ExceptionHandler(ModelGatewayException.class)
    public ResponseEntity<Map<String, Object>> modelFailed(ModelGatewayException e) {
        return error(HttpStatus.BAD_GATEWAY, AgentErrorCode.MODEL_FAILED, e.getMessage(), null);
    }

    /**
     * 摘要请求超过窗口预算使用稳定 413，必须先映射再落到通用 MODEL_FAILED 分支：
     * 重试同样的请求必然再次失败，浏览器需要可区分的“调整输入”语义。
     */
    @ExceptionHandler(ContextWindowExceededException.class)
    public ResponseEntity<Map<String, Object>> contextWindowExceeded(
            ContextWindowExceededException e) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, AgentErrorCode.CONTEXT_WINDOW_EXCEEDED,
                e.getMessage(), null);
    }

    /** 所有领域与 DTO 参数校验失败统一映射为 400。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalidArgument(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, AgentErrorCode.INVALID_ARGUMENT, e.getMessage(), null);
    }

    /**
     * 请求体不可读（非法 JSON、字段类型错配）转为 400 标准错误体。
     *
     * <p>底层异常消息可能携带 Jackson 解析细节甚至片段内容，属于实现细节，
     * 不回显给浏览器，只保留稳定的 INVALID_ARGUMENT 语义。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadableMessage(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, AgentErrorCode.INVALID_ARGUMENT,
                "请求体不是合法 JSON 或字段形状不符合契约", null);
    }

    /** 缺少必填请求参数（如 MCP Admin 删除的 revision）按参数错误返回 400。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> missingParameter(
            MissingServletRequestParameterException e) {
        return error(HttpStatus.BAD_REQUEST, AgentErrorCode.INVALID_ARGUMENT,
                "缺少必填请求参数: " + e.getParameterName(), null);
    }

    /** 路径或查询参数类型不匹配按参数错误返回 400，不暴露内部转换堆栈。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> typeMismatch(
            MethodArgumentTypeMismatchException e) {
        return error(HttpStatus.BAD_REQUEST, AgentErrorCode.INVALID_ARGUMENT,
                "请求参数类型不匹配: " + e.getName(), null);
    }

    /** 组装所有 Starter Controller 共用的稳定错误响应。 */
    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code,
                                                             String message, Map<String, Object> extra) {
        return ResponseEntity.status(status).body(errorBody(code, message, extra));
    }

    /**
     * 构造 ControllerAdvice 与底层协议异常解析器共用的错误信封。
     * 包级可见性只服务于同一 Web Adapter，避免两条 Spring 异常路径复制 JSON 契约。
     */
    static Map<String, Object> errorBody(
            String code, String message, Map<String, Object> extra) {
        Map<String, Object> error = new LinkedHashMap<String, Object>();
        error.put("code", code);
        error.put("message", message);
        if (extra != null) {
            error.putAll(extra);
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("error", error);
        return body;
    }
}
