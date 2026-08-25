package io.patchbridge.agent.core.conversation;

import io.patchbridge.agent.core.user.UserContext;

/**
 * 会话归属键解析 SPI。
 *
 * <p>框架只把解析结果当作不透明的存储隔离键，不解释租户、组织或用户的业务语义。
 * 单租户应用可以直接使用用户标识；多租户应用应由宿主组合 tenantId、userId，
 * 或映射为自己的稳定主体标识。返回值必须稳定且非空，不能来自浏览器请求参数。
 */
public interface ConversationOwnerResolver {

    /**
     * 根据服务端可信身份解析会话归属键。
     *
     * @param user 服务端通过登录态解析出的可信用户上下文
     * @return 仅供会话存储进行等值隔离的不透明归属键
     */
    String resolveOwnerKey(UserContext user);
}
