package io.patchbridge.agent.starter.web;

import io.patchbridge.agent.core.auth.AdminAccessPolicy;
import io.patchbridge.agent.core.auth.AdminCapability;
import io.patchbridge.agent.core.auth.CurrentUserProvider;
import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.user.UserContext;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Admin 路由到宿主能力策略的统一映射与拒绝语义。 */
class AdminAuthorizationInterceptorTest {

    /** 审计查询映射为只读审计能力。 */
    @Test
    void auditGetRequiresAuditRead() throws Exception {
        RecordingPolicy policy = new RecordingPolicy(true);
        AdminAuthorizationInterceptor interceptor = interceptor(policy);

        boolean allowed = interceptor.preHandle(
                request("GET", "/ai/admin/traces"), new MockHttpServletResponse(), new Object());

        assertTrue(allowed);
        assertEquals(AdminCapability.AUDIT_READ, policy.capabilities.get(0));
    }

    /** MCP 状态读取与刷新分别映射到 READ / MANAGE，避免审计员获得修改能力。 */
    @Test
    void mcpReadAndMutationUseDifferentCapabilities() throws Exception {
        RecordingPolicy policy = new RecordingPolicy(true);
        AdminAuthorizationInterceptor interceptor = interceptor(policy);

        boolean readAllowed = interceptor.preHandle(
                request("GET", "/ai/admin/mcp/servers"),
                new MockHttpServletResponse(), new Object());
        boolean manageAllowed = interceptor.preHandle(
                request("POST", "/ai/admin/mcp/servers/inventory/refresh"),
                new MockHttpServletResponse(), new Object());

        assertTrue(readAllowed);
        assertTrue(manageAllowed);
        assertEquals(java.util.Arrays.asList(
                AdminCapability.MCP_READ, AdminCapability.MCP_MANAGE), policy.capabilities);
    }

    /** 宿主策略拒绝时，静态资源与 API 共用的拦截器直接返回稳定 403。 */
    @Test
    void deniedCapabilityReturnsForbiddenResponse() throws Exception {
        AdminAuthorizationInterceptor interceptor = interceptor(new RecordingPolicy(false));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request("GET", "/ai-admin/index.html"),
                response, new Object());

        assertFalse(allowed);
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("ADMIN_FORBIDDEN"));
    }

    /** 未登录请求在访问策略前直接收口为稳定 401。 */
    @Test
    void unauthenticatedRequestReturnsUnauthorizedResponse() throws Exception {
        CurrentUserProvider anonymousUsers = new CurrentUserProvider() {
            @Override
            public UserContext currentUser(AiRequestContext request) {
                return null;
            }
        };
        AdminAuthorizationInterceptor interceptor = new AdminAuthorizationInterceptor(
                anonymousUsers, new RecordingPolicy(true), "/ai");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request("GET", "/ai-admin/index.html"),
                response, new Object());

        assertFalse(allowed);
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("AUTH_REQUIRED"));
    }

    /** null、空串和纯空白 userId 必须在宿主授权策略之前统一拒绝。 */
    @Test
    void blankUserIdsAreRejectedBeforePolicy() throws Exception {
        String[] invalidUserIds = new String[] {null, "", "   ", "\t\n", "　"};
        for (final String userId : invalidUserIds) {
            CurrentUserProvider users = new CurrentUserProvider() {
                @Override
                public UserContext currentUser(AiRequestContext request) {
                    return UserContext.builder().userId(userId).build();
                }
            };
            RecordingPolicy policy = new RecordingPolicy(true);
            AdminAuthorizationInterceptor interceptor = new AdminAuthorizationInterceptor(
                    users, policy, "/ai");
            MockHttpServletResponse response = new MockHttpServletResponse();

            boolean allowed = interceptor.preHandle(
                    request("GET", "/ai-admin/index.html"), response, new Object());

            assertFalse(allowed);
            assertEquals(401, response.getStatus());
            assertTrue(policy.users.isEmpty());
        }
    }

    /** 非空白 userId 必须原样交给宿主授权策略，框架不能 trim 或替换身份。 */
    @Test
    void nonBlankUserIdIsPreservedForPolicy() throws Exception {
        final UserContext original = UserContext.builder().userId("  admin-1  " ).build();
        CurrentUserProvider users = new CurrentUserProvider() {
            @Override
            public UserContext currentUser(AiRequestContext request) {
                return original;
            }
        };
        RecordingPolicy policy = new RecordingPolicy(true);
        AdminAuthorizationInterceptor interceptor = new AdminAuthorizationInterceptor(
                users, policy, "/ai");

        boolean allowed = interceptor.preHandle(
                request("GET", "/ai/admin/traces"), new MockHttpServletResponse(), new Object());

        assertTrue(allowed);
        assertSame(original, policy.users.get(0));
        assertEquals("  admin-1  ", policy.users.get(0).getUserId());
    }

    /** 新增却未声明能力的 Admin API 必须默认拒绝，不继承任何宽泛权限。 */
    @Test
    void unknownAdminRouteFailsClosed() throws Exception {
        RecordingPolicy policy = new RecordingPolicy(true);
        AdminAuthorizationInterceptor interceptor = interceptor(policy);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request("POST", "/ai/admin/future-operation"),
                response, new Object());

        assertFalse(allowed);
        assertEquals(403, response.getStatus());
        assertTrue(policy.capabilities.isEmpty());
    }

    /** 创建带固定可信用户的拦截器。 */
    private static AdminAuthorizationInterceptor interceptor(AdminAccessPolicy policy) {
        CurrentUserProvider users = new CurrentUserProvider() {
            @Override
            public UserContext currentUser(AiRequestContext request) {
                return UserContext.builder().userId("u-1").build();
            }
        };
        return new AdminAuthorizationInterceptor(users, policy, "/ai");
    }

    /** 创建测试请求。 */
    private static MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    /** 记录收到能力并按预设结果授权的宿主策略。 */
    private static final class RecordingPolicy implements AdminAccessPolicy {
        /** 固定授权结果。 */
        private final boolean allowed;
        /** 调用过程中收到的能力，用于验证路由映射。 */
        private final List<AdminCapability> capabilities = new ArrayList<AdminCapability>();
        /** 调用过程中收到的原始用户，用于验证身份不被归一化。 */
        private final List<UserContext> users = new ArrayList<UserContext>();

        /** 创建固定授权结果的记录策略。 */
        private RecordingPolicy(boolean allowed) {
            this.allowed = allowed;
        }

        /** 记录能力并返回预设授权结果。 */
        @Override
        public boolean canAccess(UserContext user, AdminCapability capability) {
            users.add(user);
            capabilities.add(capability);
            return allowed;
        }
    }
}
