package io.patchbridge.agent.starter.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.patchbridge.agent.core.error.AgentErrorCode;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Starter 独占命名空间的 HTTP 协议异常解析器。
 *
 * <p>405、映射阶段的 406/415 可能在 HandlerMethod 选中前产生，无法由按 Controller
 * 类型限定的 ControllerAdvice 捕获。本解析器只在启动期所有权校验后的 base-path 内工作，
 * 因而不会改写命名空间外的宿主 Controller 响应。406 场景仍直接返回 JSON，是统一公开错误
 * 契约对原始 Accept 协商的明确覆盖。
 */
public final class PatchBridgeAgentProtocolExceptionResolver
        implements HandlerExceptionResolver, Ordered {

    /** 在 Spring 默认异常解析器前处理三类稳定协议错误。 */
    private static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 100;
    /** 405 对外稳定消息。 */
    private static final String METHOD_NOT_ALLOWED_MESSAGE = "请求方法不受支持";
    /** 415 对外稳定消息。 */
    private static final String MEDIA_TYPE_NOT_SUPPORTED_MESSAGE = "请求媒体类型不受支持";
    /** 406 对外稳定消息。 */
    private static final String MEDIA_TYPE_NOT_ACCEPTABLE_MESSAGE = "请求的响应媒体类型不可接受";

    /** 独占路径边界。 */
    private final PatchBridgeAgentPathScope pathScope;
    /** 复用宿主 Jackson 配置输出统一 JSON 信封。 */
    private final ObjectMapper objectMapper;

    /** 创建只服务于规范化 base-path 的协议异常解析器。 */
    public PatchBridgeAgentProtocolExceptionResolver(String basePath, ObjectMapper objectMapper) {
        this.pathScope = new PatchBridgeAgentPathScope(basePath);
        this.objectMapper = objectMapper;
    }

    /** 返回高于 Spring 默认解析器的固定顺序。 */
    @Override
    public int getOrder() {
        return ORDER;
    }

    /**
     * 只处理独占命名空间中的 405、415 与 406；其余请求零副作用地交回宿主解析链。
     */
    @Override
    public ModelAndView resolveException(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception) {
        ProtocolError protocolError = ProtocolError.from(exception);
        if (protocolError == null || !pathScope.contains(request)) {
            return null;
        }

        byte[] body = serialize(protocolError.message);
        applyProtocolHeaders(response, exception);
        response.setStatus(protocolError.status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setContentLength(body.length);
        try {
            response.getOutputStream().write(body);
        } catch (IOException e) {
            throw new IllegalStateException("写出 PatchBridge 协议错误响应失败", e);
        }
        return new ModelAndView();
    }

    /** 序列化既有统一错误信封；序列化失败属于框架配置错误，不能静默降级。 */
    private byte[] serialize(String message) {
        Map<String, Object> body = PatchBridgeAgentExceptionHandler.errorBody(
                AgentErrorCode.INVALID_ARGUMENT, message, null);
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化 PatchBridge 协议错误响应失败", e);
        }
    }

    /** 保留 Spring 默认协议响应中有意义的 Allow 与 Accept 元数据。 */
    private static void applyProtocolHeaders(
            HttpServletResponse response, Exception exception) {
        if (exception instanceof HttpRequestMethodNotSupportedException) {
            String[] supported = ((HttpRequestMethodNotSupportedException) exception)
                    .getSupportedMethods();
            if (supported != null && supported.length > 0) {
                response.setHeader(HttpHeaders.ALLOW, String.join(", ", supported));
            }
            return;
        }
        if (exception instanceof HttpMediaTypeNotSupportedException) {
            List<MediaType> supported = ((HttpMediaTypeNotSupportedException) exception)
                    .getSupportedMediaTypes();
            if (!supported.isEmpty()) {
                response.setHeader(HttpHeaders.ACCEPT, MediaType.toString(supported));
            }
        }
    }

    /** 三类框架异常到稳定状态与消息的封闭映射。 */
    private static final class ProtocolError {
        /** 必须保留的 HTTP 状态。 */
        private final HttpStatus status;
        /** 不暴露 Spring 内部匹配细节的稳定消息。 */
        private final String message;

        /** 创建固定协议错误。 */
        private ProtocolError(HttpStatus status, String message) {
            this.status = status;
            this.message = message;
        }

        /** 识别受支持异常；其他异常返回 null 交回原解析链。 */
        private static ProtocolError from(Exception exception) {
            if (exception instanceof HttpRequestMethodNotSupportedException) {
                return new ProtocolError(HttpStatus.METHOD_NOT_ALLOWED, METHOD_NOT_ALLOWED_MESSAGE);
            }
            if (exception instanceof HttpMediaTypeNotSupportedException) {
                return new ProtocolError(
                        HttpStatus.UNSUPPORTED_MEDIA_TYPE, MEDIA_TYPE_NOT_SUPPORTED_MESSAGE);
            }
            if (exception instanceof HttpMediaTypeNotAcceptableException) {
                return new ProtocolError(
                        HttpStatus.NOT_ACCEPTABLE, MEDIA_TYPE_NOT_ACCEPTABLE_MESSAGE);
            }
            return null;
        }
    }
}
