package io.patchbridge.agent.core.model;

/** 单条 assistant 消息的模型生成终点，先于监听器 onCompleted 发布。 */
public final class ModelMessageStopEvent implements ModelStreamEvent {
    /** 标准化停止原因。 */
    private final ModelStopReason stopReason;

    /** 下一轮连续状态，可为空。 */
    private final ModelState modelState;

    /** 厂商报告的 token 用量，可为空且不得估算。 */
    private final ModelUsage usage;

    /** 创建消息结束事件。 */
    public ModelMessageStopEvent(
            ModelStopReason stopReason, ModelState modelState, ModelUsage usage) {
        if (stopReason == null) {
            throw new IllegalArgumentException("stopReason 不可为空");
        }
        this.stopReason = stopReason;
        this.modelState = modelState;
        this.usage = usage;
    }

    /** 返回事件类型。 */
    @Override
    public Type getType() {
        return Type.MESSAGE_STOP;
    }

    /** 返回标准化停止原因。 */
    public ModelStopReason getStopReason() {
        return stopReason;
    }

    /** 返回下一轮模型连续状态。 */
    public ModelState getModelState() {
        return modelState;
    }

    /** 返回可选 token 用量。 */
    public ModelUsage getUsage() {
        return usage;
    }
}
