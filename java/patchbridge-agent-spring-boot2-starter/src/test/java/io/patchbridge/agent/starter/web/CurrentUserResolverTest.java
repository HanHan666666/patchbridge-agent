package io.patchbridge.agent.starter.web;

import io.patchbridge.agent.core.auth.CurrentUserProvider;
import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.user.UserContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证 Starter 可信身份入口对缺失、空白和原始 userId 的严格边界。
 */
class CurrentUserResolverTest {

    /** null 用户以及没有可见文本的 userId 都必须按未登录拒绝。 */
    @Test
    void missingAndBlankUserIdsAreRejected() {
        UserContext[] invalidUsers = new UserContext[] {
            null,
            UserContext.builder().userId(null).build(),
            UserContext.builder().userId("").build(),
            UserContext.builder().userId("   " ).build(),
            UserContext.builder().userId("\t\n").build(),
            UserContext.builder().userId("　").build()
        };

        for (UserContext invalidUser : invalidUsers) {
            CurrentUserResolver resolver = new CurrentUserResolver(provider(invalidUser));
            assertThrows(AuthRequiredException.class, resolver::requiredUser);
        }
    }

    /** 校验只判断是否为空白，不能裁剪或重建宿主提供的身份和 owner 原值。 */
    @Test
    void nonBlankUserIdIsReturnedWithoutNormalization() {
        UserContext original = UserContext.builder().userId("  user-1  " ).build();
        CurrentUserResolver resolver = new CurrentUserResolver(provider(original));

        UserContext resolved = resolver.requiredUser();

        assertSame(original, resolved);
        assertEquals("  user-1  ", resolved.getUserId());
    }

    /** 创建始终返回指定上下文的宿主身份适配器。 */
    private static CurrentUserProvider provider(final UserContext user) {
        return new CurrentUserProvider() {
            @Override
            public UserContext currentUser(AiRequestContext request) {
                return user;
            }
        };
    }
}
