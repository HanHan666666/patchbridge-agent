package io.patchbridge.agent.core.schema;

import io.patchbridge.agent.annotations.AiParam;
import io.patchbridge.agent.core.context.AiRequestContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖设计不变量：
 * 1. AiRequestContext 参数绝不进入 Schema（可信上下文注入，防 LLM 伪造）；
 * 2. required 只来自显式声明；
 * 3. 常见企业 DTO 结构（嵌套 / 集合 / 枚举 / Map）能正确展开；
 * 4. 非法输入（重名、循环引用、参数名不可解析）必须启动期失败。
 */
class SimpleReflectionSchemaGeneratorTest {

    enum Level { LOW, HIGH }

    static class DeviceDTO {
        public String sn;
        public Level level;
        public List<String> tags;
        public Map<String, Integer> metrics;
        public OwnerDTO owner;
    }

    static class OwnerDTO {
        public String name;
        public int age;
    }

    static class Node {
        public Node next;
    }

    @SuppressWarnings("unused")
    public Object sampleMethod(
            @AiParam(value = "设备序列号", required = true) String sn,
            @AiParam(value = "数量") int count,
            AiRequestContext context) {
        return null;
    }

    @SuppressWarnings("unused")
    public Object dtoMethod(@AiParam("设备") DeviceDTO device, List<String> plainNames) {
        return null;
    }

    @SuppressWarnings("unused")
    public Object dupNameMethod(@AiParam(name = "same") String a, @AiParam(name = "same") String b) {
        return null;
    }

    @SuppressWarnings("unused")
    public Object cycleMethod(Node node) {
        return null;
    }

    private final SimpleReflectionSchemaGenerator generator = new SimpleReflectionSchemaGenerator();

    private Method method(String name, Class<?>... params) throws NoSuchMethodException {
        return getClass().getMethod(name, params);
    }

    @Test
    @SuppressWarnings("unchecked")
    void contextParameterExcludedAndRequiredCollected() throws Exception {
        Map<String, Object> schema = generator.generate(
                method("sampleMethod", String.class, int.class, AiRequestContext.class));

        assertEquals("object", schema.get("type"));
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertEquals(2, props.size(), "AiRequestContext 必须被排除");
        assertTrue(props.containsKey("sn") && props.containsKey("count"));

        Map<String, Object> sn = (Map<String, Object>) props.get("sn");
        assertEquals("string", sn.get("type"));
        assertEquals("设备序列号", sn.get("description"));

        assertEquals(Arrays.asList("sn"), schema.get("required"), "required 只来自显式声明");
    }

    @Test
    @SuppressWarnings("unchecked")
    void nestedDtoListEnumMapExpanded() throws Exception {
        Map<String, Object> schema = generator.generate(
                method("dtoMethod", DeviceDTO.class, List.class));

        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        Map<String, Object> device = (Map<String, Object>) props.get("device");
        assertEquals("object", device.get("type"));

        Map<String, Object> deviceProps = (Map<String, Object>) device.get("properties");
        assertEquals(Arrays.asList("LOW", "HIGH"),
                ((Map<String, Object>) deviceProps.get("level")).get("enum"));

        Map<String, Object> tags = (Map<String, Object>) deviceProps.get("tags");
        assertEquals("array", tags.get("type"));
        assertEquals("string", ((Map<String, Object>) tags.get("items")).get("type"));

        Map<String, Object> metrics = (Map<String, Object>) deviceProps.get("metrics");
        assertEquals("object", metrics.get("type"));
        assertEquals("integer", ((Map<String, Object>) metrics.get("additionalProperties")).get("type"));

        Map<String, Object> owner = (Map<String, Object>) deviceProps.get("owner");
        assertNotNull(owner.get("properties"), "嵌套 POJO 必须展开");

        Map<String, Object> plainNames = (Map<String, Object>) props.get("plainNames");
        assertEquals("array", plainNames.get("type"));
        assertEquals("string", ((Map<String, Object>) plainNames.get("items")).get("type"),
                "方法签名上的泛型信息应被展开");
    }

    @Test
    void duplicateParameterNameFails() {
        assertThrows(IllegalArgumentException.class, () -> generator.generate(
                method("dupNameMethod", String.class, String.class)));
    }

    @Test
    void circularReferenceFails() {
        assertThrows(IllegalArgumentException.class, () ->
                generator.generate(method("cycleMethod", Node.class)));
    }
}
