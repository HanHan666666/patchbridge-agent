package io.patchbridge.agent.core.model;

/**
 * 模型接入 SPI。
 *
 * <p>浏览器不持有模型 API Key：所有模型请求都经过服务端网关 （认证、审计、凭据保管、将来限流）。Provider 负责把厂商中立 ModelRequest
 * 编码为目标协议，并把上游流转换成结构化 ModelStreamEvent；它不做 Agent Loop， 也不拥有会话状态——那些属于浏览器 Runtime。
 */
public interface ModelProvider {

    /**
     * 发起异步流式模型调用并立即返回取消句柄。
     *
     * <p>正常结束必须调用 onCompleted，异步失败必须调用 onError；启动阶段无法建立 调用时可直接抛
     * ModelGatewayException。回调可能来自任意线程，实现必须保证 终止信号只发送一次。
     *
     * @throws io.patchbridge.agent.core.error.ModelGatewayException 启动阶段的配置或协议错误
     */
    ModelCall stream(ModelRequest request, ModelStreamListener listener);
}
