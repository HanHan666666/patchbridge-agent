package io.patchbridge.agent.starter.web.dto;

/**
 * Conversation HTTP 请求共享的字段约束。
 *
 * <p>创建与保存必须使用同一标题上限；该上限与 v0.1 JDBC Schema 的
 * {@code VARCHAR(256)} 一致，避免一个入口接受、另一个入口在持久化时才失败。
 */
final class ConversationRequestValidator {

    /** v0.1 公开会话标题最大字符数。 */
    private static final int MAX_TITLE_LENGTH = 256;

    /** 工具类不允许实例化。 */
    private ConversationRequestValidator() {
    }

    /** 可选标题存在时必须落在持久化契约上限内。 */
    static void validateTitle(String title) {
        if (title != null && title.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException(
                    "会话标题长度不能超过 " + MAX_TITLE_LENGTH + " 个字符");
        }
    }
}
