package io.patchbridge.agent.core.model.target;

import io.patchbridge.agent.core.user.UserContext;

/** 可信入口构建的模型授权上下文；匿名 HTTP 不能冒充可信 JVM 调用。 */
public final class ModelAccessContext {
    /** 服务端解析的用户，可在可信系统调用中为空。 */
    private final UserContext user;

    /** 只有 Java 门面可以显式选择系统调用语义。 */
    private final boolean trustedJvm;

    /** 限定由具名工厂构建，避免请求 JSON 绑定授权来源。 */
    private ModelAccessContext(UserContext user, boolean trustedJvm) {
        this.user = user;
        this.trustedJvm = trustedJvm;
    }

    /** HTTP 必须先经过宿主身份认证。 */
    public static ModelAccessContext authenticated(UserContext user) {
        if (user == null || user.getUserId() == null || user.getUserId().trim().isEmpty()) {
            throw new ModelTargetException("AUTH_REQUIRED", "模型调用要求可信登录身份");
        }
        return new ModelAccessContext(user, false);
    }

    /** 宿主 Java 代码显式发起系统调用，不从 HTTP 请求推导。 */
    public static ModelAccessContext trustedJvm() {
        return new ModelAccessContext(null, true);
    }

    /** 返回供宿主授权策略使用的用户。 */
    public UserContext getUser() {
        return user;
    }

    /** 区分系统调用与已认证用户调用。 */
    public boolean isTrustedJvm() {
        return trustedJvm;
    }
}
