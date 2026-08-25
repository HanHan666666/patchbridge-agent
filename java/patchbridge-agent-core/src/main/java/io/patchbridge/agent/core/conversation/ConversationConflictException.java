package io.patchbridge.agent.core.conversation;

/**
 * 会话保存冲突：客户端 revision 落后于服务端（典型场景是另一个 Tab 已保存过）。
 * 调用方应提示用户刷新会话，而不是静默覆盖。
 */
public class ConversationConflictException extends Exception {

    private final long currentRevision;

    public ConversationConflictException(String message, long currentRevision) {
        super(message);
        this.currentRevision = currentRevision;
    }

    /** 服务端当前 revision，便于客户端重新对齐。 */
    public long getCurrentRevision() {
        return currentRevision;
    }
}
