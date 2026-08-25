package io.patchbridge.agent.core.model;

import java.util.Map;

/** 本轮暴露给模型的厂商中立工具定义。 */
public final class ModelToolDefinition {
    /** Tool Registry 中的完整稳定名称。 */
    private final String name;

    /** 引导模型选择工具的自然语言说明。 */
    private final String description;

    /** 不可变 JSON Schema 参数对象。 */
    private final Map<String, Object> inputSchema;

    /** 创建模型工具定义。 */
    public ModelToolDefinition(String name, String description, Map<String, Object> inputSchema) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("工具 name 不可为空");
        }
        if (description == null) {
            throw new IllegalArgumentException("工具 description 不可为空");
        }
        this.name = name;
        this.description = description;
        this.inputSchema = ModelValues.immutableObject(inputSchema);
    }

    /** 返回工具完整名称。 */
    public String getName() {
        return name;
    }

    /** 返回工具描述。 */
    public String getDescription() {
        return description;
    }

    /** 返回不可变输入 JSON Schema。 */
    public Map<String, Object> getInputSchema() {
        return inputSchema;
    }
}
