package io.patchbridge.agent.core.model;

/**
 * Provider 输出的厂商中立流式事件。
 *
 * <p>浏览器只消费这些稳定事件，不接触厂商 SSE chunk、结束标记或字段名。Provider 必须在发布事件前完成厂商协议解析，Controller 只负责统一 JSON 序列化。
 */
public interface ModelStreamEvent {
    /** 事件类型。 */
    enum Type {
        /** 新内容块开始。 */
        BLOCK_START("block-start"),
        /** 内容块收到增量。 */
        BLOCK_DELTA("block-delta"),
        /** 内容块结束。 */
        BLOCK_STOP("block-stop"),
        /** 本次 assistant 消息结束。 */
        MESSAGE_STOP("message-stop");

        /** 浏览器协议稳定值。 */
        private final String wireValue;

        /** 保存稳定协议值。 */
        Type(String wireValue) {
            this.wireValue = wireValue;
        }

        /** 返回浏览器协议值。 */
        public String getWireValue() {
            return wireValue;
        }
    }

    /** 返回稳定事件类型。 */
    Type getType();
}
