package io.patchbridge.agent.starter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.patchbridge.agent.core.auth.CurrentUserProvider;
import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.conversation.ConversationOwnerResolver;
import io.patchbridge.agent.core.user.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 验证宿主自定义会话归属规则可以实现租户隔离。
 *
 * <p>测试刻意让两个租户使用相同 userId，确保 Controller 不再直接把 userId 当作存储归属，而是对每个入口统一使用 ConversationOwnerResolver 的
 * ownerKey。
 */
@SpringBootTest(
        classes = ConversationTenantIsolationIntegrationTest.TestApp.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:tenanttest;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:agent-schema-h2.sql",
            "patchbridge-agent.model.base-url=http://localhost:0/v1",
            "patchbridge-agent.model.model=fake-model",
            "patchbridge-agent.model.context-window-tokens=128000",
            "patchbridge-agent.mcp.enabled=false"
        })
@AutoConfigureMockMvc
class ConversationTenantIsolationIntegrationTest {

    /** 用于通过 HTTP 契约验证完整的 Controller 到 JDBC 隔离链路。 */
    @Autowired private MockMvc mockMvc;

    /** 用于从创建响应中可靠读取会话标识。 */
    @Autowired private ObjectMapper objectMapper;

    /** 测试后清理线程安全上下文，避免登录态泄漏到其他用例。 */
    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    /** 相同 userId 在不同 tenantId 下互不可见，也不能跨租户删除。 */
    @Test
    void sameUserIdInDifferentTenantsIsIsolated() throws Exception {
        login("tenant-a");
        String response =
                mockMvc.perform(
                                post("/ai/conversations")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"title\":\"租户 A 会话\"}"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String conversationId =
                objectMapper
                        .readTree(response)
                        .path("conversation")
                        .path("conversationId")
                        .asText();
        mockMvc.perform(
                        put("/ai/conversations/" + conversationId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"revision\":0,\"context\":{"
                                            + "\"messages\":[{\"id\":\"tenant-a-message\","
                                            + "\"role\":\"user\",\"blocks\":[{\"type\":\"text\",\"text\":\"租户"
                                            + " A 私有消息\"}]}],"
                                            + "\"modelContext\":{\"checkpoint\":null,"
                                            + "\"firstRetainedMessageId\":null,"
                                            + "\"modelState\":{\"format\":\"tenant-test/v1\","
                                            + "\"data\":{\"secret\":\"tenant-a-state\"}},"
                                            + "\"usage\":null}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversation.revision").value(1));

        login("tenant-b");
        mockMvc.perform(get("/ai/conversations/" + conversationId))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/ai/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversations.length()").value(0));
        mockMvc.perform(
                        put("/ai/conversations/" + conversationId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"revision\":1,\"context\":{"
                                                + "\"messages\":[],\"modelContext\":{"
                                                + "\"checkpoint\":null,"
                                                + "\"firstRetainedMessageId\":null,"
                                                + "\"modelState\":null,\"usage\":null}}}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CONVERSATION_NOT_FOUND"));
        mockMvc.perform(delete("/ai/conversations/" + conversationId))
                .andExpect(status().isNoContent());

        login("tenant-a");
        mockMvc.perform(get("/ai/conversations/" + conversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversation.title").value("租户 A 会话"))
                .andExpect(jsonPath("$.conversation.revision").value(1))
                .andExpect(jsonPath("$.context.messages[0].blocks[0].text").value("租户 A 私有消息"))
                .andExpect(jsonPath("$.context.modelContext.modelState.data.secret")
                        .value("tenant-a-state"));
    }

    /** 把租户放入认证 details，模拟宿主安全体系解析出的可信租户。 */
    private void login(String tenantId) {
        TestingAuthenticationToken authentication =
                new TestingAuthenticationToken("shared-user", "n/a", "ai:chat:use");
        authentication.setDetails(tenantId);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /** 隔离测试宿主：排除 Boot 默认安全链，身份与会话归属规则都由宿主 Bean 提供。 */
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration(
            exclude = {
                org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
                        .class,
                org.springframework.boot.autoconfigure.security.servlet
                        .SecurityFilterAutoConfiguration.class,
                org.springframework.boot.autoconfigure.security.servlet
                        .UserDetailsServiceAutoConfiguration.class
            })
    @org.springframework.context.annotation.Configuration
    static class TestApp {

        /** 提供能解析 tenantId 的测试身份适配器。 */
        @Bean
        public CurrentUserProvider tenantCurrentUserProvider() {
            return new TenantCurrentUserProvider();
        }

        /** 提供 tenantId + userId 的复合会话归属规则。 */
        @Bean
        public ConversationOwnerResolver tenantConversationOwnerResolver() {
            return new TenantConversationOwnerResolver();
        }
    }

    /** 从测试认证对象中构造带 tenantId 的可信 UserContext。 */
    private static final class TenantCurrentUserProvider implements CurrentUserProvider {

        /** 认证 details 在本测试中代表宿主已校验的 tenantId。 */
        @Override
        public UserContext currentUser(AiRequestContext request) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()) {
                return null;
            }
            return UserContext.builder()
                    .userId(authentication.getName())
                    .username(authentication.getName())
                    .tenantId(String.valueOf(authentication.getDetails()))
                    .build();
        }
    }

    /** 示例租户归属规则；分隔符语义由宿主掌握，框架只执行等值隔离。 */
    private static final class TenantConversationOwnerResolver
            implements ConversationOwnerResolver {

        /** 将租户与用户组合成稳定且不透明的存储键。 */
        @Override
        public String resolveOwnerKey(UserContext user) {
            return user.getTenantId() + ":" + user.getUserId();
        }
    }
}
