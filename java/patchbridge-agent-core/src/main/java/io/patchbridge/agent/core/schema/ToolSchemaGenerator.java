package io.patchbridge.agent.core.schema;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Java 方法 → 模型可见 inputSchema 的生成 SPI。
 *
 * <p>复杂 DTO 场景企业可替换为 victools 等成熟实现（通过独立 Adapter 模块接入，
 * 不进 core 依赖树）；本 SPI 只约定输出为标准 JSON Schema 的 Map 表示。
 */
public interface ToolSchemaGenerator {

    /**
     * 生成方法参数的对象型 JSON Schema。
     *
     * @throws IllegalArgumentException 参数名不可解析（未显式指定且无 -parameters 编译信息）、
     *                                  参数重名或类型结构无法表达（如循环引用）
     */
    Map<String, Object> generate(Method method);
}
