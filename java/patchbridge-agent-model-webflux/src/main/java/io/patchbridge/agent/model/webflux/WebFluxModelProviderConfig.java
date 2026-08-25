package io.patchbridge.agent.model.webflux;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * WebFlux OpenAI-compatible 模型 Provider 的不可变配置。
 *
 * <p>连接池、代理、TLS 和超时属于宿主 HTTP 基础设施，统一通过传入的 WebClient
 * 配置；本对象只保存生成模型请求所必需、且不会在运行中变化的协议参数。
 */
public final class WebFluxModelProviderConfig {

    /** OpenAI-compatible 服务根地址，不包含 chat/completions 路径。 */
    private final String baseUrl;

    /** 服务端保存的上游凭据；为空表示目标网关不要求 Bearer Token。 */
    private final String apiKey;

    /** ModelRequest 未指定模型时使用的默认模型名称。 */
    private final String defaultModel;

    /** 经过校验的模型完成接口地址，避免每次请求重复拼接和解析。 */
    private final URI completionsUri;

    /**
     * 创建不可变配置。
     *
     * @param baseUrl OpenAI-compatible 服务根地址，必须是无用户信息、查询参数或片段的绝对 HTTP(S) 地址
     * @param apiKey Bearer Token，可为空
     * @param defaultModel 默认模型名称，不可为空
     */
    public WebFluxModelProviderConfig(String baseUrl, String apiKey, String defaultModel) {
        if (isBlank(baseUrl)) {
            throw new IllegalArgumentException("baseUrl 不可为空");
        }
        if (isBlank(defaultModel)) {
            throw new IllegalArgumentException("defaultModel 不可为空");
        }
        this.baseUrl = stripTrailingSlash(baseUrl.trim());
        this.apiKey = apiKey;
        this.defaultModel = defaultModel.trim();
        this.completionsUri = parseCompletionsUri(this.baseUrl);
    }

    /** 返回 OpenAI-compatible 服务根地址。 */
    public String getBaseUrl() {
        return baseUrl;
    }

    /** 返回 Bearer Token；目标网关无需凭据时可能为空。 */
    public String getApiKey() {
        return apiKey;
    }

    /** 返回请求未覆盖模型名时使用的默认值。 */
    public String getDefaultModel() {
        return defaultModel;
    }

    /** 返回已解析的 chat/completions 绝对地址。 */
    URI getCompletionsUri() {
        return completionsUri;
    }

    /** 校验并生成模型完成接口地址，尽早暴露错误配置。 */
    private static URI parseCompletionsUri(String baseUrl) {
        try {
            URI baseUri = new URI(baseUrl);
            String scheme = baseUri.getScheme();
            if (!baseUri.isAbsolute()
                    || baseUri.getHost() == null
                    || (!("http".equalsIgnoreCase(scheme)) && !("https".equalsIgnoreCase(scheme)))) {
                throw new IllegalArgumentException("baseUrl 必须是绝对 HTTP(S) 地址");
            }
            if (baseUri.getUserInfo() != null
                    || baseUri.getQuery() != null
                    || baseUri.getFragment() != null) {
                throw new IllegalArgumentException(
                        "baseUrl 不得包含用户信息、查询参数或片段");
            }
            return new URI(baseUrl + "/chat/completions");
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("baseUrl 不是合法 URI", e);
        }
    }

    /** 删除根地址末尾的斜杠，保证固定路径只拼接一次。 */
    private static String stripTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    /** 判断必填文本是否缺失。 */
    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
