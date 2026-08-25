package io.patchbridge.agent.starter.web;

import io.patchbridge.agent.core.audit.AuditInvocationType;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Admin 静态控制台与后端公开枚举的契约测试。
 *
 * <p>页面由 Starter 直接分发，没有模板编译阶段；静态过滤项若漂移，只会在用户查询时
 * 变成 400。因此测试直接核对资源中可选择的调用类型。
 */
class AdminConsoleResourceContractTest {

    /** 页面只展示后端实际接受的 AuditInvocationType，不保留规划或已删除类型。 */
    @Test
    void auditTypeOptionsMatchBackendContract() throws Exception {
        String html;
        try (InputStream input = new ClassPathResource(
                "META-INF/patchbridge-agent-admin/index.html").getInputStream()) {
            html = StreamUtils.copyToString(input, StandardCharsets.UTF_8);
        }

        for (AuditInvocationType type : AuditInvocationType.values()) {
            String option = "<option value=\"" + type.name() + "\">" + type.name() + "</option>";
            assertTrue(html.contains(option), "管理页缺少审计类型: " + type.name());
        }
        assertFalse(html.contains("CONFIRMATION"),
                "Browser HITL 不属于服务端 AuditInvocationType，管理页不得提供该过滤项");
    }
}
