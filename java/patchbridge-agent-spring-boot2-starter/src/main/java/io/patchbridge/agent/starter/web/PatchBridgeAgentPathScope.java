package io.patchbridge.agent.starter.web;

import org.springframework.web.util.UrlPathHelper;

import javax.servlet.http.HttpServletRequest;

/**
 * 规范化的 Starter 独占路径边界。
 *
 * <p>边界匹配始终按完整路径段判断，确保 /ai 只拥有 /ai 与 /ai/**，不会误接管 /aix。
 * 根路径 / 明确表示整个 MVC 路径空间均归 Starter 所有。
 */
final class PatchBridgeAgentPathScope {

    /** 与 Spring MVC 请求路径解析一致的路径提取器。 */
    private static final UrlPathHelper URL_PATH_HELPER = new UrlPathHelper();

    /** 已通过严格格式校验的独占根路径。 */
    private final String basePath;

    /** 创建路径边界；非法或带尾斜杠的路径必须显式失败。 */
    PatchBridgeAgentPathScope(String basePath) {
        if (basePath == null
                || (!("/".equals(basePath))
                && !basePath.matches("/[A-Za-z0-9._~-]+(?:/[A-Za-z0-9._~-]+)*"))) {
            throw new IllegalArgumentException(
                    "patchbridge-agent.base-path 必须是 / 或不带尾斜杠的绝对路径");
        }
        this.basePath = basePath;
    }

    /** 返回用于错误信息和所有权判断的规范化根路径。 */
    String getBasePath() {
        return basePath;
    }

    /** 根据当前 DispatcherServlet 的应用内路径判断请求是否属于独占命名空间。 */
    boolean contains(HttpServletRequest request) {
        String lookupPath = URL_PATH_HELPER.getLookupPathForRequest(request);
        return containsPath(lookupPath.isEmpty() ? "/" : lookupPath);
    }

    /** 判断已注册 RequestMapping pattern 是否位于独占命名空间。 */
    boolean containsPattern(String pattern) {
        if (pattern == null) {
            throw new IllegalArgumentException("RequestMapping pattern 不能为空");
        }
        return containsPath(pattern.isEmpty() ? "/" : pattern);
    }

    /** 使用路径段边界执行统一匹配，根路径按“拥有全部 MVC 路径”处理。 */
    private boolean containsPath(String path) {
        if ("/".equals(basePath)) {
            return true;
        }
        return path.equals(basePath) || path.startsWith(basePath + "/");
    }
}
