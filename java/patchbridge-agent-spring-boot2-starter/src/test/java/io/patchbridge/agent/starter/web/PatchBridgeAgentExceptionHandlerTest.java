package io.patchbridge.agent.starter.web;

import io.patchbridge.agent.mcp.McpException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.ControllerAdviceBean;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Starter 错误模型的作用域，防止自动装配后接管宿主 Controller 异常。
 */
class PatchBridgeAgentExceptionHandlerTest {

    /** assignableTypes 必须覆盖框架端点，但明确排除宿主业务端点。 */
    @Test
    void adviceOnlyAppliesToStarterControllers() {
        ControllerAdviceBean advice = new ControllerAdviceBean(
                new PatchBridgeAgentExceptionHandler());

        assertTrue(advice.isApplicableToBeanType(ToolGatewayController.class));
        assertTrue(advice.isApplicableToBeanType(ConversationController.class));
        assertFalse(advice.isApplicableToBeanType(HostBusinessController.class));
    }

    /** MCP 刷新失败应保持 Starter 统一错误结构与稳定错误码。 */
    @Test
    @SuppressWarnings("unchecked")
    void mcpFailureUsesScopedGatewayErrorModel() {
        PatchBridgeAgentExceptionHandler handler = new PatchBridgeAgentExceptionHandler();

        ResponseEntity<Map<String, Object>> response = handler.mcpFailed(
                new McpException("remote unavailable"));

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        Map<String, Object> error = (Map<String, Object>) response.getBody().get("error");
        assertEquals("MCP_FAILED", error.get("code"));
        assertEquals("remote unavailable", error.get("message"));
    }

    /** 模拟与 Starter 无继承关系的宿主业务 Controller。 */
    @RestController
    private static final class HostBusinessController {
    }

    /** 请求体解析失败必须映射为 400 标准错误体，且不携带底层解析细节。 */
    @Test
    @SuppressWarnings("unchecked")
    void unreadableBodyMappedToInvalidArgumentWithoutParserDetails() {
        PatchBridgeAgentExceptionHandler handler = new PatchBridgeAgentExceptionHandler();

        ResponseEntity<Map<String, Object>> response = handler.unreadableMessage(
                new org.springframework.http.converter.HttpMessageNotReadableException(
                        "boom: Unexpected character 'a' at line 1",
                        (org.springframework.http.HttpInputMessage) null));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Map<String, Object> error = (Map<String, Object>) response.getBody().get("error");
        assertEquals("INVALID_ARGUMENT", error.get("code"));
        assertFalse(error.get("message").toString().contains("Unexpected character"));
    }

    /** 缺少必填请求参数返回 400，并在消息中点名缺失参数。 */
    @Test
    @SuppressWarnings("unchecked")
    void missingParameterMappedToInvalidArgument() {
        PatchBridgeAgentExceptionHandler handler = new PatchBridgeAgentExceptionHandler();

        ResponseEntity<Map<String, Object>> response = handler.missingParameter(
                new org.springframework.web.bind.MissingServletRequestParameterException(
                        "revision", "long"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Map<String, Object> error = (Map<String, Object>) response.getBody().get("error");
        assertEquals("INVALID_ARGUMENT", error.get("code"));
        assertTrue(error.get("message").toString().contains("revision"));
    }

    /** 参数类型不匹配返回 400，只点名参数名，不暴露内部转换堆栈。 */
    @Test
    @SuppressWarnings("unchecked")
    void typeMismatchMappedToInvalidArgument() throws Exception {
        PatchBridgeAgentExceptionHandler handler = new PatchBridgeAgentExceptionHandler();
        org.springframework.core.MethodParameter parameter = new org.springframework.core.MethodParameter(
                PatchBridgeAgentExceptionHandler.class.getDeclaredMethod(
                        "invalidArgument", IllegalArgumentException.class), -1);
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException exception =
                new org.springframework.web.method.annotation.MethodArgumentTypeMismatchException(
                        null, null, "revision", parameter, null);

        ResponseEntity<Map<String, Object>> response = handler.typeMismatch(exception);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Map<String, Object> error = (Map<String, Object>) response.getBody().get("error");
        assertEquals("INVALID_ARGUMENT", error.get("code"));
        assertTrue(error.get("message").toString().contains("revision"));
    }
}
