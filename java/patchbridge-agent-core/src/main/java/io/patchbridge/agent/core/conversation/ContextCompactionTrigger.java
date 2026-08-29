package io.patchbridge.agent.core.conversation;

/**
 * 上下文压缩触发来源。
 *
 * <p>该值进入持久化检查点和 UI，不使用 Java 枚举名作为协议，避免重命名破坏存量数据。
 */
public enum ContextCompactionTrigger {
    /** 模型工作上下文达到服务端派生的 80% 阈值。 */
    AUTOMATIC("automatic"),
    /** 用户在空闲状态主动请求压缩。 */
    MANUAL("manual");

    /** 稳定 JSON 协议值。 */
    private final String wireValue;

    /** 绑定稳定协议值。 */
    ContextCompactionTrigger(String wireValue) {
        this.wireValue = wireValue;
    }

    /** 返回跨语言协议值。 */
    public String getWireValue() {
        return wireValue;
    }

    /** 从不可信 JSON 协议值恢复触发来源。 */
    public static ContextCompactionTrigger fromWireValue(String value) {
        for (ContextCompactionTrigger trigger : values()) {
            if (trigger.wireValue.equals(value)) {
                return trigger;
            }
        }
        throw new IllegalArgumentException("不支持的上下文压缩触发来源: " + value);
    }
}
