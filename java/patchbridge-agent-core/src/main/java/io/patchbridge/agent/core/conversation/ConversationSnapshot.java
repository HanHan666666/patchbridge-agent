package io.patchbridge.agent.core.conversation;

/**
 * 一次一致性读取获得的持久化会话聚合。
 *
 * <p>Conversation 中的 revision 与 Context 必须来自同一数据库快照，避免并发保存时 把旧 revision 和新模型状态组合后交给浏览器继续执行。
 */
public final class ConversationSnapshot {

    /** 不包含敏感模型状态的会话元数据。 */
    private final Conversation conversation;

    /** 与元数据 revision 对应的完整会话上下文。 */
    private final ConversationContext context;

    /**
     * 创建不可变会话聚合。
     *
     * @param conversation 会话元数据
     * @param context 同一 revision 下的完整上下文
     */
    public ConversationSnapshot(Conversation conversation, ConversationContext context) {
        if (conversation == null) {
            throw new IllegalArgumentException("conversationSnapshot.conversation 不可为空");
        }
        if (context == null) {
            throw new IllegalArgumentException("conversationSnapshot.context 不可为空");
        }
        this.conversation = conversation;
        this.context = context;
    }

    /** 返回不包含 ownerKey 对外视图逻辑的领域会话元数据。 */
    public Conversation getConversation() {
        return conversation;
    }

    /** 返回与 revision 一致的完整上下文。 */
    public ConversationContext getContext() {
        return context;
    }
}
