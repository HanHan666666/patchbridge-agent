package io.patchbridge.agent.core.tool;

import io.patchbridge.agent.core.auth.ToolAccessPolicy;
import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.error.ToolAccessDeniedException;
import io.patchbridge.agent.core.error.ToolExecutionException;
import io.patchbridge.agent.core.error.ToolVersionMismatchException;
import io.patchbridge.agent.core.user.UserContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 默认聚合 Registry：把多个 ToolProvider 聚合到统一命名平面。
 *
 * <p>命名契约（见 {@link ToolNamingStrategy}）：
 * Provider 返回的 {@link ToolDefinition#getName()} 已经是带命名空间的全名，
 * 本类按“最长命名空间前缀”把调用路由回对应 Provider。
 *
 * <p>安全契约：list 按 canDiscover 过滤；call 每次都重新执行 canInvoke。
 * 浏览器拿到的 Tool 列表只代表“当时可见”，不代表后续调用免检。
 *
 * <p>版本契约：call 携带的定义/路由版本引用必须与 find 取到的当前定义一致。
 * find 会触发各 Provider 的配置同步与快照刷新，因此该比较使用的是
 * “即将参与路由的同一代”服务端状态；版本不符说明调用方持有过期快照，
 * 必须明确失败而不是把旧调用执行到新目标。Provider 内部还会在自己
 * 实际路由的状态上做最终复核，收敛 find 与执行之间的配置切换竞态。
 *
 * <p>启动期即校验命名空间唯一与“前缀包含”冲突，冲突直接失败，
 * 避免运行期出现不可预测的路由。
 */
public class DefaultToolRegistry implements ToolRegistry {

    private final List<ToolProvider> providers;
    private final ToolAccessPolicy accessPolicy;

    public DefaultToolRegistry(List<ToolProvider> providers, ToolAccessPolicy accessPolicy) {
        this.accessPolicy = accessPolicy;
        this.providers = new ArrayList<ToolProvider>(providers);
        validateNamespaces(this.providers);
    }

    @Override
    public List<ToolDefinition> list(UserContext user) {
        List<ToolDefinition> visible = new ArrayList<ToolDefinition>();
        for (ToolProvider provider : providers) {
            for (ToolDefinition tool : provider.list()) {
                if (accessPolicy.canDiscover(user, tool)) {
                    visible.add(tool);
                }
            }
        }
        return visible;
    }

    @Override
    public ToolDefinition find(String fullName) {
        if (fullName == null) {
            return null;
        }
        for (ToolProvider provider : providers) {
            for (ToolDefinition tool : provider.list()) {
                if (tool.getName().equals(fullName)) {
                    return tool;
                }
            }
        }
        return null;
    }

    @Override
    public ToolCallResult call(String fullName, String definitionVersion,
                               Map<String, Object> arguments, AiRequestContext requestContext)
            throws ToolExecutionException, ToolAccessDeniedException,
            ToolVersionMismatchException {
        ToolDefinition tool = find(fullName);
        if (tool == null) {
            throw new ToolExecutionException("Tool 不存在: " + fullName);
        }
        // 版本一致性先于授权与路由：过期引用说明调用方的整份快照不可信，
        // 即使当前用户仍有权限，也不能让旧描述引导的调用继续执行。
        if (!Objects.equals(definitionVersion, tool.getVersion())) {
            throw new ToolVersionMismatchException(
                    "Tool 定义或路由已更新，当前调用携带的版本引用已过期，请重新发现工具后重试: "
                            + fullName);
        }
        if (!accessPolicy.canInvoke(requestContext.getUser(), tool)) {
            throw new ToolAccessDeniedException("当前用户无权调用 Tool: " + fullName);
        }
        ToolProvider provider = routeProvider(fullName);
        if (provider == null) {
            // find() 命中但前缀路由失败，说明某个 Provider 破坏了“全名 = namespace + 本地名”契约
            throw new ToolExecutionException("Tool 命名不符合命名空间契约，无法路由: " + fullName);
        }
        return provider.call(stripNamespace(fullName, provider.namespace()), definitionVersion,
                arguments, requestContext);
    }

    /** 按最长命名空间前缀定位 Provider（MCP 命名空间含点，必须最长匹配）。 */
    private ToolProvider routeProvider(String fullName) {
        ToolProvider matched = null;
        int matchedLength = -1;
        for (ToolProvider provider : providers) {
            String prefix = provider.namespace() + ".";
            if (fullName.startsWith(prefix) && provider.namespace().length() > matchedLength) {
                matched = provider;
                matchedLength = provider.namespace().length();
            }
        }
        return matched;
    }

    private static String stripNamespace(String fullName, String namespace) {
        return fullName.substring(namespace.length() + 1);
    }

    /** 校验命名空间：非空、唯一、互不为前缀（否则最长匹配仍有歧义）。 */
    private static void validateNamespaces(List<ToolProvider> providers) {
        List<String> namespaces = new ArrayList<String>();
        for (ToolProvider provider : providers) {
            String ns = provider.namespace();
            if (ns == null || ns.isEmpty()) {
                throw new IllegalArgumentException(
                        "ToolProvider namespace 不能为空: " + provider.getClass().getName());
            }
            for (String existing : namespaces) {
                if (existing.equals(ns)) {
                    throw new IllegalArgumentException("ToolProvider 命名空间冲突: " + ns);
                }
                if (existing.startsWith(ns + ".") || ns.startsWith(existing + ".")) {
                    throw new IllegalArgumentException(
                            "ToolProvider 命名空间存在前缀包含，路由会有歧义: " + existing + " vs " + ns);
                }
            }
            namespaces.add(ns);
        }
    }
}
