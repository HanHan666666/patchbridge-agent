package io.patchbridge.agent.core.model.target;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 启动配置的不可变目录；重新部署生效，不引入管理数据库或热更新状态。 */
public final class ImmutableModelTargetCatalog implements ModelTargetCatalog {
    /** 原子装配的目标快照。 */
    private final List<ResolvedModelTarget> targets;

    /** 管理者明确指定的新会话初始目标。 */
    private final ModelTargetRef defaultTarget;

    /** 拒绝重复 ID、错误默认和无默认的非空可用目录。 */
    public ImmutableModelTargetCatalog(
            List<ResolvedModelTarget> targets, ModelTargetRef defaultTarget) {
        if (targets == null) throw new IllegalArgumentException("targets 不可为空");
        Set<String> ids = new HashSet<String>();
        boolean anyEnabled = false;
        boolean validDefault = false;
        for (ResolvedModelTarget target : targets) {
            if (target == null || !ids.add(target.getRef().getTargetId()))
                throw new IllegalArgumentException("模型目标 ID 重复或为空");
            anyEnabled |= target.isEnabled();
            validDefault |= target.isEnabled() && target.getRef().equals(defaultTarget);
        }
        if ((anyEnabled && !validDefault) || (!anyEnabled && defaultTarget != null)) {
            throw new IllegalArgumentException("存在可用目标时必须明确指定一个已启用 default-target");
        }
        this.targets = Collections.unmodifiableList(new ArrayList<ResolvedModelTarget>(targets));
        this.defaultTarget = defaultTarget;
    }

    /** 返回整份不可变目录。 */
    @Override
    public List<ResolvedModelTarget> targets() {
        return targets;
    }

    /** 默认目标只服务新会话，不参与失败切换。 */
    @Override
    public ModelTargetRef defaultTarget() {
        return defaultTarget;
    }
}
