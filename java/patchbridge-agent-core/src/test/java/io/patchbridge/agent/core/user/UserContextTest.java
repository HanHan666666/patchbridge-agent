package io.patchbridge.agent.core.user;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UserContext 不可变契约测试：构造后再修改来源集合，不允许影响已构建对象
 * （二次审计 Q-05 的回归防线）。
 */
class UserContextTest {

    @Test
    void buildCopiesMutableSourceCollections() {
        Set<String> roles = new LinkedHashSet<String>();
        roles.add("ROLE_ADMIN");
        Set<String> permissions = new LinkedHashSet<String>();
        permissions.add("device:read");
        Map<String, String> attributes = new LinkedHashMap<String, String>();
        attributes.put("department", "ops");

        UserContext user = UserContext.builder()
                .userId("u1")
                .roles(roles)
                .permissions(permissions)
                .attributes(attributes)
                .build();

        // 构造后外部继续修改来源集合，已构建对象保持不变
        roles.add("ROLE_GUEST");
        permissions.add("device:write");
        attributes.put("department", "finance");

        assertEquals(1, user.getRoles().size());
        assertTrue(user.getRoles().contains("ROLE_ADMIN"));
        assertEquals(1, user.getPermissions().size());
        assertEquals("ops", user.getAttributes().get("department"));
    }

    @Test
    void exposedCollectionsAreUnmodifiable() {
        UserContext user = UserContext.builder()
                .userId("u1")
                .role("ROLE_ADMIN")
                .permission("device:read")
                .attributes(new LinkedHashMap<String, String>())
                .build();

        assertThrows(UnsupportedOperationException.class,
                () -> user.getRoles().add("ROLE_GUEST"));
        assertThrows(UnsupportedOperationException.class,
                () -> user.getPermissions().add("device:write"));
        assertThrows(UnsupportedOperationException.class,
                () -> user.getAttributes().put("department", "ops"));
    }
}
