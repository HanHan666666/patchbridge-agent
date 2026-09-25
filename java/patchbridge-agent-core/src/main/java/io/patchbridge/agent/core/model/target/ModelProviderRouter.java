package io.patchbridge.agent.core.model.target;

import io.patchbridge.agent.core.model.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 所有模型入口共用的目标解析、权限与能力边界；失败时绝不选择替代目标。 */
public final class ModelProviderRouter {
    /** 唯一配置来源。 */
    private final ModelTargetCatalog catalog;

    /** 宿主可替换的授权策略。 */
    private final ModelAccessPolicy policy;

    /** 装配目录与策略，不捕获目录故障沿用旧结果。 */
    public ModelProviderRouter(ModelTargetCatalog catalog, ModelAccessPolicy policy) {
        if (catalog == null || policy == null)
            throw new IllegalArgumentException("catalog / policy 不可为空");
        this.catalog = catalog;
        this.policy = policy;
    }

    /** 返回用户真正可调用的目标列表；实际调用仍会再次授权。 */
    public List<ResolvedModelTarget> available(ModelAccessContext access) {
        if (access == null) throw new IllegalArgumentException("access 不可为空");
        List<ResolvedModelTarget> result = new ArrayList<ResolvedModelTarget>();
        for (ResolvedModelTarget target : catalog.targets()) {
            if (target.isEnabled() && policy.canUse(access, target)) result.add(target);
        }
        return Collections.unmodifiableList(result);
    }

    /** 默认目标无权限时返回无初始选择，不能悄悄选其他模型。 */
    public ModelTargetRef defaultTarget(ModelAccessContext access) {
        ModelTargetRef ref = catalog.defaultTarget();
        for (ResolvedModelTarget target : available(access))
            if (target.getRef().equals(ref)) return ref;
        return null;
    }

    /** 精确检查身份、修订、启用和权限后返回一次调用的完整目标。 */
    public ResolvedModelTarget resolve(ModelTargetRef ref, ModelAccessContext access) {
        if (ref == null || access == null)
            throw new IllegalArgumentException("modelTarget / access 不可为空");
        for (ResolvedModelTarget target : catalog.targets()) {
            if (!target.getRef().getTargetId().equals(ref.getTargetId())) continue;
            if (!target.isEnabled())
                throw new ModelTargetException("MODEL_TARGET_DISABLED", "模型目标已停用");
            if (!policy.canUse(access, target))
                throw new ModelTargetException("MODEL_TARGET_FORBIDDEN", "无权使用此模型目标");
            if (!target.getRef().equals(ref))
                throw new ModelTargetException(
                        "MODEL_TARGET_REVISION_MISMATCH", "模型配置已更新，请显式切换到新修订");
            return target;
        }
        throw new ModelTargetException("MODEL_TARGET_NOT_FOUND", "模型目标不存在");
    }

    /** 与切换预检共用能力约束，并把输出限制固定在当前目标预留内。 */
    public ModelRequest prepare(ResolvedModelTarget target, ModelRequest request) {
        if (!target.getRef().equals(request.getModelTarget()))
            throw new IllegalArgumentException("调用目标不一致");
        if (!target.supportsTools() && !request.getTools().isEmpty()) incompatible("目标不支持工具调用");
        for (AgentMessage message : request.getMessages())
            for (ContentBlock block : message.getBlocks()) {
                if (block instanceof ImageBlock && !target.supportsImages())
                    incompatible("目标不支持图片输入");
                if ((block instanceof ToolCallBlock || block instanceof ToolResultBlock)
                        && !target.supportsTools()) incompatible("目标不能接续工具历史");
            }
        int limit = target.getSettings().getReservedOutputTokens();
        Integer requested = request.getMaxTokens();
        if (requested != null && (requested <= 0 || requested > limit))
            throw new IllegalArgumentException("maxTokens 超出目标输出预算");
        ModelRequest prepared =
                new ModelRequest(
                        request.getResponseMessageId(),
                        request.getModelTarget(),
                        request.getMessages(),
                        request.getTools(),
                        request.getModelState(),
                        request.getTemperature(),
                        requested == null ? limit : requested);
        target.getAdapter().validate(prepared);
        return prepared;
    }

    /** 将不可表示的输入作为明确契约失败，不做文本替换。 */
    private static void incompatible(String message) {
        throw new ModelTargetException("MODEL_TARGET_INCOMPATIBLE", message);
    }
}
