package io.patchbridge.agent.model.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.patchbridge.agent.core.error.ModelGatewayException;
import io.patchbridge.agent.core.model.AgentMessage;
import io.patchbridge.agent.core.model.ContentBlock;
import io.patchbridge.agent.core.model.MessageRole;
import io.patchbridge.agent.core.model.ModelBlockDeltaEvent;
import io.patchbridge.agent.core.model.ModelBlockStartEvent;
import io.patchbridge.agent.core.model.ModelBlockStopEvent;
import io.patchbridge.agent.core.model.ModelMessageStopEvent;
import io.patchbridge.agent.core.model.ModelRequest;
import io.patchbridge.agent.core.model.ModelState;
import io.patchbridge.agent.core.model.ModelStopReason;
import io.patchbridge.agent.core.model.ModelStreamEvent;
import io.patchbridge.agent.core.model.ModelStreamListener;
import io.patchbridge.agent.core.model.TextBlock;
import io.patchbridge.agent.core.model.ToolCallBlock;
import io.patchbridge.agent.core.model.ToolResultBlock;
import io.patchbridge.agent.core.model.ToolResultStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat 协议契约测试。
 *
 * <p>该测试直接约束厂商 wire protocol 到 Core 事件的唯一映射，不经过 OkHttp 或
 * WebFlux。合法事件与 Browser HttpModel 共用仓库级 fixture；请求编码、ModelState
 * 和错误边界继续在本 Provider 内验证，避免为了跨语言测试引入生产依赖或测试基类。
 */
class OpenAiChatProtocolContractTest {
    /** 协议测试与共享 fixture 使用同一 JSON 语义。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 被测的无状态 OpenAI Chat 协议内核。 */
    private final OpenAiChatProtocol protocol = new OpenAiChatProtocol(JSON);

    /** 文本与思考必须逐事件匹配共享契约，重复 finish 不得产生第二个 message-stop。 */
    @Test
    void decodesSharedTextAndReasoningContractWithUniqueMessageStop() throws Exception {
        RecordingListener listener = new RecordingListener();
        OpenAiChatProtocol.Decoder decoder = decoder(simpleRequest("response-text"));

        decoder.accept(
                "{\"choices\":[{\"delta\":{\"reasoning_content\":\"先分析\"}}]}",
                listener);
        decoder.accept(
                "{\"choices\":[{\"delta\":{\"content\":\"答案\"}}]}", listener);
        decoder.accept(
                "{\"choices\":[{\"delta\":{\"content\":\"完成\"},"
                        + "\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":4,"
                        + "\"total_tokens\":11}}",
                listener);
        decoder.finish(listener);
        decoder.finish(listener);

        assertEquals(
                sharedEvents("text-reasoning-end-turn"),
                JSON.readTree(normalize(listener.events).toString()));
        assertEquals(1L, countMessageStops(listener.events));
    }

    /** callId、Tool 名称和参数分片必须无损转换为共享 Tool 事件序列。 */
    @Test
    void preservesFragmentedToolIdentityAndArguments() throws Exception {
        RecordingListener listener = new RecordingListener();
        OpenAiChatProtocol.Decoder decoder = decoder(simpleRequest("response-tool"));

        decoder.accept(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"id\":\"call-\",\"function\":{\"name\":\"local.\"}}]}}]}",
                listener);
        decoder.accept(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"id\":\"1\",\"function\":{\"name\":\"echo\","
                        + "\"arguments\":\"{\\\"serial\"}}]}}]}",
                listener);
        decoder.accept(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"arguments\":\"\\\":\\\"DEV-1\\\"}\"}}]},"
                        + "\"finish_reason\":\"tool_calls\"}]}",
                listener);
        decoder.finish(listener);

        assertEquals(
                sharedEvents("fragmented-tool-use"),
                JSON.readTree(normalize(listener.events).toString()));
        assertEquals(1L, countMessageStops(listener.events));
    }

    /** 非 Tool 停止原因必须完整映射为稳定 Core 枚举。 */
    @Test
    void mapsAllNonToolStopReasons() {
        assertEquals(ModelStopReason.MAX_TOKENS, decodeTextStopReason("length"));
        assertEquals(ModelStopReason.STOP_SEQUENCE, decodeTextStopReason("stop_sequence"));
        assertEquals(ModelStopReason.OTHER, decodeTextStopReason("content_filter"));
    }

    /** stopReason 与 Tool 数量矛盾时不得发布稳定 message-stop。 */
    @Test
    void rejectsStopReasonAndToolCountMismatch() {
        RecordingListener missingTool = new RecordingListener();
        OpenAiChatProtocol.Decoder toolReasonWithoutTool = decoder(simpleRequest("missing-tool"));
        toolReasonWithoutTool.accept(
                "{\"choices\":[{\"delta\":{\"content\":\"错误结果\"},"
                        + "\"finish_reason\":\"tool_calls\"}]}",
                missingTool);

        assertThrows(ModelGatewayException.class, () -> toolReasonWithoutTool.finish(missingTool));
        assertEquals(0L, countMessageStops(missingTool.events));

        for (String finishReason : Arrays.asList("stop", "length", "stop_sequence", "content_filter")) {
            RecordingListener unexpectedTool = new RecordingListener();
            OpenAiChatProtocol.Decoder decoder = decoder(simpleRequest("unexpected-" + finishReason));
            decoder.accept(toolChunk(finishReason), unexpectedTool);

            ModelGatewayException failure =
                    assertThrows(ModelGatewayException.class, () -> decoder.finish(unexpectedTool));

            assertFalse(failure.isRetryable());
            assertEquals(0L, countMessageStops(unexpectedTool.events));
        }
    }

    /** Tool Result 必须编码为准确引用原调用的 OpenAI tool 消息。 */
    @Test
    @SuppressWarnings("unchecked")
    void encodesToolResultMessage() {
        AgentMessage assistant =
                new AgentMessage(
                        "assistant-1",
                        MessageRole.ASSISTANT,
                        Collections.<ContentBlock>singletonList(
                                new ToolCallBlock(
                                        "call-1",
                                        "local.echo",
                                        Collections.<String, Object>singletonMap(
                                                "serial", "DEV-1"))));
        AgentMessage tool =
                new AgentMessage(
                        "tool-1",
                        MessageRole.TOOL,
                        Collections.<ContentBlock>singletonList(
                                new ToolResultBlock(
                                        "call-1",
                                        "local.echo",
                                        ToolResultStatus.ERROR,
                                        Arrays.asList(new TextBlock("业务失败"), new TextBlock("：无权限")))));
        ModelRequest request = request("response-after-tool", Arrays.asList(userMessage(), assistant, tool), null);

        List<Map<String, Object>> messages =
                (List<Map<String, Object>>) protocol.prepare(request, "gpt-test").getBody().get("messages");
        Map<String, Object> encoded = messages.get(2);

        assertEquals(
                Arrays.asList("role", "tool_call_id", "content"),
                new ArrayList<String>(encoded.keySet()));
        assertEquals("tool", encoded.get("role"));
        assertEquals("call-1", encoded.get("tool_call_id"));
        assertEquals("业务失败：无权限", encoded.get("content"));
    }

    /** 下一状态只保留当前历史仍引用的 reasoning，并加入本轮新状态。 */
    @Test
    void prunesStaleModelStateAndPublishesCompleteReplacement() {
        AgentMessage assistant =
                new AgentMessage(
                        "assistant-active",
                        MessageRole.ASSISTANT,
                        Collections.<ContentBlock>singletonList(
                                new ToolCallBlock(
                                        "call-old",
                                        "local.echo",
                                        Collections.<String, Object>singletonMap("value", 1))));
        Map<String, Object> reasoning = new LinkedHashMap<String, Object>();
        reasoning.put("assistant-active", "保留思考");
        reasoning.put("assistant-stale", "必须裁掉");
        ModelRequest request =
                request(
                        "response-new",
                        Arrays.asList(userMessage(), assistant),
                        reasoningState(reasoning));
        OpenAiChatProtocol.PreparedRequest prepared = protocol.prepare(request, "gpt-test");
        RecordingListener listener = new RecordingListener();
        OpenAiChatProtocol.Decoder decoder =
                protocol.decoder(request, prepared.getRetainedReasoning());

        decoder.accept(
                "{\"choices\":[{\"delta\":{\"reasoning_content\":\"本轮思考\"}}]}",
                listener);
        decoder.accept(toolChunk("tool_calls"), listener);
        decoder.finish(listener);

        ModelState nextState = lastStop(listener.events).getModelState();
        Map<?, ?> data = (Map<?, ?>) nextState.getData();
        Map<?, ?> nextReasoning = (Map<?, ?>) data.get("reasoningByMessageId");
        assertEquals("保留思考", nextReasoning.get("assistant-active"));
        assertEquals("本轮思考", nextReasoning.get("response-new"));
        assertFalse(nextReasoning.containsKey("assistant-stale"));
        assertEquals(2, nextReasoning.size());
    }

    /** 当当前历史不再引用旧 reasoning 时，Provider 必须以 null 明确清空状态。 */
    @Test
    void clearsModelStateWhenHistoryNoLongerReferencesIt() {
        Map<String, Object> stale = new LinkedHashMap<String, Object>();
        stale.put("assistant-stale", "旧思考");
        ModelRequest request =
                request(
                        "response-clear",
                        Collections.singletonList(userMessage()),
                        reasoningState(stale));
        OpenAiChatProtocol.PreparedRequest prepared = protocol.prepare(request, "gpt-test");
        RecordingListener listener = new RecordingListener();
        OpenAiChatProtocol.Decoder decoder =
                protocol.decoder(request, prepared.getRetainedReasoning());

        decoder.accept(
                "{\"choices\":[{\"delta\":{\"content\":\"完成\"},"
                        + "\"finish_reason\":\"stop\"}]}",
                listener);
        decoder.finish(listener);

        assertTrue(prepared.getRetainedReasoning().isEmpty());
        assertNull(lastStop(listener.events).getModelState());
    }

    /** 不属于 OpenAI Chat 的状态格式必须在任何解码或网络操作前失败。 */
    @Test
    void rejectsMismatchedModelStateFormat() {
        ModelRequest request =
                request(
                        "response-mismatch",
                        Collections.singletonList(userMessage()),
                        new ModelState(
                                "other-provider/v1",
                                Collections.<String, Object>singletonMap("value", "x")));

        ModelGatewayException failure =
                assertThrows(ModelGatewayException.class, () -> protocol.prepare(request, "gpt-test"));

        assertFalse(failure.isRetryable());
    }

    /** 创建使用请求裁剪状态的解码器。 */
    private OpenAiChatProtocol.Decoder decoder(ModelRequest request) {
        OpenAiChatProtocol.PreparedRequest prepared = protocol.prepare(request, "gpt-test");
        return protocol.decoder(request, prepared.getRetainedReasoning());
    }

    /** 解码一条非 Tool 消息并返回最终停止原因。 */
    private ModelStopReason decodeTextStopReason(String finishReason) {
        RecordingListener listener = new RecordingListener();
        OpenAiChatProtocol.Decoder decoder = decoder(simpleRequest("response-" + finishReason));
        decoder.accept(
                "{\"choices\":[{\"delta\":{\"content\":\"完成\"},"
                        + "\"finish_reason\":\""
                        + finishReason
                        + "\"}]}",
                listener);
        decoder.finish(listener);
        return lastStop(listener.events).getStopReason();
    }

    /** 构造一条完整 Tool Call 厂商 chunk，可替换结束原因验证决策表。 */
    private static String toolChunk(String finishReason) {
        return "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"id\":\"call-1\",\"function\":{\"name\":\"local.echo\","
                + "\"arguments\":\"{}\"}}]},\"finish_reason\":\""
                + finishReason
                + "\"}]}";
    }

    /** 创建只含一条用户消息的最小请求。 */
    private static ModelRequest simpleRequest(String responseMessageId) {
        return request(
                responseMessageId,
                Collections.singletonList(userMessage()),
                null);
    }

    /** 创建协议测试请求。 */
    private static ModelRequest request(
            String responseMessageId, List<AgentMessage> messages, ModelState state) {
        return new ModelRequest(
                responseMessageId,
                null,
                messages,
                Collections.emptyList(),
                state,
                null,
                null);
    }

    /** 创建稳定用户消息。 */
    private static AgentMessage userMessage() {
        return new AgentMessage(
                "user-1",
                MessageRole.USER,
                Collections.<ContentBlock>singletonList(new TextBlock("执行测试")));
    }

    /** 创建 OpenAI Chat reasoning 状态。 */
    private static ModelState reasoningState(Map<String, Object> reasoning) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("reasoningByMessageId", reasoning);
        return new ModelState(OpenAiChatProtocol.STATE_FORMAT, data);
    }

    /** 从仓库级 fixture 读取一个稳定事件序列。 */
    private static JsonNode sharedEvents(String caseId) throws IOException {
        String basedir = System.getProperty("basedir");
        if (basedir == null) {
            throw new IllegalStateException("Maven 测试缺少 basedir 系统属性");
        }
        Path fixture =
                Paths.get(
                        basedir,
                        "..",
                        "..",
                        "test-fixtures",
                        "model-provider-contract-v1.json");
        JsonNode root = JSON.readTree(fixture.toFile());
        assertEquals(1, root.path("schemaVersion").asInt());
        for (JsonNode candidate : root.path("cases")) {
            if (caseId.equals(candidate.path("id").asText())) {
                return candidate.path("events");
            }
        }
        throw new AssertionError("共享 Model Provider 契约用例不存在: " + caseId);
    }

    /** 把 Core 事件转换成与 Browser SSE 完全相同的厂商中立 JSON。 */
    private static ArrayNode normalize(List<ModelStreamEvent> events) {
        ArrayNode result = JSON.createArrayNode();
        for (ModelStreamEvent event : events) {
            ObjectNode value = result.addObject();
            value.put("type", event.getType().getWireValue());
            if (event instanceof ModelBlockStartEvent) {
                writeBlockStart(value, (ModelBlockStartEvent) event);
            } else if (event instanceof ModelBlockDeltaEvent) {
                writeBlockDelta(value, (ModelBlockDeltaEvent) event);
            } else if (event instanceof ModelBlockStopEvent) {
                value.put("index", ((ModelBlockStopEvent) event).getIndex());
            } else if (event instanceof ModelMessageStopEvent) {
                writeMessageStop(value, (ModelMessageStopEvent) event);
            } else {
                throw new AssertionError("未知 Core Model 事件: " + event.getClass().getName());
            }
        }
        return result;
    }

    /** 写入 block-start 的精确框架字段。 */
    private static void writeBlockStart(ObjectNode target, ModelBlockStartEvent event) {
        target.put("index", event.getIndex());
        ObjectNode block = target.putObject("block");
        block.put("type", event.getBlock().getType().getWireValue());
        if (event.getBlock().getCallId() != null) {
            block.put("callId", event.getBlock().getCallId());
            block.put("name", event.getBlock().getName());
        }
    }

    /** 写入 block-delta 的精确框架字段。 */
    private static void writeBlockDelta(ObjectNode target, ModelBlockDeltaEvent event) {
        target.put("index", event.getIndex());
        ObjectNode delta = target.putObject("delta");
        delta.put("type", event.getDelta().getType().getWireValue());
        if (event.getDelta().getArgumentsDelta() != null) {
            delta.put("argumentsDelta", event.getDelta().getArgumentsDelta());
        } else {
            delta.put("text", event.getDelta().getText());
        }
    }

    /** 写入 message-stop 的停止原因、usage 与状态。 */
    private static void writeMessageStop(ObjectNode target, ModelMessageStopEvent event) {
        target.put("stopReason", event.getStopReason().getWireValue());
        if (event.getUsage() == null) {
            target.putNull("usage");
        } else {
            ObjectNode usage = target.putObject("usage");
            usage.put("inputTokens", event.getUsage().getInputTokens());
            usage.put("outputTokens", event.getUsage().getOutputTokens());
            usage.put("totalTokens", event.getUsage().getTotalTokens());
        }
        if (event.getModelState() == null) {
            target.putNull("modelState");
        } else {
            ObjectNode state = target.putObject("modelState");
            state.put("format", event.getModelState().getFormat());
            state.set("data", JSON.valueToTree(event.getModelState().getData()));
        }
    }

    /** 统计稳定消息终止事件，避免只比较最后一个元素而漏过重复终态。 */
    private static long countMessageStops(List<ModelStreamEvent> events) {
        long count = 0L;
        for (ModelStreamEvent event : events) {
            if (event instanceof ModelMessageStopEvent) {
                count += 1L;
            }
        }
        return count;
    }

    /** 返回唯一的最终 message-stop。 */
    private static ModelMessageStopEvent lastStop(List<ModelStreamEvent> events) {
        ModelStreamEvent event = events.get(events.size() - 1);
        if (!(event instanceof ModelMessageStopEvent)) {
            throw new AssertionError("模型事件序列未以 message-stop 结束");
        }
        return (ModelMessageStopEvent) event;
    }

    /** 记录协议内核发布的结构化事件；本测试不模拟传输终止回调。 */
    private static final class RecordingListener implements ModelStreamListener {
        /** 按发布顺序保存的 Core 事件。 */
        private final List<ModelStreamEvent> events = new ArrayList<ModelStreamEvent>();

        /** 记录一条结构化事件。 */
        @Override
        public void onEvent(ModelStreamEvent event) {
            events.add(event);
        }

        /** 协议内核本身不拥有 Transport，因此此回调在本测试中不应发生。 */
        @Override
        public void onCompleted() {
            throw new AssertionError("协议内核不应直接发布 Transport 完成回调");
        }

        /** 协议内核以同步异常表达失败，因此此回调在本测试中不应发生。 */
        @Override
        public void onError(Throwable error) {
            throw new AssertionError("协议内核不应直接发布 Transport 错误回调", error);
        }
    }
}
