package io.patchbridge.agent.core.schema;

import io.patchbridge.agent.annotations.AiParam;
import io.patchbridge.agent.core.context.AiRequestContext;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 零依赖的反射 Schema 生成器：覆盖企业 DTO 中最常见的结构
 * （基本类型 / 枚举 / 日期时间 / List / Map / 嵌套 POJO / 一维泛型数组）。
 *
 * <p>设计边界（为什么不追求完备）：
 * <ul>
 *   <li>循环引用直接报错而不是生成 $ref——简单实现必须让不支持的复杂度尽早暴露；</li>
 *   <li>嵌套 POJO 字段没有描述注解（@AiParam 只作用于方法参数层），字段全部视为可选；</li>
 *   <li>required 只来自 @AiParam(required=true) 的显式声明，不做“非空即必填”的隐式推断。</li>
 * </ul>
 * 超出能力边界的 DTO 应接入 victools Adapter（v0.2）。
 */
public class SimpleReflectionSchemaGenerator implements ToolSchemaGenerator {

    /** 嵌套深度上限：防御超深对象图导致 Schema 膨胀。 */
    private static final int MAX_DEPTH = 8;

    @Override
    public Map<String, Object> generate(Method method) {
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        List<String> required = new ArrayList<String>();

        for (Parameter parameter : method.getParameters()) {
            // AiRequestContext 由服务器注入，绝不能进入模型可生成的 Schema
            if (AiRequestContext.class.isAssignableFrom(parameter.getType())) {
                continue;
            }
            AiParam meta = parameter.getAnnotation(AiParam.class);
            String name = resolveParameterName(method, parameter, meta);
            if (properties.containsKey(name)) {
                throw new IllegalArgumentException(String.format(
                        "@AiTool 方法 %s#%s 存在重名参数: %s",
                        method.getDeclaringClass().getSimpleName(), method.getName(), name));
            }
            Map<String, Object> schema =
                    typeSchema(parameter.getParameterizedType(), 0, new HashSet<Type>());
            if (meta != null && !meta.value().isEmpty()) {
                schema.put("description", meta.value());
            }
            properties.put(name, schema);
            if (meta != null && meta.required()) {
                required.add(name);
            }
        }

        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("type", "object");
        root.put("properties", properties);
        if (!required.isEmpty()) {
            root.put("required", required);
        }
        root.put("additionalProperties", Boolean.FALSE);
        return root;
    }

    /**
     * 参数名解析：@AiParam.name 显式指定优先；否则要求编译期保留了形参名。
     * 解析不到直接失败——生成匿名参数（arg0）会让模型传错参数名，属于静默事故。
     */
    private String resolveParameterName(Method method, Parameter parameter, AiParam meta) {
        if (meta != null && !meta.name().isEmpty()) {
            return meta.name();
        }
        if (parameter.isNamePresent()) {
            return parameter.getName();
        }
        throw new IllegalArgumentException(String.format(
                "@AiTool 方法 %s#%s 的参数名不可解析（宿主项目未开启 -parameters 编译），"
                        + "请在 @AiParam 中显式指定 name",
                method.getDeclaringClass().getSimpleName(), method.getName()));
    }

    /** 递归生成类型 Schema。 */
    private Map<String, Object> typeSchema(Type type, int depth, Set<Type> visiting) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("类型嵌套超过 " + MAX_DEPTH + " 层，Schema 过深");
        }
        Class<?> raw = erase(type);

        // 数组与集合
        if (raw.isArray()) {
            Type component = raw.getComponentType();
            return arraySchema(component, depth, visiting);
        }
        if (Collection.class.isAssignableFrom(raw)) {
            return arraySchema(elementTypeOf(type, raw), depth, visiting);
        }
        // Map：键约定为 string，值类型来自泛型
        if (Map.class.isAssignableFrom(raw)) {
            Map<String, Object> schema = new LinkedHashMap<String, Object>();
            schema.put("type", "object");
            Type valueType = (type instanceof ParameterizedType)
                    ? ((ParameterizedType) type).getActualTypeArguments()[1] : Object.class;
            schema.put("additionalProperties",
                    valueType == Object.class ? Boolean.TRUE : typeSchema(valueType, depth, visiting));
            return schema;
        }
        return scalarOrObjectSchema(type, raw, depth, visiting);
    }

    private Map<String, Object> scalarOrObjectSchema(Type type, Class<?> raw, int depth, Set<Type> visiting) {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();

        if (raw == String.class || raw == char.class || raw == Character.class
                || raw == CharSequence.class || raw == UUID.class) {
            schema.put("type", "string");
            return schema;
        }
        if (raw == boolean.class || raw == Boolean.class) {
            schema.put("type", "boolean");
            return schema;
        }
        if (raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class
                || raw == short.class || raw == Short.class || raw == byte.class || raw == Byte.class
                || raw == BigInteger.class) {
            schema.put("type", "integer");
            return schema;
        }
        if (raw == double.class || raw == Double.class || raw == float.class || raw == Float.class
                || raw == BigDecimal.class || Number.class.isAssignableFrom(raw)) {
            schema.put("type", "number");
            return schema;
        }
        if (raw.isEnum()) {
            schema.put("type", "string");
            List<String> values = new ArrayList<String>();
            for (Object constant : raw.getEnumConstants()) {
                values.add(((Enum<?>) constant).name());
            }
            schema.put("enum", values);
            return schema;
        }
        if (raw == LocalDate.class) {
            schema.put("type", "string");
            schema.put("format", "date");
            return schema;
        }
        if (raw == LocalTime.class) {
            schema.put("type", "string");
            schema.put("format", "time");
            return schema;
        }
        if (raw == LocalDateTime.class || raw == Instant.class || raw == ZonedDateTime.class
                || raw == OffsetDateTime.class || Date.class.isAssignableFrom(raw)) {
            schema.put("type", "string");
            schema.put("format", "date-time");
            return schema;
        }
        if (raw == Object.class) {
            // 自由结构：交由模型按需生成（如透传业务 JSON）
            return schema;
        }

        // POJO
        if (!visiting.add(raw)) {
            throw new IllegalArgumentException("类型存在循环引用，简单 Schema 生成器不支持: " + raw.getName());
        }
        try {
            schema.put("type", "object");
            Map<String, Object> properties = new LinkedHashMap<String, Object>();
            for (Field field : allInstanceFields(raw)) {
                properties.put(field.getName(),
                        typeSchema(field.getGenericType(), depth + 1, visiting));
            }
            schema.put("properties", properties);
            return schema;
        } finally {
            visiting.remove(raw);
        }
    }

    private Map<String, Object> arraySchema(Type componentType, int depth, Set<Type> visiting) {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "array");
        schema.put("items", typeSchema(componentType, depth, visiting));
        return schema;
    }

    /** 收集本类与父类的实例字段（static / transient / synthetic 排除），父类字段在前。 */
    private List<Field> allInstanceFields(Class<?> raw) {
        LinkedList<Class<?>> hierarchy = new LinkedList<Class<?>>();
        for (Class<?> c = raw; c != null && c != Object.class; c = c.getSuperclass()) {
            hierarchy.addFirst(c);
        }
        List<Field> fields = new ArrayList<Field>();
        for (Class<?> c : hierarchy) {
            for (Field field : c.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)
                        || field.isSynthetic()) {
                    continue;
                }
                field.setAccessible(true);
                fields.add(field);
            }
        }
        return fields;
    }

    private Class<?> erase(Type type) {
        if (type instanceof Class) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            return erase(((ParameterizedType) type).getRawType());
        }
        if (type instanceof GenericArrayType) {
            return Object[].class;
        }
        throw new IllegalArgumentException("无法解析的类型: " + type);
    }

    /** 提取 Collection 泛型元素类型；无泛型信息时按 Object 处理（自由结构）。 */
    private Type elementTypeOf(Type type, Class<?> raw) {
        if (type instanceof ParameterizedType) {
            return ((ParameterizedType) type).getActualTypeArguments()[0];
        }
        // 沿父类链找 Collection 的参数化定义
        for (Class<?> c = raw; c != null && c != Object.class; c = c.getSuperclass()) {
            Type genericSuper = c.getGenericSuperclass();
            if (genericSuper instanceof ParameterizedType) {
                ParameterizedType pt = (ParameterizedType) genericSuper;
                if (pt.getRawType() instanceof Class
                        && Collection.class.isAssignableFrom((Class<?>) pt.getRawType())) {
                    return pt.getActualTypeArguments()[0];
                }
            }
        }
        return Object.class;
    }
}
