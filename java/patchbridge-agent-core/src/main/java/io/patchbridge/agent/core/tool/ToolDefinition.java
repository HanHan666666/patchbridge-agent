package io.patchbridge.agent.core.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一 Tool 定义（不可变）。
 *
 * <p>无论来源是本地 @AiTool、现有 REST API 还是远程 MCP Server，最终都收敛为本结构，
 * 通过 Unified Tool Gateway 对浏览器暴露。name 使用带命名空间的全名
 * （如 local.device_get、mcp.inventory.query_stock），进入 LLM Schema、RBAC、审计链路后保持稳定。
 *
 * <p>inputSchema 是标准 JSON Schema 的 Map 表示（object 类型），序列化由上层完成，
 * 以保持 core 零 JSON 依赖。
 */
public final class ToolDefinition {

    /** 带命名空间的全局唯一名称。 */
    private final String name;

    /** 面向用户展示的可读标题。 */
    private final String title;

    /** 面向模型的能力描述。 */
    private final String description;

    /** 模型可生成参数的 JSON Schema。 */
    private final Map<String, Object> inputSchema;

    /** 描述副作用、幂等性与审批要求的行为元数据。 */
    private final ToolAnnotations annotations;

    /** Tool 的实现来源，用于路由与审计。 */
    private final ToolSource source;

    /**
     * 宿主业务定义的全部权限标识。
     *
     * <p>Core 只保证元数据完整、不可变，不规定 ALL、ANY 或更复杂的判定语义。
     */
    private final List<String> permissions;

    /**
     * 服务端认可的定义/路由版本引用；动态来源（如 MCP）每次发布定义时计算，
     * 静态来源（如启动期 @AiTool）为 {@code null}。
     *
     * <p>该引用随发现结果下发给浏览器，并在调用时原样回传：Server 据此确认
     * “模型看到的定义”与“本次实际路由的目标”仍然一致，定义或路由发生语义变化后
     * 旧引用明确失败，而不是静默执行新目标。它只是一致性凭证，不能替代
     * 每次调用的权限检查与启停状态检查。
     */
    private final String version;

    /**
     * 创建不可变 Tool 定义。
     *
     * @param name 带命名空间的全局名称
     * @param title 人类可读标题
     * @param description 模型可见描述
     * @param inputSchema 模型输入 Schema
     * @param annotations 行为元数据
     * @param source Tool 来源
     * @param permissions 宿主业务权限标识集合
     * @param version 定义/路由版本引用；静态来源传 {@code null}
     */
    public ToolDefinition(String name, String title, String description,
                          Map<String, Object> inputSchema, ToolAnnotations annotations,
                          ToolSource source, List<String> permissions, String version) {
        this.name = name;
        this.title = title;
        this.description = description;
        if (inputSchema == null) {
            throw new IllegalArgumentException("inputSchema 不能为空");
        }
        this.inputSchema = immutableMap(inputSchema,
                new IdentityHashMap<Object, Boolean>());
        this.annotations = annotations;
        this.source = source;
        if (permissions == null) {
            throw new IllegalArgumentException("permissions 不能为空，请显式传入空列表");
        }
        this.permissions = Collections.unmodifiableList(new ArrayList<String>(permissions));
        this.version = version;
    }

    /** 命名空间全名，全局唯一。 */
    public String getName() {
        return name;
    }

    /** 人类可读标题（可空）。 */
    public String getTitle() {
        return title;
    }

    /** 给模型的能力描述。 */
    public String getDescription() {
        return description;
    }

    /** 返回模型可生成参数的 JSON Schema。 */
    public Map<String, Object> getInputSchema() {
        return inputSchema;
    }

    /** 返回 Tool 的行为元数据。 */
    public ToolAnnotations getAnnotations() {
        return annotations;
    }

    /** 返回 Tool 的实现来源。 */
    public ToolSource getSource() {
        return source;
    }

    /**
     * 返回全部权限元数据；具体组合语义由宿主 {@code ToolAccessPolicy} 决定。
     */
    public List<String> getPermissions() {
        return permissions;
    }

    /**
     * 返回定义/路由版本引用；静态来源为 {@code null}。
     *
     * <p>调用方必须把发现时取得的版本原样回传，Server 在调用时校验一致性。
     */
    public String getVersion() {
        return version;
    }

    /**
     * 递归复制 JSON Schema 对象，保证构造后的外部修改不会改变 Tool 契约。
     * Schema 必须是只由 Map、List 和 JSON 标量组成的无环结构。
     */
    private static Map<String, Object> immutableMap(
            Map<?, ?> source, IdentityHashMap<Object, Boolean> visiting) {
        enter(source, visiting);
        try {
            Map<String, Object> copy = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    throw new IllegalArgumentException("JSON Schema 的对象键必须是字符串");
                }
                copy.put((String) entry.getKey(), immutableValue(entry.getValue(), visiting));
            }
            return Collections.unmodifiableMap(copy);
        } finally {
            visiting.remove(source);
        }
    }

    /** 递归复制 Schema 数组，同时隔离嵌套对象引用。 */
    private static List<Object> immutableList(
            List<?> source, IdentityHashMap<Object, Boolean> visiting) {
        enter(source, visiting);
        try {
            List<Object> copy = new ArrayList<Object>(source.size());
            for (Object value : source) {
                copy.add(immutableValue(value, visiting));
            }
            return Collections.unmodifiableList(copy);
        } finally {
            visiting.remove(source);
        }
    }

    /** JSON 容器递归复制，标量值可安全共享。 */
    private static Object immutableValue(Object value,
                                         IdentityHashMap<Object, Boolean> visiting) {
        if (value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map) {
            return immutableMap((Map<?, ?>) value, visiting);
        }
        if (value instanceof List) {
            return immutableList((List<?>) value, visiting);
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<Object> elements = new ArrayList<Object>(length);
            for (int index = 0; index < length; index += 1) {
                elements.add(java.lang.reflect.Array.get(value, index));
            }
            return immutableList(elements, visiting);
        }
        throw new IllegalArgumentException("JSON Schema 包含非 JSON 值类型: "
                + value.getClass().getName());
    }

    /** 拒绝不是合法 JSON 结构的循环引用，避免构造时无限递归。 */
    private static void enter(Object value, IdentityHashMap<Object, Boolean> visiting) {
        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("JSON Schema 不能包含循环引用");
        }
    }
}
