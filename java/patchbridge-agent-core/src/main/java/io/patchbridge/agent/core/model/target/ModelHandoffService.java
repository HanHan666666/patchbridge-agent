package io.patchbridge.agent.core.model.target;

import io.patchbridge.agent.core.compaction.ModelInputEstimator;
import io.patchbridge.agent.core.conversation.*;
import io.patchbridge.agent.core.model.*;

import java.util.*;

/** 草稿与持久化会话共用的切换规则；只构造候选，持久化由带 revision 的应用入口提交。 */
public final class ModelHandoffService {
    /** 与模型调用相同的目标解析和权限边界。 */
    private final ModelProviderRouter router;

    /** 注入唯一模型路由，禁止另行解释目标配置。 */
    public ModelHandoffService(ModelProviderRouter router) {
        this.router = router;
    }

    /** 保留全部历史和检查点，清除旧私有状态后校验目标可表示性与窗口。 */
    public ConversationContext prepare(
            ConversationContext source,
            ModelTargetRef ref,
            List<ModelToolDefinition> tools,
            ModelAccessContext access) {
        if (source == null || tools == null) {
            throw new IllegalArgumentException("切换必须携带完整上下文与工具目录");
        }
        // 同目标调用不能借 handoff 偷偷清除私有状态；独立状态重置需另有明确意图和契约。
        if (source.getModelTarget().equals(ref)) {
            throw new ModelTargetException("MODEL_TARGET_MISMATCH", "目标未变化，不能通过切换清除模型状态");
        }
        ResolvedModelTarget target = router.resolve(ref, access);
        List<AgentMessage> working = workingMessages(source);
        ModelContext previous = source.getModelContext();
        if (!working.isEmpty()) {
            router.prepare(
                    target,
                    new ModelRequest("handoff-validation", ref, working, tools, null, null, null));
        }
        if (!target.supportsTools() && !tools.isEmpty()) {
            throw new ModelTargetException("MODEL_TARGET_INCOMPATIBLE", "目标不支持工具调用");
        }
        int toolTokens = ModelInputEstimator.estimateToolDefinitions(tools);
        long tokens = (long) ModelInputEstimator.estimateMessages(working) + toolTokens;
        // 本次目录与真实工作消息一起预检；失败不保存任何目标或上下文变更。
        if (tokens
                > target.getSettings().getContextWindowTokens()
                        - target.getSettings().getReservedOutputTokens()) {
            throw new ModelTargetException(
                    "MODEL_TARGET_CONTEXT_TOO_LARGE", "当前历史超过目标模型窗口，原会话保持不变");
        }
        List<AgentMessage> messages = source.getMessages();
        ModelContextUsage usage =
                messages.isEmpty()
                        ? null
                        : new ModelContextUsage(
                                tokens,
                                ModelContextUsage.Source.ESTIMATED,
                                messages.get(messages.size() - 1).getId(),
                                toolTokens);
        return new ConversationContext(
                messages,
                ref,
                new ModelContext(
                        previous.getCheckpoint(),
                        previous.getFirstRetainedMessageId(),
                        null,
                        usage));
    }

    /** 与 Browser 检查点投影保持同一消息形状；完整历史只读不改写。 */
    public static List<AgentMessage> workingMessages(ConversationContext context) {
        ContextCompactionCheckpoint checkpoint = context.getModelContext().getCheckpoint();
        if (checkpoint == null) return context.getMessages();
        List<AgentMessage> projected = new ArrayList<AgentMessage>();
        for (AgentMessage message : context.getMessages())
            if (message.getRole() == MessageRole.SYSTEM) projected.add(message);
        projected.add(
                new AgentMessage(
                        "context-summary-" + checkpoint.getId(),
                        MessageRole.USER,
                        Collections.<ContentBlock>singletonList(
                                new TextBlock(
                                        "<context-checkpoint>\n"
                                                + checkpoint.getSummary()
                                                + "\n</context-checkpoint>"))));
        boolean retain = false;
        for (AgentMessage message : context.getMessages()) {
            if (message.getId().equals(context.getModelContext().getFirstRetainedMessageId()))
                retain = true;
            if (retain && message.getRole() != MessageRole.SYSTEM) projected.add(message);
        }
        if (!retain) throw new IllegalArgumentException("检查点没有合法保留边界");
        return Collections.unmodifiableList(projected);
    }
}
