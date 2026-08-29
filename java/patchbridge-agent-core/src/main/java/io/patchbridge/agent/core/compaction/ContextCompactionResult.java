package io.patchbridge.agent.core.compaction;

import io.patchbridge.agent.core.model.ModelState;
import io.patchbridge.agent.core.model.ModelUsage;

/** 当前模型生成摘要并完成 Provider 状态投影后的原子结果。 */
public final class ContextCompactionResult {

    /** 当前模型生成的模型专用摘要。 */
    private final String summary;
    /** 摘要调用的必需 Provider 用量。 */
    private final ModelUsage usage;
    /** 只对应保留真实消息的 Provider 私有状态。 */
    private final ModelState modelState;

    /** 创建完整成功结果；空摘要或缺失 usage 都不能进入该类型。 */
    public ContextCompactionResult(String summary, ModelUsage usage, ModelState modelState) {
        if (summary == null || summary.trim().isEmpty()) {
            throw new IllegalArgumentException("contextCompaction.summary 不可为空");
        }
        if (usage == null) {
            throw new IllegalArgumentException("contextCompaction.usage 不可为空");
        }
        this.summary = summary;
        this.usage = usage;
        this.modelState = modelState;
    }

    /** 返回模型生成的摘要。 */
    public String getSummary() { return summary; }
    /** 返回摘要调用用量。 */
    public ModelUsage getUsage() { return usage; }
    /** 返回投影到保留消息的 Provider 状态。 */
    public ModelState getModelState() { return modelState; }
}
