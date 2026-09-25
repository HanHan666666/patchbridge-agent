package io.patchbridge.agent.core.conversation;

import io.patchbridge.agent.core.model.ModelTestTargets;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.patchbridge.agent.core.model.AgentMessage;
import io.patchbridge.agent.core.model.ContentBlock;
import io.patchbridge.agent.core.model.MessageRole;
import io.patchbridge.agent.core.model.ModelState;
import io.patchbridge.agent.core.model.TextBlock;
import io.patchbridge.agent.core.model.ToolResultBlock;
import io.patchbridge.agent.core.model.ToolResultStatus;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 验证 Conversation 稳定 JSON 值映射只接受唯一的 Message + ContentBlock 协议。 */
class ConversationContextValuesTest {

    /** 合法上下文经过值映射往返后保持相同稳定结构。 */
    @Test
    void roundTripsStableContextValue() {
        Map<String, Object> stateData = new LinkedHashMap<String, Object>();
        stateData.put("opaque", "value");
        ConversationContext context =
                new ConversationContext(
                        Collections.singletonList(
                                new AgentMessage(
                                        "m-1",
                                        MessageRole.USER,
                                        Collections.<ContentBlock>singletonList(
                                                new TextBlock("你好")))),
                        ModelTestTargets.REF,
                        new ModelContext(
                                null,
                                null,
                                new ModelState("provider/v1", stateData),
                                new ModelContextUsage(
                                        42L,
                                        ModelContextUsage.Source.PROVIDER,
                                        "m-1", 77)));

        Map<String, Object> value = ConversationContextValues.toValue(context);
        ConversationContext restored = ConversationContextValues.fromValue(value);

        assertEquals(value, ConversationContextValues.toValue(restored));
    }

    /** modelContext 必须显式出现，缺失不能被静默解释成空状态。 */
    @Test
    void rejectsMissingModelContext() {
        Map<String, Object> context = new LinkedHashMap<String, Object>();
        context.put("messages", Collections.emptyList());

        assertThrows(
                IllegalArgumentException.class, () -> ConversationContextValues.fromValue(context));
    }

    /** role/content 形状不属于领域消息契约，必须明确拒绝。 */
    @Test
    void rejectsMessageWithoutStableIdentityAndBlocks() {
        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("role", "user");
        message.put("content", "旧消息");
        Map<String, Object> context = new LinkedHashMap<String, Object>();
        context.put("messages", Collections.singletonList(message));
        context.put(
                "modelContext",
                ConversationContextValues.toModelContextValue(ModelContext.empty()));

        assertThrows(
                IllegalArgumentException.class, () -> ConversationContextValues.fromValue(context));
    }

    /** 新协议也拒绝额外字段，避免拼错字段被 Jackson 静默丢失。 */
    @Test
    void rejectsUnknownContextField() {
        Map<String, Object> context = new LinkedHashMap<String, Object>();
        context.put("messages", Collections.emptyList());
        context.put(
                "modelContext",
                ConversationContextValues.toModelContextValue(ModelContext.empty()));
        context.put("fallbackMessages", Collections.emptyList());

        assertThrows(
                IllegalArgumentException.class, () -> ConversationContextValues.fromValue(context));
    }    /** 执行事实逐种往返，缺字段必须拒绝，不能把中止记录恢复为真实结果。 */
    @Test
    void roundTripsToolExecutionFactsAndRejectsMissingExecution() {
        for (ToolResultBlock.Execution execution : ToolResultBlock.Execution.values()) {
            AgentMessage message = new AgentMessage("tool-1", MessageRole.TOOL,
                    Collections.<ContentBlock>singletonList(new ToolResultBlock(
                            "call-1", "local.action", ToolResultStatus.ERROR, execution,
                            Collections.singletonList(new TextBlock("不依赖提示文本恢复状态")))));
            java.util.List<Map<String, Object>> values = ConversationContextValues.toMessagesValue(
                    Collections.singletonList(message));
            ToolResultBlock restored = (ToolResultBlock) ConversationContextValues.fromMessagesValue(values)
                    .get(0).getBlocks().get(0);
            assertEquals(execution, restored.getExecution());
            Map<?, ?> block = (Map<?, ?>) ((java.util.List<?>) values.get(0).get("blocks")).get(0);
            block.remove("execution");
            assertThrows(IllegalArgumentException.class,
                    () -> ConversationContextValues.fromMessagesValue(values));
        }
    }

    /** 目录基线与 usage 来源一致，损坏或缺失不能静默当成零。 */
    @Test
    void validatesToolDefinitionBaseline() {
        assertThrows(IllegalArgumentException.class,
                () -> new ModelContextUsage(10, ModelContextUsage.Source.PROVIDER, null, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelContextUsage(10, ModelContextUsage.Source.ESTIMATED, null, 1));
        Map<String, Object> value = ConversationContextValues.toModelContextValue(new ModelContext(
                null, null, null, new ModelContextUsage(10, ModelContextUsage.Source.PROVIDER, null, 7)));
        ((Map<?, ?>) value.get("usage")).remove("toolDefinitionTokens");
        assertThrows(IllegalArgumentException.class,
                () -> ConversationContextValues.fromModelContextValue(value));
    }

}
