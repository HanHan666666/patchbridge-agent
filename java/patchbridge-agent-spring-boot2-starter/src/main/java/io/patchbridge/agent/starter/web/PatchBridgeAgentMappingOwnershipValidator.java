package io.patchbridge.agent.starter.web;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.util.ClassUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 在应用启动完成前验证 base-path 的 RequestMapping 所有权。
 *
 * <p>405 在 Spring MVC 选中 HandlerMethod 之前产生，只有把 base-path 定义为 Starter
 * 独占命名空间并拒绝宿主重叠映射，协议异常解析器才能在不影响宿主 Controller 的前提下
 * 根据路径稳定归属请求。
 */
public final class PatchBridgeAgentMappingOwnershipValidator implements SmartInitializingSingleton {

    /** 允许占用独占命名空间的五类公共 Starter Controller。 */
    private static final List<Class<?>> ALLOWED_CONTROLLER_TYPES = Collections.unmodifiableList(
            Arrays.<Class<?>>asList(
                    ToolGatewayController.class,
                    ModelStreamController.class,
                    ConversationController.class,
                    AdminApiController.class,
                    McpAdminController.class));

    /** 独占路径边界。 */
    private final PatchBridgeAgentPathScope pathScope;
    /** Spring MVC 已注册的注解 Controller 映射。 */
    private final RequestMappingHandlerMapping handlerMapping;

    /** 创建启动期所有权校验器。 */
    public PatchBridgeAgentMappingOwnershipValidator(
            String basePath, RequestMappingHandlerMapping handlerMapping) {
        this.pathScope = new PatchBridgeAgentPathScope(basePath);
        this.handlerMapping = handlerMapping;
    }

    /**
     * 在所有单例与 RequestMapping 完成注册后执行一次不可绕过的冲突检查。
     * 合法子类和宿主提供的替换实例按父 Controller 契约放行。
     */
    @Override
    public void afterSingletonsInstantiated() {
        Set<String> conflicts = new LinkedHashSet<String>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry
                : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            Class<?> userType = ClassUtils.getUserClass(handler.getBeanType());
            if (isAllowedControllerType(userType)) {
                continue;
            }
            for (String pattern : entry.getKey().getPatternValues()) {
                if (pathScope.containsPattern(pattern)) {
                    conflicts.add(pattern + " -> " + userType.getName()
                            + "#" + handler.getMethod().getName());
                }
            }
        }
        if (conflicts.isEmpty()) {
            return;
        }
        List<String> sortedConflicts = new ArrayList<String>(conflicts);
        Collections.sort(sortedConflicts);
        throw new IllegalStateException(
                "patchbridge-agent.base-path=" + pathScope.getBasePath()
                        + " 是 Starter 独占命名空间，检测到非 PatchBridge Controller 映射: "
                        + String.join("; ", sortedConflicts));
    }

    /** 判断 Controller 用户类是否属于五类公共契约或其合法子类。 */
    private static boolean isAllowedControllerType(Class<?> controllerType) {
        for (Class<?> allowedType : ALLOWED_CONTROLLER_TYPES) {
            if (allowedType.isAssignableFrom(controllerType)) {
                return true;
            }
        }
        return false;
    }
}
