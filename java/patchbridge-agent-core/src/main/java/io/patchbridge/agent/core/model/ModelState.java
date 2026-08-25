package io.patchbridge.agent.core.model;

/**
 * Provider 拥有的模型协议连续状态。
 *
 * <p>Runtime 和 UI 只能保存、传递该值，不得解析或展示 data。format 是强制的协议 防误用边界；Provider 收到不匹配格式时必须明确失败，不能丢弃或尝试兼容。
 */
public final class ModelState {
    /** Provider 定义并版本化的状态格式。 */
    private final String format;

    /** 可 JSON 序列化且已递归冻结的不透明状态。 */
    private final Object data;

    /** 创建不可变模型状态。 */
    public ModelState(String format, Object data) {
        if (format == null || format.trim().isEmpty()) {
            throw new IllegalArgumentException("modelState.format 不可为空");
        }
        this.format = format;
        this.data = ModelValues.immutableJson(data);
    }

    /** 返回版本化状态格式。 */
    public String getFormat() {
        return format;
    }

    /** 返回 Provider 才能解释的不透明不可变数据。 */
    public Object getData() {
        return data;
    }
}
