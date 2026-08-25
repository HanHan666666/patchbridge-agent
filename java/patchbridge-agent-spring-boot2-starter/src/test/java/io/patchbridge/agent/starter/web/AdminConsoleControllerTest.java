package io.patchbridge.agent.starter.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Admin 静态页运行时配置契约测试。
 * 重点保证页面取得宿主真实 basePath，而不是重新引入写死的 /ai 路径。
 */
class AdminConsoleControllerTest {

    /** 自定义 API 前缀必须原样提供给管理页。 */
    @Test
    void exposesConfiguredBasePath() {
        AdminConsoleController controller = new AdminConsoleController("/patchbridge/agent");

        assertEquals("/patchbridge/agent", controller.config().get("basePath"));
    }
}
