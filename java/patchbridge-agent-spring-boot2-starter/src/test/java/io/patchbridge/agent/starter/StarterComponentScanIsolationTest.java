package io.patchbridge.agent.starter;

import io.patchbridge.agent.starter.web.AdminApiController;
import io.patchbridge.agent.starter.web.AdminConsoleController;
import io.patchbridge.agent.starter.web.ConversationController;
import io.patchbridge.agent.starter.web.PatchBridgeAgentExceptionHandler;
import io.patchbridge.agent.starter.web.McpAdminController;
import io.patchbridge.agent.starter.web.ModelStreamController;
import io.patchbridge.agent.starter.web.ToolGatewayController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证宿主即使扩大了 ComponentScan，也不能绕过 Starter 和 Admin 开关。
 */
class StarterComponentScanIsolationTest {

    /** 总开关关闭时，宽范围扫描不得注册任何框架 Controller 或 Advice。 */
    @Test
    void disabledStarterCannotBeReenabledByBroadComponentScan() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        try {
            TestPropertyValues.of(
                    "patchbridge-agent.enabled=false",
                    "patchbridge-agent.admin.enabled=true")
                    .applyTo(context);
            context.scan("io.patchbridge.agent.starter.web");
            context.refresh();

            assertTrue(context.getBeansOfType(ToolGatewayController.class).isEmpty());
            assertTrue(context.getBeansOfType(ModelStreamController.class).isEmpty());
            assertTrue(context.getBeansOfType(ConversationController.class).isEmpty());
            assertTrue(context.getBeansOfType(AdminApiController.class).isEmpty());
            assertTrue(context.getBeansOfType(AdminConsoleController.class).isEmpty());
            assertTrue(context.getBeansOfType(McpAdminController.class).isEmpty());
            assertTrue(context.getBeansOfType(PatchBridgeAgentExceptionHandler.class).isEmpty());
        } finally {
            context.close();
        }
    }

    /** 单独扫到 Admin Controller 也必须因缺少统一授权拦截器而不注册。 */
    @Test
    void adminControllersRequireAuthorizationInfrastructure() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        try {
            TestPropertyValues.of(
                    "patchbridge-agent.enabled=true",
                    "patchbridge-agent.admin.enabled=true")
                    .applyTo(context);
            context.register(AdminApiController.class,
                    AdminConsoleController.class, McpAdminController.class);
            context.refresh();

            assertTrue(context.getBeansOfType(AdminApiController.class).isEmpty());
            assertTrue(context.getBeansOfType(AdminConsoleController.class).isEmpty());
            assertTrue(context.getBeansOfType(McpAdminController.class).isEmpty());
        } finally {
            context.close();
        }
    }
}
