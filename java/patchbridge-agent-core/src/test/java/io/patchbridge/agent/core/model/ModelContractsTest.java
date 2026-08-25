package io.patchbridge.agent.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** 验证厂商中立模型契约的不可变性和非法状态拒绝规则。 */
class ModelContractsTest {

    /** Tool 参数必须递归冻结，调用方后续修改原 Map 不能污染本轮请求快照。 */
    @Test
    @SuppressWarnings("unchecked")
    void toolInputIsDeeplyImmutable() {
        List<Object> nested = new ArrayList<Object>();
        nested.add("before");
        Map<String, Object> input = new LinkedHashMap<String, Object>();
        input.put("items", nested);

        ToolCallBlock block = new ToolCallBlock("call-1", "local.test", input);
        nested.add("after");
        input.put("extra", true);

        List<Object> frozen = (List<Object>) block.getInput().get("items");
        assertEquals(Collections.singletonList("before"), frozen);
        assertThrows(UnsupportedOperationException.class, () -> frozen.add("forbidden"));
        assertThrows(
                UnsupportedOperationException.class, () -> block.getInput().put("forbidden", true));
    }

    /** Role 与 Block 的组合是 Core 不变量，任何 Adapter 都不能绕过。 */
    @Test
    void rejectsRoleAndBlockMismatch() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new AgentMessage(
                                "message-1",
                                MessageRole.USER,
                                Collections.<ContentBlock>singletonList(
                                        new ToolCallBlock(
                                                "call-1", "local.test", Collections.emptyMap()))));
    }

    /** responseMessageId 是模型状态关联主键，直接使用 Core SPI 时同样强制必填。 */
    @Test
    void requiresResponseMessageId() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ModelRequest(
                                " ",
                                null,
                                Collections.emptyList(),
                                Collections.emptyList(),
                                null,
                                null,
                                null));
    }

    /** ModelState 只能保存真正 JSON 值，禁止序列化器自行解释运行时对象或非有限数。 */
    @Test
    void rejectsNonJsonModelState() {
        assertThrows(
                IllegalArgumentException.class, () -> new ModelState("provider/v1", new Object()));
        assertThrows(
                IllegalArgumentException.class, () -> new ModelState("provider/v1", Double.NaN));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModelState("provider/v1", new AtomicInteger(1)));
    }

    /** JSON null 是合法的不透明值，Java Core 必须与 Browser JsonValue 契约一致。 */
    @Test
    void acceptsJsonNullModelStateData() {
        ModelState state = new ModelState("provider/v1", null);

        assertNull(state.getData());
    }
}
