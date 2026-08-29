package io.patchbridge.agent.core.model;

import java.util.List;

/**
 * Provider 私有状态在上下文压缩边界上的投影 SPI。
 *
 * <p>只有状态所属 Provider 能解释 format/data。压缩服务把保留消息交回该 SPI，Browser
 * 和通用 Core 均不得通过清空或猜测字段来构造下一份状态。
 */
public interface ModelStateProjector {

    /**
     * 只保留 retainedMessages 仍需要的 Provider 私有状态。
     *
     * @param state 当前工作上下文对应的可空状态
     * @param retainedMessages 压缩后继续作为真实模型输入的消息
     * @return 与保留消息严格对应的可空状态
     */
    ModelState project(ModelState state, List<AgentMessage> retainedMessages);
}
