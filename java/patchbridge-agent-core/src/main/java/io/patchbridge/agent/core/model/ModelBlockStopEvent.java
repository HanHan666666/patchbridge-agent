package io.patchbridge.agent.core.model;

/** 某个模型输出内容块已经完整结束。 */
public final class ModelBlockStopEvent implements ModelStreamEvent {
    /** 被关闭的消息内块序号。 */
    private final int index;

    /** 创建块结束事件。 */
    public ModelBlockStopEvent(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("块 index 不可为负数");
        }
        this.index = index;
    }

    /** 返回事件类型。 */
    @Override
    public Type getType() {
        return Type.BLOCK_STOP;
    }

    /** 返回被关闭块序号。 */
    public int getIndex() {
        return index;
    }
}
