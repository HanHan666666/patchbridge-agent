package io.patchbridge.agent.core.conversation;

import io.patchbridge.agent.core.model.AgentMessage;
import io.patchbridge.agent.core.model.target.ModelTargetRef;
import io.patchbridge.agent.core.model.MessageRole;

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

    /** 与完整上下文同一修订保存的当前目标。 */
    private final ModelTargetRef modelTarget;

    /** 按对话顺序排列的稳定消息快照。 */
    private final List<AgentMessage> messages;

    /** 与完整消息历史分离、可以独立压缩的模型工作上下文。 */
    private final ModelContext modelContext;

    /**
     * 创建不可变会话上下文。
     *
     * @param messages 完整稳定消息列表，允许为空但不能为 null
     * @param modelContext 非空的模型工作上下文
     */
    public ConversationContext(List<AgentMessage> messages, ModelTargetRef modelTarget, ModelContext modelContext) {
        if (messages == null) {
            throw new IllegalArgumentException("conversationContext.messages 不可为空");
        }
        if (modelContext == null) {
            throw new IllegalArgumentException("conversationContext.modelContext 不可为空");
        }
        if (modelTarget == null) throw new IllegalArgumentException("modelTarget 不可为空");
        this.modelTarget = modelTarget;
        assertUniqueMessageIds(messages);
        assertModelContextReferences(messages, modelContext);
        this.messages = Collections.unmodifiableList(new ArrayList<AgentMessage>(messages));
        this.modelContext = modelContext;
    }

    /** 返回会话下一次调用必须使用的目标。 */
    public ModelTargetRef getModelTarget() { return modelTarget; }

    /** 返回按对话顺序排列的不可变消息快照。 */
    public List<AgentMessage> getMessages() {
        return messages;
    }

    /** 返回不可为空的模型工作上下文。 */
    public ModelContext getModelContext() {
        return modelContext;
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

    /** 检查点边界和用量游标都必须引用完整聊天历史中的真实消息。 */
    private static void assertModelContextReferences(
            List<AgentMessage> messages, ModelContext modelContext) {
        String retainedId = modelContext.getFirstRetainedMessageId();
        if (retainedId != null) {
            AgentMessage retained = findMessage(messages, retainedId);
            if (retained == null || retained.getRole() == MessageRole.SYSTEM) {
                throw new IllegalArgumentException(
                        "modelContext.firstRetainedMessageId 必须引用非 system 消息");
            }
        }
        ModelContextUsage usage = modelContext.getUsage();
        if (usage != null && usage.getMeasuredThroughMessageId() != null
                && findMessage(messages, usage.getMeasuredThroughMessageId()) == null) {
            throw new IllegalArgumentException(
                    "modelContext.usage.measuredThroughMessageId 引用了不存在的消息");
        }
    }

    /** 按稳定 ID 查找真实聊天消息。 */
    private static AgentMessage findMessage(List<AgentMessage> messages, String id) {
        for (AgentMessage message : messages) {
            if (message.getId().equals(id)) {
                return message;
            }
        }
        return null;
    }
}
