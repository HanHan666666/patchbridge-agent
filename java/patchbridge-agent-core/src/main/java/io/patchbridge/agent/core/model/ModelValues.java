package io.patchbridge.agent.core.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Core JSON 值的不可变复制工具，防止请求快照被宿主线程后续修改。 */
final class ModelValues {
    /** 工具类不允许实例化。 */
    private ModelValues() {}

    /**
     * 递归复制 JSON 兼容值。
     *
     * <p>Core 不引入 JSON 库，因此只接受 null、String、Boolean、标准不可变数字、List 和字符串键 Map。数字必须显式白名单，不能笼统接受
     * {@link Number}：宿主自定义 Number 或 AtomicInteger 等类型可能在快照创建后继续变化，破坏模型请求与 ModelState 的深度不可变契约。
     */
    static Object immutableJson(Object value) {
        if (value instanceof Double
                && (((Double) value).isNaN() || ((Double) value).isInfinite())) {
            throw new IllegalArgumentException("JSON 数字必须是有限值");
        }
        if (value instanceof Float && (((Float) value).isNaN() || ((Float) value).isInfinite())) {
            throw new IllegalArgumentException("JSON 数字必须是有限值");
        }
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (isImmutableJsonNumber(value)) {
            return value;
        }
        if (value instanceof List) {
            List<?> source = (List<?>) value;
            List<Object> copy = new ArrayList<Object>(source.size());
            for (Object item : source) {
                copy.add(immutableJson(item));
            }
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Map) {
            Map<?, ?> source = (Map<?, ?>) value;
            Map<String, Object> copy = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    throw new IllegalArgumentException("JSON 对象键必须是字符串");
                }
                copy.put((String) entry.getKey(), immutableJson(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        throw new IllegalArgumentException("不支持的 JSON 值类型: " + value.getClass().getName());
    }

    /**
     * 只接受 JDK 中值语义稳定的 JSON 数字实现。
     *
     * <p>BigInteger 与 BigDecimal 虽有复杂内部表示，但对外是不可变值对象；Float 与 Double 的非有限值已在入口明确拒绝。
     */
    private static boolean isImmutableJsonNumber(Object value) {
        Class<?> type = value.getClass();
        return type == Byte.class
                || type == Short.class
                || type == Integer.class
                || type == Long.class
                || type == BigInteger.class
                || type == BigDecimal.class
                || type == Float.class
                || type == Double.class;
    }

    /** 复制并收窄为不可变字符串键对象。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> immutableObject(Map<String, Object> value) {
        if (value == null) {
            throw new IllegalArgumentException("JSON 对象不可为空");
        }
        return (Map<String, Object>) immutableJson(value);
    }
}
