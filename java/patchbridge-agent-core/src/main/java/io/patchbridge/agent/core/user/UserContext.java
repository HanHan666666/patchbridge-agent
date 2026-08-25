package io.patchbridge.agent.core.user;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 服务端解析出的当前登录用户上下文（不可变）。
 *
 * <p>它是所有可信身份信息的唯一载体：由宿主应用通过 CurrentUserProvider 从
 * 现有登录体系（Spring Security / Shiro / Sa-Token / 自研）解析得到，
 * 由服务器在 Tool 执行时注入，绝不来自 LLM 生成的参数或浏览器声明。
 */
public final class UserContext {

    private final String userId;
    private final String username;
    private final String tenantId;
    private final Set<String> roles;
    private final Set<String> permissions;
    private final Map<String, String> attributes;

    private UserContext(Builder builder) {
        this.userId = builder.userId;
        this.username = builder.username;
        this.tenantId = builder.tenantId;
        this.roles = Collections.unmodifiableSet(new LinkedHashSet<String>(builder.roles));
        this.permissions = Collections.unmodifiableSet(new LinkedHashSet<String>(builder.permissions));
        // attributes 与 roles/permissions 一样做防御性复制：宿主传入的 Map 在 build 后
        // 仍可能被外部修改（二次审计 Q-05），不可变对象不允许保留这种外部可变别名。
        this.attributes = Collections.unmodifiableMap(
                new LinkedHashMap<String, String>(builder.attributes));
    }

    /** 唯一用户标识（对应宿主系统的用户主键或账号）。 */
    public String getUserId() {
        return userId;
    }

    /** 展示名，用于审计。 */
    public String getUsername() {
        return username;
    }

    /** 租户 / 组织标识，多租户系统用于数据隔离校验。 */
    public String getTenantId() {
        return tenantId;
    }

    /** 角色集合（如 ROLE_ADMIN），仅作为信息透传，权限判断由 ToolAccessPolicy 决定。 */
    public Set<String> getRoles() {
        return roles;
    }

    /** 权限标识集合（宿主系统语义，如 device:restart）。 */
    public Set<String> getPermissions() {
        return permissions;
    }

    /** 扩展属性（部门、组织等），由宿主应用自行约定。 */
    public Map<String, String> getAttributes() {
        return attributes;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 建造者。userId 为空视为“未识别出登录用户”，AuthXXX 默认策略将拒绝其调用。 */
    public static final class Builder {
        private String userId;
        private String username;
        private String tenantId;
        private Set<String> roles = new LinkedHashSet<String>();
        private Set<String> permissions = new LinkedHashSet<String>();
        private Map<String, String> attributes = Collections.emptyMap();

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder roles(Set<String> roles) {
            this.roles = roles;
            return this;
        }

        public Builder role(String role) {
            this.roles.add(role);
            return this;
        }

        public Builder permissions(Set<String> permissions) {
            this.permissions = permissions;
            return this;
        }

        public Builder permission(String permission) {
            this.permissions.add(permission);
            return this;
        }

        public Builder attributes(Map<String, String> attributes) {
            this.attributes = attributes;
            return this;
        }

        public UserContext build() {
            return new UserContext(this);
        }
    }
}
