package io.patchbridge.agent.starter.model;

import io.patchbridge.agent.core.compaction.ContextCompactionSettings;
import io.patchbridge.agent.core.model.target.*;
import io.patchbridge.agent.starter.PatchBridgeAgentProperties;

import java.util.*;

/** application.yml 唯一配置来源的启动装配；缺失协议或不完整配置直接阻止启动。 */
public final class PropertiesModelTargetCatalog {
    /** 仅在装配期创建不可变目录。 */
    private PropertiesModelTargetCatalog() {}

    /** 所有目标一次校验完成后发布，不保留单模型配置兼容分支。 */
    public static ModelTargetCatalog create(
            PatchBridgeAgentProperties.Models configuration,
            List<ModelProtocolAdapterFactory> factories) {
        Map<String, ModelProtocolAdapterFactory> protocols =
                new LinkedHashMap<String, ModelProtocolAdapterFactory>();
        for (ModelProtocolAdapterFactory factory : factories) {
            if (protocols.put(factory.protocol(), factory) != null)
                throw new IllegalArgumentException("重复模型协议 Adapter");
        }
        List<ResolvedModelTarget> targets = new ArrayList<ResolvedModelTarget>();
        ModelTargetRef defaultRef = null;
        for (Map.Entry<String, PatchBridgeAgentProperties.Target> entry :
                configuration.getTargets().entrySet()) {
            PatchBridgeAgentProperties.Target item = entry.getValue();
            if (item == null
                    || item.getRoutingRevision() == null
                    || item.getContextWindowTokens() == null
                    || item.getImageInput() == null
                    || item.getToolCalling() == null) {
                throw new IllegalArgumentException(
                        "目标必须明确配置 routing-revision、context-window-tokens、image-input 与"
                            + " tool-calling");
            }
            ModelProtocolAdapterFactory factory = protocols.get(item.getProtocol());
            if (factory == null)
                throw new IllegalArgumentException("模型目标引用了未注册协议：" + item.getProtocol());
            ModelTargetRef ref = new ModelTargetRef(entry.getKey(), item.getRoutingRevision());
            targets.add(
                    new ResolvedModelTarget(
                            ref,
                            item.getDisplayName(),
                            item.getProtocol(),
                            item.isEnabled(),
                            item.getImageInput(),
                            item.getToolCalling(),
                            new ContextCompactionSettings(
                                    item.getContextWindowTokens(),
                                    item.getKeepRecentTokens(),
                                    item.getReservedOutputTokens()),
                            factory.create(item)));
            if (entry.getKey().equals(configuration.getDefaultTarget())) defaultRef = ref;
        }
        if (configuration.getDefaultTarget() != null && defaultRef == null)
            throw new IllegalArgumentException("default-target 不在模型目录中");
        return new ImmutableModelTargetCatalog(targets, defaultRef);
    }
}
