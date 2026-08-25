package io.patchbridge.agent.core.conversation;

import io.patchbridge.agent.core.user.UserContext;

/**
 * 以 userId 作为会话归属键的单租户默认实现。
 *
 * <p>该实现只服务于“用户标识在整个系统内全局唯一”的场景。若同一 userId
 * 可出现在不同租户中，宿主必须提供自定义 {@link ConversationOwnerResolver}。
 */
public final class UserIdConversationOwnerResolver implements ConversationOwnerResolver {

    /**
     * 返回可信用户上下文中的 userId，不拼接或推断任何租户规则。
     *
     * @param user 服务端解析出的可信用户上下文
     * @return 当前用户的 userId
     */
    @Override
    public String resolveOwnerKey(UserContext user) {
        if (user == null) {
            throw new IllegalArgumentException("解析会话归属键时用户上下文不能为空");
        }
        return user.getUserId();
    }
}
