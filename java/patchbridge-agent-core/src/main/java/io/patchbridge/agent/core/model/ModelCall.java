package io.patchbridge.agent.core.model;

/**
 * 一次异步模型调用的取消句柄。
 *
 * <p>取消必须幂等，并尽快释放上游连接；它只描述生命周期，不暴露 OkHttp、
 * Reactor 或厂商 SDK 类型，因此 Servlet MVC 与 WebFlux Adapter 可共享同一 Core。
 */
public interface ModelCall {

    /** 请求停止上游模型流；重复调用不得产生额外副作用。 */
    void cancel();
}
