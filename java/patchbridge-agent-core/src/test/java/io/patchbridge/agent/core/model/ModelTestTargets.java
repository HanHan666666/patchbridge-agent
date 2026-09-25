package io.patchbridge.agent.core.model;

import io.patchbridge.agent.core.compaction.ContextCompactionSettings;
import io.patchbridge.agent.core.model.target.*;
import java.util.Collections;
import java.util.List;

/** 为 Core 契约测试提供单一显式目标，保留真实 Router 的校验边界。 */
public final class ModelTestTargets {
    /** 测试使用的稳定目标引用。 */
    public static final ModelTargetRef REF = new ModelTargetRef("test-model", 1);
    /** 测试辅助类不实例化。 */
    private ModelTestTargets() { }
    /** 使用宽裕窗口与无状态投影验证基础调用生命周期。 */
    public static ModelProviderRouter router(ModelProvider provider) {
        return router(provider, (state, retained) -> state, new ContextCompactionSettings(1000000, 20000, 10000));
    }
    /** 只测试摘要入口时，模型调用由注入的 Gateway 替身提供。 */
    public static ModelProviderRouter router(ModelStateProjector projector, ContextCompactionSettings settings) {
        return router((request, listener) -> {
            throw new AssertionError("摘要测试不得绕过 Gateway 调用 Provider");
        }, projector, settings);
    }
    /** 注入压缩测试的真实窗口和专用状态投影器。 */
    public static ModelProviderRouter router(ModelProvider provider, ModelStateProjector projector,
            ContextCompactionSettings settings) {
        ModelProtocolAdapter adapter = new ModelProtocolAdapter() {
            /** Core 测试的编码预检由真实 Router 完成。 */
            @Override public void validate(ModelRequest request) { }
            /** 调用测试原有的模型替身。 */
            @Override public ModelCall stream(ModelRequest request, ModelStreamListener listener) {
                return provider.stream(request, listener);
            }
            /** 压缩测试原有的私有状态投影替身。 */
            @Override public ModelState project(ModelState state, List<AgentMessage> retained) {
                return projector.project(state, retained);
            }
        };
        ResolvedModelTarget target = new ResolvedModelTarget(REF, "测试模型", "test", true,
                true, true, settings, adapter);
        return new ModelProviderRouter(new ImmutableModelTargetCatalog(
                Collections.singletonList(target), REF), (access, selected) -> true);
    }
}
