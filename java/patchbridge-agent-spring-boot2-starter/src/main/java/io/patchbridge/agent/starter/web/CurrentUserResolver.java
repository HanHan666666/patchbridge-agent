package io.patchbridge.agent.starter.web;

import io.patchbridge.agent.core.auth.CurrentUserProvider;
import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.user.UserContext;
import org.springframework.util.StringUtils;

/**
 * Starter 控制器与管理端共用的可信用户解析入口。
 *
 * <p>这里只判定 userId 是否包含可见文本，不做 trim 或其他归一化；会话 owner、审计和
 * 宿主权限策略必须继续消费宿主提供的原始标识，避免身份边界被框架静默改写。
 */
public class CurrentUserResolver {

    /** 宿主现有登录体系的身份适配端口。 */
    private final CurrentUserProvider provider;

    /** 创建统一身份解析器；provider 的返回值仍由本类执行最终登录态校验。 */
    public CurrentUserResolver(CurrentUserProvider provider) {
        this.provider = provider;
    }

    /**
     * 解析登录用户；用户缺失或 userId 没有可见文本时抛出 401 语义异常。
     *
     * @return 未经裁剪或重建的原始用户上下文
     */
    public UserContext requiredUser() {
        UserContext user = provider.currentUser(
                new AiRequestContext(null, null, null, null, null));
        if (user == null || !StringUtils.hasText(user.getUserId())) {
            throw new AuthRequiredException("使用 AI 能力需要先登录");
        }
        return user;
    }
}
