package io.patchbridge.agent.core.model;

/** 单一协议的请求编码、状态投影与调用边界；配置已绑定到具体目标。 */
public interface ModelProtocolAdapter extends ModelProvider, ModelStateProjector {
    /** 无网络副作用的编码预检，普通调用和会话切换使用同一规则。 */
    void validate(ModelRequest request);
}
