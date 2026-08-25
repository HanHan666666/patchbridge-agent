package io.patchbridge.agent.core.model;

/**
 * 厂商中立模型流监听器。
 *
 * <p>回调可能来自 Provider 的 I/O 线程，实现必须具备线程安全的终止控制。正常流应先 收到一个 {@link ModelMessageStopEvent}，再收到
 * onCompleted；取消不伪造终止回调。
 */
public interface ModelStreamListener {
    /** 收到一条已经完成厂商协议转换的结构化事件。 */
    void onEvent(ModelStreamEvent event);

    /** 结构化模型消息正常结束且上游连接已释放。 */
    void onCompleted();

    /** 上游、网络或协议转换失败。 */
    void onError(Throwable error);
}
