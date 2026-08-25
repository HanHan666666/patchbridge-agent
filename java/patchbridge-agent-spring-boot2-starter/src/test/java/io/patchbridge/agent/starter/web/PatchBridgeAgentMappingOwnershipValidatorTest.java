package io.patchbridge.agent.starter.web;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 base-path 独占命名空间的启动期所有权规则：
 * 命名空间内只允许 Starter 公共 Controller，宿主重叠映射必须让启动失败。
 */
class PatchBridgeAgentMappingOwnershipValidatorTest {

    /** 宿主映射落在 base-path 内时启动失败，错误信息必须指出冲突映射与来源类。 */
    @Test
    void hostMappingInsideBasePathFailsStartup() {
        RequestMappingHandlerMapping mapping = mappingWith(new HostControllerInside());
        PatchBridgeAgentMappingOwnershipValidator validator =
                new PatchBridgeAgentMappingOwnershipValidator("/ai", mapping);

        IllegalStateException error = assertThrows(
                IllegalStateException.class, validator::afterSingletonsInstantiated);

        assertTrue(error.getMessage().contains("/ai/host"));
        assertTrue(error.getMessage().contains(HostControllerInside.class.getName()));
        assertTrue(error.getMessage().contains("#handle"));
    }

    /** 命名空间外的宿主映射不受 Starter 所有权约束，启动照常完成。 */
    @Test
    void hostMappingOutsideBasePathPasses() {
        RequestMappingHandlerMapping mapping = mappingWith(new HostControllerOutside());
        PatchBridgeAgentMappingOwnershipValidator validator =
                new PatchBridgeAgentMappingOwnershipValidator("/ai", mapping);

        assertDoesNotThrow(validator::afterSingletonsInstantiated);
    }

    /** 同前缀的相邻完整段（/aix）不属于 /ai 命名空间，不得按前缀误判。 */
    @Test
    void siblingSegmentPrefixIsNotOwned() {
        RequestMappingHandlerMapping mapping = mappingWith(new HostControllerSibling());
        PatchBridgeAgentMappingOwnershipValidator validator =
                new PatchBridgeAgentMappingOwnershipValidator("/ai", mapping);

        assertDoesNotThrow(validator::afterSingletonsInstantiated);
    }

    /** 根路径 base-path 表示 Starter 拥有全部 MVC 路径空间，任何宿主映射都冲突。 */
    @Test
    void rootBasePathRejectsAnyHostMapping() {
        RequestMappingHandlerMapping mapping = mappingWith(new HostControllerOutside());
        PatchBridgeAgentMappingOwnershipValidator validator =
                new PatchBridgeAgentMappingOwnershipValidator("/", mapping);

        assertThrows(IllegalStateException.class, validator::afterSingletonsInstantiated);
    }

    /** 用与生产一致的注解扫描方式构建只含指定 Controller 的映射表。 */
    private static RequestMappingHandlerMapping mappingWith(Object controller) {
        StaticWebApplicationContext context = new StaticWebApplicationContext();
        context.getBeanFactory().registerSingleton("hostController", controller);
        RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
        mapping.setApplicationContext(context);
        try {
            mapping.afterPropertiesSet();
        } catch (Exception e) {
            throw new IllegalStateException("构建测试映射表失败", e);
        }
        return mapping;
    }

    /** 映射进 base-path 的宿主 Controller，模拟宿主误占 Starter 命名空间。 */
    @Controller
    static class HostControllerInside {

        /** 占位映射；仅用于触发所有权校验，不执行。 */
        @GetMapping("/ai/host")
        String handle() {
            return "host";
        }
    }

    /** 映射在 base-path 之外的合法宿主 Controller。 */
    @Controller
    static class HostControllerOutside {

        /** 占位映射；仅用于验证命名空间外不受约束。 */
        @GetMapping("/host/api")
        String handle() {
            return "host";
        }
    }

    /** 与 base-path 同前缀但不属于同一完整段的宿主 Controller。 */
    @Controller
    static class HostControllerSibling {

        /** 占位映射；验证段边界匹配而不是字符串前缀。 */
        @GetMapping("/aix/host")
        String handle() {
            return "host";
        }
    }
}
