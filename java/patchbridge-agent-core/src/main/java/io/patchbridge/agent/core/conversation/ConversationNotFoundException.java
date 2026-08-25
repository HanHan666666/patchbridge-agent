package io.patchbridge.agent.core.conversation;

/**
 * 当前 owner 范围内找不到目标会话。
 *
 * <p>ConversationRepository 的读取与保存都使用该异常表达资源不存在；不存在与属于
 * 其他 owner 保持同一语义，避免 Adapter 通过错误类型泄漏会话归属信息。
 */
public final class ConversationNotFoundException extends RuntimeException {

    /**
     * 创建不携带 owner 细节的会话不存在异常。
     *
     * @param message 面向当前调用方的稳定说明
     */
    public ConversationNotFoundException(String message) {
        super(message);
    }
}
