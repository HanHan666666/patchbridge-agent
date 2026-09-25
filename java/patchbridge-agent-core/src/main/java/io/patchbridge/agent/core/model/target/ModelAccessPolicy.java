package io.patchbridge.agent.core.model.target;

/** 宿主模型授权策略；目录过滤和每次实际使用均调用同一规则。 */
public interface ModelAccessPolicy {
    /** 是否允许可信调用者使用目标；不得以 Browser 传入的身份作判断。 */
    boolean canUse(ModelAccessContext access, ResolvedModelTarget target);
}
