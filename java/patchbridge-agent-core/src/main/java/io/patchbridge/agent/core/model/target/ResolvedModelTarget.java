package io.patchbridge.agent.core.model.target;

import io.patchbridge.agent.core.compaction.ContextCompactionSettings;
import io.patchbridge.agent.core.model.ModelProtocolAdapter;

import java.util.LinkedHashMap;
import java.util.Map;

/** 配置在装配期绑定协议实现；一次请求只使用这份不可变目标，不持有执行状态。 */
public final class ResolvedModelTarget {
    /** 目标身份与配置修订。 */
    private final ModelTargetRef ref;

    /** 用户可见名称。 */
    private final String displayName;

    /** 稳定协议标识，不是模型名称。 */
    private final String protocol;

    /** 由配置明确声明的图片输入能力。 */
    private final boolean imageInput;

    /** 由配置明确声明的工具调用能力。 */
    private final boolean toolCalling;

    /** 普通路由的可用状态。 */
    private final boolean enabled;

    /** 本目标唯一窗口预算。 */
    private final ContextCompactionSettings settings;

    /** 已绑定本目标地址、模型和凭据的协议实现，不出现在公开视图。 */
    private final ModelProtocolAdapter adapter;

    /** 创建完整且不可拆分的调用目标。 */
    public ResolvedModelTarget(
            ModelTargetRef ref,
            String displayName,
            String protocol,
            boolean enabled,
            boolean imageInput,
            boolean toolCalling,
            ContextCompactionSettings settings,
            ModelProtocolAdapter adapter) {
        if (ref == null
                || displayName == null
                || displayName.trim().isEmpty()
                || protocol == null
                || protocol.trim().isEmpty()
                || settings == null
                || adapter == null) {
            throw new IllegalArgumentException("模型目标配置不完整");
        }
        this.ref = ref;
        this.displayName = displayName;
        this.protocol = protocol;
        this.enabled = enabled;
        this.imageInput = imageInput;
        this.toolCalling = toolCalling;
        this.settings = settings;
        this.adapter = adapter;
    }

    /** 返回公开引用。 */
    public ModelTargetRef getRef() {
        return ref;
    }

    /** 返回协议标识。 */
    public String getProtocol() {
        return protocol;
    }

    /** 返回启用状态。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 返回图片支持声明。 */
    public boolean supportsImages() {
        return imageInput;
    }

    /** 返回工具支持声明。 */
    public boolean supportsTools() {
        return toolCalling;
    }

    /** 返回模型窗口的统一派生值。 */
    public ContextCompactionSettings getSettings() {
        return settings;
    }

    /** 仅服务端路由与状态投影可以使用协议实现。 */
    public ModelProtocolAdapter getAdapter() {
        return adapter;
    }

    /** 明确列出公开字段，避免 Jackson 自动序列化私有 Adapter 或配置。 */
    public Map<String, Object> toValue() {
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("ref", ref.toValue());
        value.put("displayName", displayName);
        value.put("protocol", protocol);
        value.put("imageInput", imageInput);
        value.put("toolCalling", toolCalling);
        Map<String, Object> budget = new LinkedHashMap<String, Object>();
        budget.put("contextWindowTokens", settings.getContextWindowTokens());
        budget.put("automaticThresholdTokens", settings.getAutomaticThresholdTokens());
        budget.put("keepRecentTokens", settings.getKeepRecentTokens());
        budget.put("reservedOutputTokens", settings.getReservedOutputTokens());
        value.put("configuration", budget);
        return value;
    }
}
