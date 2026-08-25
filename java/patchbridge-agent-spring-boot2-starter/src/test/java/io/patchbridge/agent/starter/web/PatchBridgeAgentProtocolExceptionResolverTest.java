package io.patchbridge.agent.starter.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证独占命名空间内 405/406/415 的统一错误信封输出，
 * 以及命名空间外与非协议异常的零副作用交回行为。
 */
class PatchBridgeAgentProtocolExceptionResolverTest {

    private final PatchBridgeAgentProtocolExceptionResolver resolver =
            new PatchBridgeAgentProtocolExceptionResolver("/ai", new ObjectMapper());

    /** 命名空间内 405 返回统一信封并保留 Allow 元数据。 */
    @Test
    void methodNotAllowedInsideBasePathReturnsEnvelope() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/ai/tools");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ModelAndView view = resolver.resolveException(request, response, null,
                new HttpRequestMethodNotSupportedException(
                        "DELETE", new String[] {"GET", "POST"}));

        assertNotNull(view);
        assertEquals(405, response.getStatus());
        assertEquals("GET, POST", response.getHeader(HttpHeaders.ALLOW));
        Map<String, Object> body = readBody(response);
        assertEquals("INVALID_ARGUMENT", errorCode(body));
        assertEquals("请求方法不受支持", errorMessage(body));
    }

    /** 命名空间内 415 返回统一信封并保留服务端可接受媒体类型。 */
    @Test
    void unsupportedMediaTypeInsideBasePathReturnsEnvelope() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ai/tools/call");
        request.setContentType("text/plain");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ModelAndView view = resolver.resolveException(request, response, null,
                new HttpMediaTypeNotSupportedException(
                        MediaType.TEXT_PLAIN,
                        Collections.singletonList(MediaType.APPLICATION_JSON)));

        assertNotNull(view);
        assertEquals(415, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_VALUE, response.getHeader(HttpHeaders.ACCEPT));
        assertEquals("INVALID_ARGUMENT", errorCode(readBody(response)));
    }

    /** 命名空间内 406 返回统一 JSON 信封，覆盖原始 Accept 协商结果。 */
    @Test
    void notAcceptableInsideBasePathReturnsEnvelope() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ai/model/stream");
        request.addHeader("Accept", "text/html");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ModelAndView view = resolver.resolveException(request, response, null,
                new HttpMediaTypeNotAcceptableException(
                        Collections.singletonList(MediaType.TEXT_EVENT_STREAM)));

        assertNotNull(view);
        assertEquals(406, response.getStatus());
        // setCharacterEncoding 会在 Content-Type 上追加 charset，断言主类型即可
        assertTrue(response.getContentType().startsWith(MediaType.APPLICATION_JSON_VALUE));
        assertEquals("INVALID_ARGUMENT", errorCode(readBody(response)));
    }

    /** 命名空间外的同类异常必须零副作用交回宿主解析链。 */
    @Test
    void exceptionsOutsideBasePathAreNotHandled() {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/host/api");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ModelAndView view = resolver.resolveException(request, response, null,
                new HttpRequestMethodNotSupportedException(
                        "DELETE", new String[] {"GET"}));

        assertNull(view);
        assertEquals(200, response.getStatus());
    }

    /** 命名空间内的非协议异常同样交回宿主链，不扩大解析范围。 */
    @Test
    void unrelatedExceptionInsideBasePathIsNotHandled() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ai/tools");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ModelAndView view = resolver.resolveException(
                request, response, null, new IllegalStateException("业务异常"));

        assertNull(view);
        assertEquals(200, response.getStatus());
    }

    /** 多值 Accept 头在 415 场景下按 Spring 约定合并输出。 */
    @Test
    void supportedMediaTypesJoinedInAcceptHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ai/tools/call");
        MockHttpServletResponse response = new MockHttpServletResponse();
        List<MediaType> supported = Arrays.asList(
                MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM);

        resolver.resolveException(request, response, null,
                new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, supported));

        String accept = response.getHeader(HttpHeaders.ACCEPT);
        assertNotNull(accept);
        assertFalse(accept.isEmpty());
        // 断言合并存在即可，具体顺序属于 Spring 序列化实现细节
        assertEquals(2, accept.split(",").length);
    }

    /** 解析响应体中的统一错误信封。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> readBody(MockHttpServletResponse response) throws Exception {
        return new ObjectMapper().readValue(
                response.getContentAsByteArray(), Map.class);
    }

    /** 读取信封内错误码。 */
    private static String errorCode(Map<String, Object> body) {
        return (String) ((Map<String, Object>) body.get("error")).get("code");
    }

    /** 读取信封内稳定消息。 */
    private static String errorMessage(Map<String, Object> body) {
        return (String) ((Map<String, Object>) body.get("error")).get("message");
    }
}
