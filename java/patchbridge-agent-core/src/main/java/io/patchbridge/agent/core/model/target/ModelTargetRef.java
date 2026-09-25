package io.patchbridge.agent.core.model.target;

import java.util.LinkedHashMap;
import java.util.Map;

/** 会话与单次模型调用共同持有的目标身份；配置变更必须显式推进修订。 */
public final class ModelTargetRef {
    /** 部署内稳定目标 ID，不携带凭据或厂商字段。 */
    private final String targetId;

    /** 影响协议、账户或窗口的配置变更修订。 */
    private final long routingRevision;

    /** 创建可在 Java 与 Browser 间精确往返的引用。 */
    public ModelTargetRef(String targetId, long routingRevision) {
        if (targetId == null
                || !targetId.matches("[A-Za-z0-9._-]{1,64}")
                || routingRevision <= 0
                || routingRevision > 9007199254740991L) {
            throw new IllegalArgumentException(
                    "modelTarget 必须包含合法 targetId 与正安全整数 routingRevision");
        }
        this.targetId = targetId;
        this.routingRevision = routingRevision;
    }

    /** 返回稳定目标 ID。 */
    public String getTargetId() {
        return targetId;
    }

    /** 返回调用必须精确匹配的配置修订。 */
    public long getRoutingRevision() {
        return routingRevision;
    }

    /** 输出仅含公开身份的稳定 JSON 值。 */
    public Map<String, Object> toValue() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("targetId", targetId);
        result.put("routingRevision", routingRevision);
        return result;
    }

    /** HTTP 与 JDBC 共用严格解析，不从缺失字段推测默认目标。 */
    public static ModelTargetRef fromValue(Object value) {
        if (!(value instanceof Map)) throw new IllegalArgumentException("modelTarget 必须是对象");
        Map<?, ?> map = (Map<?, ?>) value;
        Object id = map.get("targetId");
        Object revision = map.get("routingRevision");
        if (map.size() != 2 || !(id instanceof String) || !(revision instanceof Number)) {
            throw new IllegalArgumentException("modelTarget 仅允许 targetId 与 routingRevision");
        }
        double number = ((Number) revision).doubleValue();
        long integer = ((Number) revision).longValue();
        if (!Double.isFinite(number) || number != integer)
            throw new IllegalArgumentException("routingRevision 必须是整数");
        return new ModelTargetRef((String) id, integer);
    }

    /** 两个引用完全相等才允许普通会话保存与续跑。 */
    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ModelTargetRef)) return false;
        ModelTargetRef ref = (ModelTargetRef) other;
        return targetId.equals(ref.targetId) && routingRevision == ref.routingRevision;
    }

    /** 与身份相等规则一致，供不可变目录使用。 */
    @Override
    public int hashCode() {
        return 31 * targetId.hashCode() + Long.hashCode(routingRevision);
    }
}
