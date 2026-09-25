package io.patchbridge.agent.core.model.target;

/** 目标查找、授权或兼容性失败；跨 HTTP 与 Java 保留稳定错误码。 */
public final class ModelTargetException extends RuntimeException {
    /** 不含凭据和上游正文的公共错误码。 */
    private final String code;

    /** 构造不可重试到其他目标的明确失败。 */
    public ModelTargetException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** 供端点统一映射 HTTP 状态与审计。 */
    public String getCode() {
        return code;
    }
}
