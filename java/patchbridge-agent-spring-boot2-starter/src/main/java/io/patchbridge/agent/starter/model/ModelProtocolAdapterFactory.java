package io.patchbridge.agent.starter.model;

import io.patchbridge.agent.core.model.ModelProtocolAdapter;
import io.patchbridge.agent.starter.PatchBridgeAgentProperties;

/** 部署配置到协议 Adapter 的装配端口；扩展协议只新增工厂，不修改 Router 或 Browser。 */
public interface ModelProtocolAdapterFactory {
    /** 唯一且稳定的协议 ID。 */
    String protocol();

    /** 根据一份完整配置创建绑定该目标的 Adapter。 */
    ModelProtocolAdapter create(PatchBridgeAgentProperties.Target configuration);
}
