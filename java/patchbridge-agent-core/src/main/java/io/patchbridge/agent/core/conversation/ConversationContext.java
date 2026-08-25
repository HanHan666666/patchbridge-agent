package io.patchbridge.agent.core.conversation;

import io.patchbridge.agent.core.model.AgentMessage;
import io.patchbridge.agent.core.model.ModelState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 可跨刷新和设备恢复的完整会话上下文。
 *
 * <p>稳定展示消息与 Provider 私有连续状态在类型上保持分离，但保存时属于同一个 乐观锁聚合。调用方不得只更新其中一部分，否则下一次模型调用可能使用错位的历史。
 */
public final class ConversationContext {

    /** 按对话顺序排列的稳定消息快照。 */
    private final List<AgentMessage> messages;

    /** Provider 拥有的连续状态；无状态模型或空会话时可以为空。 */
    private final ModelState modelState;

    /**
     * 创建不可变会话上下文。
     *
     * @param messages 完整稳定消息列表，允许为空但不能为 null
     * @param modelState 可选的模型连续状态
     */
    public ConversationContext(List<AgentMessage> messages, ModelState modelState) {
        if (messages == null) {
            throw new IllegalArgumentException("conversationContext.messages 不可为空");
        }
        assertUniqueMessageIds(messages);
        this.messages = Collections.unmodifiableList(new ArrayList<AgentMessage>(messages));
        this.modelState = modelState;
    }

    /** 返回按对话顺序排列的不可变消息快照。 */
    public List<AgentMessage> getMessages() {
        return messages;
    }

    /** 返回可选的 Provider 连续状态。 */
    public ModelState getModelState() {
        return modelState;
    }

    /** 保证消息稳定 ID 在一个会话内唯一，避免恢复后渲染和追踪指向错误消息。 */
    private static void assertUniqueMessageIds(List<AgentMessage> messages) {
        Set<String> ids = new HashSet<String>();
        for (AgentMessage message : messages) {
            if (message == null) {
                throw new IllegalArgumentException("conversationContext.messages 不允许包含 null");
            }
            if (!ids.add(message.getId())) {
                throw new IllegalArgumentException("会话内消息 id 重复: " + message.getId());
            }
        }
    }
}
