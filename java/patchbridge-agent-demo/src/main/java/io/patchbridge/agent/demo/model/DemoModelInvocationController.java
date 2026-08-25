package io.patchbridge.agent.demo.model;

import io.patchbridge.agent.core.auth.CurrentUserProvider;
import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.invocation.ModelInvocation;
import io.patchbridge.agent.core.model.ImageSource;
import io.patchbridge.agent.core.model.ModelResponse;
import io.patchbridge.agent.starter.web.CurrentUserResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Demo 企业应用自己的模型调用入口。
 *
 * <p>端点位于 {@code /demo-api}，用于观察 Java Service 调用真实模型的方式；它不是 Starter
 * 新增的框架远程 API。两个已认证端点都先通过宿主 {@link CurrentUserProvider}
 * 获取可信用户，再把用户和随机 traceId 显式交给同 JVM 模型门面；框架不会在异步线程中猜测
 * Servlet 或 SecurityContext。文本端点展示阻塞超时，图片端点用 {@link DeferredResult}
 * 绑定容器异步生命周期与上游取消；二者都不默认保存任何请求或响应。
 */
@RestController
@RequestMapping("/demo-api/model-invocations")
public class DemoModelInvocationController {

    /** Demo 文本同步入口允许占用请求线程的最大时间。 */
    private static final Duration TEXT_TIMEOUT = Duration.ofSeconds(20);

    /** Demo 多模态异步入口的资源预算；图片编码与推理通常比纯文本更慢。 */
    private static final Duration IMAGE_TIMEOUT = Duration.ofSeconds(60);

    /** 复用唯一的 Demo 业务 Service，避免 Controller 复制模型请求组装规则。 */
    private final DemoModelInvocationService invocationService;

    /** 统一校验宿主提供的当前登录用户，避免 Demo 复制身份有效性规则。 */
    private final CurrentUserResolver currentUser;

    /**
     * 创建只负责 HTTP DTO 映射和同步/异步选择的 Demo Controller。
     *
     * @param invocationService 后端单次模型调用业务服务
     * @param userProvider 宿主安全体系的当前用户解析端口
     */
    public DemoModelInvocationController(
            DemoModelInvocationService invocationService,
            CurrentUserProvider userProvider) {
        this.invocationService = invocationService;
        this.currentUser = new CurrentUserResolver(userProvider);
    }

    /**
     * 用显式 20 秒超时阻塞等待一次文本模型调用。
     *
     * @param request Demo 文本业务请求
     * @return 厂商中立的完整模型响应
     */
    @PostMapping("/text")
    public ModelResponse invokeText(@RequestBody TextInvocationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不可为空");
        }
        return invocationService.invokeText(request.getContent(), requestContext())
                .await(TEXT_TIMEOUT);
    }

    /**
     * 以 Spring MVC {@link DeferredResult} 返回一次图片模型调用，不阻塞 Servlet 线程。
     *
     * @param request Demo 图片业务请求；URL 与 Base64 来源必须且只能提供一种
     * @return 最终完整模型响应的异步结果
     */
    @PostMapping("/image")
    public DeferredResult<ModelResponse> invokeImage(
            @RequestBody ImageInvocationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不可为空");
        }
        ImageSource imageSource = request.toImageSource();
        ModelInvocation invocation = invocationService.invokeImage(
                request.getPrompt(), imageSource, requestContext());
        return new ModelInvocationDeferredResult(invocation, IMAGE_TIMEOUT);
    }

    /**
     * 在 Servlet 请求线程中固化可信身份和新链路标识。
     *
     * <p>上下文必须在进入异步阶段前构造，否则后续回调所在线程不一定保留宿主的
     * SecurityContext。
     *
     * @return 仅携带当前用户和本次 traceId 的显式请求上下文
     */
    private AiRequestContext requestContext() {
        return new AiRequestContext(
                currentUser.requiredUser(),
                UUID.randomUUID().toString(),
                null,
                null,
                null);
    }

    /**
     * 将 Demo 入参错误限定为当前 Controller 的 400 响应，不向宿主注册全局异常处理器。
     *
     * @param error 业务请求构造阶段发现的明确参数错误
     * @return 仅包含错误文本的最小响应
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidArgument(IllegalArgumentException error) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Collections.singletonMap("message", error.getMessage()));
    }

    /** Demo 文本入口的业务请求，不属于框架公共模型契约。 */
    public static final class TextInvocationRequest {

        /** 需要进行概括和风险提示的业务文本。 */
        private String content;

        /** Jackson 反序列化使用的无参构造器。 */
        public TextInvocationRequest() {
        }

        /** @return 当前业务文本 */
        public String getContent() {
            return content;
        }

        /** @param content 需要交给模型的业务文本 */
        public void setContent(String content) {
            this.content = content;
        }
    }

    /** Demo 图片入口的业务请求，可在 URL 与 Base64 两种明确来源中选择一种。 */
    public static final class ImageInvocationRequest {

        /** 希望模型围绕图片回答的业务问题。 */
        private String prompt;

        /** 远端可访问的图片 URL；与 Base64 字段互斥。 */
        private String imageUrl;

        /** Base64 图片媒体类型，例如 image/png；必须与 imageBase64 同时提供。 */
        private String mediaType;

        /** 不含 data URL 前缀的 Base64 图片数据；必须与 mediaType 同时提供。 */
        private String imageBase64;

        /** Jackson 反序列化使用的无参构造器。 */
        public ImageInvocationRequest() {
        }

        /** @return 图片相关的业务问题 */
        public String getPrompt() {
            return prompt;
        }

        /** @param prompt 图片相关的业务问题 */
        public void setPrompt(String prompt) {
            this.prompt = prompt;
        }

        /** @return 可选图片 URL */
        public String getImageUrl() {
            return imageUrl;
        }

        /** @param imageUrl 远端可访问图片 URL */
        public void setImageUrl(String imageUrl) {
            this.imageUrl = imageUrl;
        }

        /** @return 可选 Base64 图片媒体类型 */
        public String getMediaType() {
            return mediaType;
        }

        /** @param mediaType Base64 图片媒体类型 */
        public void setMediaType(String mediaType) {
            this.mediaType = mediaType;
        }

        /** @return 可选 Base64 图片数据 */
        public String getImageBase64() {
            return imageBase64;
        }

        /** @param imageBase64 不含 data URL 前缀的 Base64 图片数据 */
        public void setImageBase64(String imageBase64) {
            this.imageBase64 = imageBase64;
        }

        /**
         * 把 Demo HTTP DTO 映射到 Core 唯一的图片来源类型。
         *
         * @return 已校验为 URL 或 Base64 的图片来源
         */
        private ImageSource toImageSource() {
            boolean hasUrl = hasText(imageUrl);
            boolean hasMediaType = hasText(mediaType);
            boolean hasBase64 = hasText(imageBase64);
            if (hasUrl && !hasMediaType && !hasBase64) {
                return ImageSource.url(imageUrl);
            }
            if (!hasUrl && hasMediaType && hasBase64) {
                return ImageSource.base64(mediaType, imageBase64);
            }
            throw new IllegalArgumentException(
                    "图片必须且只能提供 imageUrl，或同时提供 mediaType 与 imageBase64");
        }

        /**
         * 判断可选图片字段是否携带有效文本，不对原值做隐式修剪或改写。
         *
         * @param value 待判断字段
         * @return 是否包含非空白文本
         */
        private static boolean hasText(String value) {
            return value != null && !value.trim().isEmpty();
        }
    }
}
