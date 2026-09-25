package io.patchbridge.agent.core.compaction;

import io.patchbridge.agent.core.conversation.ContextCompactionTrigger;
import io.patchbridge.agent.core.model.AgentMessage;
import io.patchbridge.agent.core.model.ModelState;

import io.patchbridge.agent.core.model.target.ModelTargetRef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次上下文压缩的厂商中立请求。
 *
 * <p>Browser 负责选择 Tool 安全边界；服务端仍通过 Provider 投影两组消息对应的状态，
 * 并使用当前模型生成摘要。列表均为本次调用快照，不持有 Browser 可变数组。
 */
public final class ContextCompactionRequest {

    /** 与摘要和状态投影共同绑定的目标。 */
    private final ModelTargetRef modelTarget;
    /** 自动或手动触发来源。 */
    private final ContextCompactionTrigger trigger;
    /** 固定 system 消息与淘汰历史前缀。 */
    private final List<AgentMessage> messagesToSummarize;
    /** 固定 system 消息与近期真实消息。 */
    private final List<AgentMessage> retainedMessages;
    /** 重复压缩时需要合并的上一份摘要。 */
    private final String previousSummary;
    /** 当前工作上下文对应的 Provider 私有状态。 */
    private final ModelState modelState;
    /** 摘要 Assistant 的稳定响应 ID。 */
    private final String responseMessageId;
    /** 是否在 assistant 起点切分当前回合。 */
    private final boolean splitTurn;
    /** 创建不可变压缩请求。 */
    public ContextCompactionRequest(
            ModelTargetRef modelTarget,
            ContextCompactionTrigger trigger,
            List<AgentMessage> messagesToSummarize,
            List<AgentMessage> retainedMessages,
            String previousSummary,
            ModelState modelState,
            String responseMessageId,
            boolean splitTurn) {
        if (trigger == null) {
            throw new IllegalArgumentException("contextCompaction.trigger 不可为空");
        }
        if (modelTarget == null) throw new IllegalArgumentException("modelTarget 不可为空");
        this.modelTarget = modelTarget;
        this.trigger = trigger;
        this.messagesToSummarize = snapshot(messagesToSummarize, "messagesToSummarize");
        this.retainedMessages = snapshot(retainedMessages, "retainedMessages");
        if (this.messagesToSummarize.isEmpty()) {
            throw new IllegalArgumentException("messagesToSummarize 不可为空");
        }
        if (this.retainedMessages.isEmpty()) {
            throw new IllegalArgumentException("retainedMessages 不可为空");
        }
        if (previousSummary != null && previousSummary.trim().isEmpty()) {
            throw new IllegalArgumentException("previousSummary 不允许为空字符串");
        }
        if (responseMessageId == null || responseMessageId.trim().isEmpty()) {
            throw new IllegalArgumentException("responseMessageId 不可为空");
        }
        this.previousSummary = previousSummary;
        this.modelState = modelState;
        this.responseMessageId = responseMessageId;
        this.splitTurn = splitTurn;
    }

    /** 返回本次摘要使用的精确模型目标。 */
    public ModelTargetRef getModelTarget() { return modelTarget; }
    /** 返回触发来源。 */
    public ContextCompactionTrigger getTrigger() { return trigger; }
    /** 返回被摘要的真实消息快照。 */
    public List<AgentMessage> getMessagesToSummarize() { return messagesToSummarize; }
    /** 返回压缩后保留的真实消息快照。 */
    public List<AgentMessage> getRetainedMessages() { return retainedMessages; }
    /** 返回上一份摘要。 */
    public String getPreviousSummary() { return previousSummary; }
    /** 返回 Provider 私有状态。 */
    public ModelState getModelState() { return modelState; }
    /** 返回摘要响应 ID。 */
    public String getResponseMessageId() { return responseMessageId; }
    /** 表示切分边界是否位于 assistant 起点。 */
    public boolean isSplitTurn() { return splitTurn; }
    /** 复制消息列表并拒绝空引用。 */
    private static List<AgentMessage> snapshot(List<AgentMessage> messages, String field) {
        if (messages == null || messages.contains(null)) {
            throw new IllegalArgumentException(field + " 不可为空或包含 null");
        }
        return Collections.unmodifiableList(new ArrayList<AgentMessage>(messages));
    }
}
