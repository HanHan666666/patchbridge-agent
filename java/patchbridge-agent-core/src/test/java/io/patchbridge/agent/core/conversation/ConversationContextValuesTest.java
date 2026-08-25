package io.patchbridge.agent.core.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.patchbridge.agent.core.model.AgentMessage;
import io.patchbridge.agent.core.model.ContentBlock;
import io.patchbridge.agent.core.model.MessageRole;
import io.patchbridge.agent.core.model.ModelState;
import io.patchbridge.agent.core.model.TextBlock;

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
                        new ModelState("provider/v1", stateData));

        Map<String, Object> value = ConversationContextValues.toValue(context);
        ConversationContext restored = ConversationContextValues.fromValue(value);

        assertEquals(value, ConversationContextValues.toValue(restored));
    }

    /** modelState 必须显式出现，缺失不能被静默解释成 null。 */
    @Test
    void rejectsMissingModelState() {
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
        context.put("modelState", null);

        assertThrows(
                IllegalArgumentException.class, () -> ConversationContextValues.fromValue(context));
    }

    /** 新协议也拒绝额外字段，避免拼错字段被 Jackson 静默丢失。 */
    @Test
    void rejectsUnknownContextField() {
        Map<String, Object> context = new LinkedHashMap<String, Object>();
        context.put("messages", Collections.emptyList());
        context.put("modelState", null);
        context.put("fallbackMessages", Collections.emptyList());

        assertThrows(
                IllegalArgumentException.class, () -> ConversationContextValues.fromValue(context));
    }
}
