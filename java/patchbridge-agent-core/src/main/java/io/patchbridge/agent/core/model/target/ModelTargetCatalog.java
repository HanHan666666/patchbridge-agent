package io.patchbridge.agent.core.model.target;

import java.util.List;

/** 唯一模型配置来源的只读端口；宿主替换整个目录，不合并第二份配置。 */
public interface ModelTargetCatalog {
    /** 返回配置已校验且不可变的完整目录。 */
    List<ResolvedModelTarget> targets();

    /** 新草稿的显式默认引用；空目录允许无默认，不能任取第一个模型。 */
    ModelTargetRef defaultTarget();
}
