package io.patchbridge.agent.model.anthropic;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.patchbridge.agent.core.error.ModelGatewayException;
import io.patchbridge.agent.core.model.*;
import io.patchbridge.agent.core.model.target.ModelTargetRef;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Messages 协议的真实流事件形状回归：签名只进私有状态，完整终止后才能续接。 */
class AnthropicMessagesProtocolTest {
    /** 使用固定引用，验证编码时不将会话路由字段发送给上游。 */
    private static final ModelTargetRef TARGET = new ModelTargetRef("messages-test", 1);

    /** 协议实现每次请求创建独立 Decoder。 */
    private final AnthropicMessagesProtocol protocol =
            new AnthropicMessagesProtocol(new ObjectMapper());

    /** DeepSeek Messages 返回的思考、签名与正文必须分离并可精确续接。 */
    @Test
    void signedThinkingStreamRoundTripsWithoutLeakingSignature() {
        ModelRequest request =
                request(
                        null,
                        Collections.singletonList(
                                new AgentMessage(
                                        "u1",
                                        MessageRole.USER,
                                        Collections.<ContentBlock>singletonList(
                                                new TextBlock("你好")))));
        List<ModelStreamEvent> events = new ArrayList<ModelStreamEvent>();
        AnthropicMessagesProtocol.Decoder decoder = protocol.decoder(request);
        ModelStreamListener listener = listener(events);

        feed(
                decoder,
                listener,
                "message_start",
                "{\"type\":\"message_start\",\"message\":{\"role\":\"assistant\",\"content\":[],\"usage\":{\"input_tokens\":5,\"output_tokens\":0}}}");
        feed(
                decoder,
                listener,
                "content_block_start",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}");
        feed(
                decoder,
                listener,
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"先想\"}}");
        feed(
                decoder,
                listener,
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"signed-value\"}}");
        feed(
                decoder,
                listener,
                "content_block_stop",
                "{\"type\":\"content_block_stop\",\"index\":0}");
        feed(
                decoder,
                listener,
                "content_block_start",
                "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
        feed(
                decoder,
                listener,
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"答案\"}}");
        feed(
                decoder,
                listener,
                "content_block_stop",
                "{\"type\":\"content_block_stop\",\"index\":1}");
        feed(
                decoder,
                listener,
                "message_delta",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":3}}");
        assertTrue(decoder.accept("message_stop", "{\"type\":\"message_stop\"}", listener));
        decoder.finish();

        assertEquals(7, events.size());
        ModelBlockDeltaEvent reasoning = (ModelBlockDeltaEvent) events.get(1);
        assertEquals("先想", reasoning.getDelta().getText());
        ModelMessageStopEvent stop = (ModelMessageStopEvent) events.get(6);
        assertEquals(ModelStopReason.END_TURN, stop.getStopReason());
        assertEquals(8L, stop.getUsage().getTotalTokens());
        assertEquals(AnthropicMessagesProtocol.STATE_FORMAT, stop.getModelState().getFormat());
        AgentMessage assistant =
                new AgentMessage(
                        "a1",
                        MessageRole.ASSISTANT,
                        Arrays.<ContentBlock>asList(new ReasoningBlock("先想"), new TextBlock("答案")));
        Map<String, Object> encoded =
                protocol.prepare(
                        request(
                                stop.getModelState(),
                                Arrays.asList(
                                        request.getMessages().get(0),
                                        assistant,
                                        new AgentMessage(
                                                "u2",
                                                MessageRole.USER,
                                                Collections.<ContentBlock>singletonList(
                                                        new TextBlock("继续"))))),
                        "deepseek-flash");
        assertFalse(encoded.containsKey("modelTarget"));
        assertTrue(encoded.toString().contains("signed-value"));
    }

    /** 断流不得把不完整的签名或 usage 当作成功结果保存。 */
    @Test
    void incompleteStreamCannotBecomeSuccess() {
        AnthropicMessagesProtocol.Decoder decoder =
                protocol.decoder(
                        request(
                                null,
                                Collections.singletonList(
                                        new AgentMessage(
                                                "u1",
                                                MessageRole.USER,
                                                Collections.<ContentBlock>singletonList(
                                                        new TextBlock("你好"))))));
        feed(
                decoder,
                listener(new ArrayList<ModelStreamEvent>()),
                "message_start",
                "{\"type\":\"message_start\",\"message\":{\"role\":\"assistant\",\"content\":[],\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}");
        assertThrows(ModelGatewayException.class, decoder::finish);
    }

    /** 点分 Tool 全名映射为合法长度的别名，回包必须恢复原名。 */
    @Test
    void toolAliasFitsMessagesLimitAndRoundTrips() {
        String original = "local.inventory.device_get";
        ModelToolDefinition tool =
                new ModelToolDefinition(
                        original,
                        "读取设备",
                        Collections.<String, Object>singletonMap("type", "object"));
        ModelRequest base =
                request(
                        null,
                        Collections.singletonList(
                                new AgentMessage(
                                        "u1",
                                        MessageRole.USER,
                                        Collections.<ContentBlock>singletonList(
                                                new TextBlock("读取设备")))));
        ModelRequest request =
                new ModelRequest(
                        "a1",
                        TARGET,
                        base.getMessages(),
                        Collections.singletonList(tool),
                        null,
                        null,
                        64);
        Map<String, Object> encoded = protocol.prepare(request, "deepseek-flash");
        Map<?, ?> definition = (Map<?, ?>) ((List<?>) encoded.get("tools")).get(0);
        String alias = (String) definition.get("name");
        assertEquals(64, alias.length());
        assertTrue(alias.matches("[A-Za-z0-9_-]{1,64}"));

        List<ModelStreamEvent> events = new ArrayList<ModelStreamEvent>();
        ModelStreamListener listener = listener(events);
        AnthropicMessagesProtocol.Decoder decoder = protocol.decoder(request);
        feed(
                decoder,
                listener,
                "message_start",
                "{\"type\":\"message_start\",\"message\":{\"role\":\"assistant\",\"content\":[],\"usage\":{\"input_tokens\":5,\"output_tokens\":0}}}");
        feed(
                decoder,
                listener,
                "content_block_start",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"call-1\",\"name\":\""
                        + alias
                        + "\",\"input\":{}}}");
        feed(
                decoder,
                listener,
                "content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"device\\\":\\\"x\\\"}\"}}");
        feed(
                decoder,
                listener,
                "content_block_stop",
                "{\"type\":\"content_block_stop\",\"index\":0}");
        feed(
                decoder,
                listener,
                "message_delta",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":7}}");
        assertTrue(decoder.accept("message_stop", "{\"type\":\"message_stop\"}", listener));
        assertEquals(original, ((ModelBlockStartEvent) events.get(0)).getBlock().getName());
        assertEquals(
                ModelStopReason.TOOL_USE,
                ((ModelMessageStopEvent) events.get(events.size() - 1)).getStopReason());
    }

    /** 统一构造显式目标的请求。 */
    private static ModelRequest request(ModelState state, List<AgentMessage> messages) {
        return new ModelRequest(
                "a1",
                TARGET,
                messages,
                Collections.<ModelToolDefinition>emptyList(),
                state,
                null,
                64);
    }

    /** 将具名 SSE 事件送入实际 Decoder。 */
    private static void feed(
            AnthropicMessagesProtocol.Decoder decoder,
            ModelStreamListener listener,
            String type,
            String data) {
        assertFalse(decoder.accept(type, data, listener));
    }

    /** 保留领域事件以检查公开正文和私有状态边界。 */
    private static ModelStreamListener listener(List<ModelStreamEvent> events) {
        return new ModelStreamListener() {
            /** 收集已解码事件。 */
            @Override
            public void onEvent(ModelStreamEvent event) {
                events.add(event);
            }

            /** 协议内核不负责 HTTP 完成通知。 */
            @Override
            public void onCompleted() {}

            /** 测试输入不触发网络错误。 */
            @Override
            public void onError(Throwable error) {
                throw new AssertionError(error);
            }
        };
    }
}
