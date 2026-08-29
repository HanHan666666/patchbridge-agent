package io.patchbridge.agent.core.conversation;

import io.patchbridge.agent.core.model.ModelState;

/**
 * 与完整聊天历史分离的模型工作上下文。
 *
 * <p>firstRetainedMessageId 引用 ConversationContext.messages 中的真实消息，避免复制
 * 近期尾部；checkpoint 与边界必须同时存在或同时为空。Provider 私有状态仍保持不透明。
 */
public final class ModelContext {

    /** 尚未调用模型的新会话使用的明确空值。 */
    private static final ModelContext EMPTY = new ModelContext(null, null, null, null);

    /** 最近一次压缩检查点。 */
    private final ContextCompactionCheckpoint checkpoint;
    /** 第一条保留的非 system 消息 ID。 */
    private final String firstRetainedMessageId;
    /** 与当前工作消息严格对应的 Provider 私有状态。 */
    private final ModelState modelState;
    /** 最近一次工作上下文计量。 */
    private final ModelContextUsage usage;

    /** 创建经过检查点/边界成对校验的模型工作上下文。 */
    public ModelContext(
            ContextCompactionCheckpoint checkpoint,
            String firstRetainedMessageId,
            ModelState modelState,
            ModelContextUsage usage) {
        if ((checkpoint == null) != (firstRetainedMessageId == null)) {
            throw new IllegalArgumentException(
                    "modelContext.checkpoint 与 firstRetainedMessageId 必须同时存在或同时为空");
        }
        if (firstRetainedMessageId != null && firstRetainedMessageId.trim().isEmpty()) {
            throw new IllegalArgumentException("modelContext.firstRetainedMessageId 不可为空");
        }
        this.checkpoint = checkpoint;
        this.firstRetainedMessageId = firstRetainedMessageId;
        this.modelState = modelState;
        this.usage = usage;
    }

    /** 返回未压缩、未计量且无 Provider 状态的模型上下文。 */
    public static ModelContext empty() { return EMPTY; }
    /** 返回最近一次压缩检查点。 */
    public ContextCompactionCheckpoint getCheckpoint() { return checkpoint; }
    /** 返回第一条保留消息 ID。 */
    public String getFirstRetainedMessageId() { return firstRetainedMessageId; }
    /** 返回 Provider 私有状态。 */
    public ModelState getModelState() { return modelState; }
    /** 返回最近一次 token 计量。 */
    public ModelContextUsage getUsage() { return usage; }
}
