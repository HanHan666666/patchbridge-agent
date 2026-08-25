package io.patchbridge.agent.model.openai;

import io.patchbridge.agent.core.error.ModelGatewayException;
import io.patchbridge.agent.core.model.AgentMessage;
import io.patchbridge.agent.core.model.BlockType;
import io.patchbridge.agent.core.model.ContentBlock;
import io.patchbridge.agent.core.model.ImageBlock;
import io.patchbridge.agent.core.model.ImageSource;
import io.patchbridge.agent.core.model.MessageRole;
import io.patchbridge.agent.core.model.ModelBlockDeltaEvent;
import io.patchbridge.agent.core.model.ModelBlockStartEvent;
import io.patchbridge.agent.core.model.ModelBlockStopEvent;
import io.patchbridge.agent.core.model.ModelMessageStopEvent;
import io.patchbridge.agent.core.model.ModelRequest;
import io.patchbridge.agent.core.model.ModelState;
import io.patchbridge.agent.core.model.ModelStopReason;
import io.patchbridge.agent.core.model.ModelStreamListener;
import io.patchbridge.agent.core.model.ModelToolDefinition;
import io.patchbridge.agent.core.model.ModelUsage;
import io.patchbridge.agent.core.model.TextBlock;
import io.patchbridge.agent.core.model.ToolCallBlock;
import io.patchbridge.agent.core.model.ToolResultBlock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat 协议内核：OkHttp 与 WebFlux 两个传输 Adapter 的唯一协议实现（二次审计 Q-10）。
 *
 * <p>该类独占厂商字段编码、SSE chunk 解码和 reasoning_content 连续状态。Core、
 * Controller 与浏览器均不允许识别这里的字段名；两个 Adapter 只负责 HTTP 发送、
 * SSE 字节流读取与取消。状态格式版本不匹配时明确失败，避免把某一厂商的签名
 * 或推理状态误交给另一 Provider。请求体使用纯 Java Map 表示，由各 Adapter 选择
 * 自己的序列化方式（OkHttp 直接 writeValueAsString，WebFlux 交给 Jackson codec）。
 */
public final class OpenAiChatProtocol {
    /** 本 Provider 连续状态的稳定格式。 */
    public static final String STATE_FORMAT = "openai-chat-reasoning/v1";

    /** 状态 data 中按稳定消息 ID 保存推理原文的键。 */
    private static final String REASONING_BY_MESSAGE_ID = "reasoningByMessageId";

    /** Provider 内唯一 JSON 编解码器。 */
    private final ObjectMapper json;

    /** 创建协议边界。 */
    public OpenAiChatProtocol(ObjectMapper json) {
        this.json = json;
    }

    /** 把领域请求编码为上游请求，并返回仍与当前历史有关的连续状态。 responseMessageId 和消息 id 只用于本地状态关联，绝不会写入请求 JSON。 */
    public PreparedRequest prepare(ModelRequest request, String model) {
        Map<String, String> incomingReasoning = readReasoningState(request.getModelState());
        Map<String, String> retainedReasoning = new LinkedHashMap<String, String>();
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("stream", Boolean.TRUE);
        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        for (AgentMessage message : request.getMessages()) {
            encodeMessage(message, incomingReasoning, retainedReasoning, messages);
        }
        body.put("messages", messages);
        if (!request.getTools().isEmpty()) {
            List<Map<String, Object>> tools = new ArrayList<Map<String, Object>>();
            for (ModelToolDefinition tool : request.getTools()) {
                tools.add(encodeTool(tool));
            }
            body.put("tools", tools);
        }
        if (request.getTemperature() != null) {
            body.put("temperature", request.getTemperature());
        }
        if (request.getMaxTokens() != null) {
            body.put("max_tokens", request.getMaxTokens());
        }
        return new PreparedRequest(body, retainedReasoning);
    }

    /** 创建本次上游流的有状态解码器。 */
    public Decoder decoder(ModelRequest request, Map<String, String> retainedReasoning) {
        return new Decoder(json, request.getResponseMessageId(), retainedReasoning);
    }

    /** 编码一条领域消息；工具角色可能展开为多条目标协议消息。 */
    private void encodeMessage(
            AgentMessage message,
            Map<String, String> incoming,
            Map<String, String> retained,
            List<Map<String, Object>> output) {
        if (message.getRole() == MessageRole.TOOL) {
            for (ContentBlock block : message.getBlocks()) {
                output.add(encodeToolResult((ToolResultBlock) block));
            }
            return;
        }
        Map<String, Object> encoded = new LinkedHashMap<String, Object>();
        encoded.put("role", message.getRole().getWireValue());
        if (message.getRole() == MessageRole.USER && hasImage(message)) {
            encoded.put("content", encodeUserContent(message));
        } else {
            String text = joinedText(message);
            if (message.getRole() == MessageRole.ASSISTANT
                    && text.isEmpty()
                    && hasToolCall(message)) {
                encoded.put("content", null);
            } else {
                encoded.put("content", text);
            }
        }
        if (message.getRole() == MessageRole.ASSISTANT && hasToolCall(message)) {
            List<Map<String, Object>> calls = new ArrayList<Map<String, Object>>();
            for (ContentBlock block : message.getBlocks()) {
                if (block instanceof ToolCallBlock) {
                    calls.add(encodeToolCall((ToolCallBlock) block));
                }
            }
            encoded.put("tool_calls", calls);
            String reasoning = incoming.get(message.getId());
            if (reasoning != null) {
                encoded.put("reasoning_content", reasoning);
                retained.put(message.getId(), reasoning);
            }
        }
        output.add(encoded);
    }

    /** 将文本与图片按领域块顺序编码为 Chat 多模态 content。 */
    private List<Map<String, Object>> encodeUserContent(AgentMessage message) {
        List<Map<String, Object>> content = new ArrayList<Map<String, Object>>();
        for (ContentBlock block : message.getBlocks()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            if (block instanceof TextBlock) {
                item.put("type", "text");
                item.put("text", ((TextBlock) block).getText());
            } else if (block instanceof ImageBlock) {
                ImageSource source = ((ImageBlock) block).getSource();
                String url =
                        source.getType() == ImageSource.Type.URL
                                ? source.getUrl()
                                : "data:" + source.getMediaType() + ";base64," + source.getData();
                item.put("type", "image_url");
                item.put("image_url", Collections.<String, Object>singletonMap("url", url));
            } else {
                continue;
            }
            content.add(item);
        }
        return content;
    }

    /** 将多个普通文本块无损拼接；reasoning 展示块不进入普通 content。 */
    private static String joinedText(AgentMessage message) {
        StringBuilder result = new StringBuilder();
        for (ContentBlock block : message.getBlocks()) {
            if (block instanceof TextBlock) {
                result.append(((TextBlock) block).getText());
            }
        }
        return result.toString();
    }

    /** 编码已完成工具调用，参数对象在协议边界重新序列化为厂商 JSON 字符串。 */
    private Map<String, Object> encodeToolCall(ToolCallBlock block) {
        Map<String, Object> function = new LinkedHashMap<String, Object>();
        function.put("name", block.getName());
        try {
            function.put("arguments", json.writeValueAsString(block.getInput()));
        } catch (JsonProcessingException e) {
            throw protocolFailure("工具参数无法序列化: " + block.getName(), e);
        }
        Map<String, Object> call = new LinkedHashMap<String, Object>();
        call.put("id", block.getCallId());
        call.put("type", "function");
        call.put("function", function);
        return call;
    }

    /**
     * 编码单个工具结果；OpenAI Chat 只通过 tool_call_id 关联先前调用。
     *
     * <p>ToolResultBlock 仍保留厂商中立名称供 Runtime 展示和校验，但 Chat 的 tool
     * message 协议没有 name 字段，不能把内部冗余信息发送给严格 Provider。
     */
    private static Map<String, Object> encodeToolResult(ToolResultBlock block) {
        StringBuilder text = new StringBuilder();
        for (TextBlock item : block.getContent()) {
            text.append(item.getText());
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("role", "tool");
        result.put("tool_call_id", block.getCallId());
        result.put("content", text.toString());
        return result;
    }

    /** 编码厂商 function tool 包装。 */
    private static Map<String, Object> encodeTool(ModelToolDefinition tool) {
        Map<String, Object> function = new LinkedHashMap<String, Object>();
        function.put("name", tool.getName());
        function.put("description", tool.getDescription());
        function.put("parameters", tool.getInputSchema());
        Map<String, Object> wrapper = new LinkedHashMap<String, Object>();
        wrapper.put("type", "function");
        wrapper.put("function", function);
        return wrapper;
    }

    /** 判断消息是否含图片块。 */
    private static boolean hasImage(AgentMessage message) {
        for (ContentBlock block : message.getBlocks()) {
            if (block instanceof ImageBlock) {
                return true;
            }
        }
        return false;
    }

    /** 判断消息是否含工具调用块。 */
    private static boolean hasToolCall(AgentMessage message) {
        for (ContentBlock block : message.getBlocks()) {
            if (block instanceof ToolCallBlock) {
                return true;
            }
        }
        return false;
    }

    /** 严格解析本 Provider 的 reasoning 状态。 */
    private static Map<String, String> readReasoningState(ModelState state) {
        if (state == null) {
            return Collections.emptyMap();
        }
        if (!STATE_FORMAT.equals(state.getFormat())) {
            throw new ModelGatewayException(
                    "模型状态格式不兼容: expected=" + STATE_FORMAT + ", actual=" + state.getFormat(), false);
        }
        if (!(state.getData() instanceof Map)) {
            throw new ModelGatewayException("模型状态 data 必须是对象", false);
        }
        Map<?, ?> data = (Map<?, ?>) state.getData();
        if (data.size() != 1 || !data.containsKey(REASONING_BY_MESSAGE_ID)) {
            throw new ModelGatewayException("模型状态 data 字段必须精确为 reasoningByMessageId", false);
        }
        Object raw = data.get(REASONING_BY_MESSAGE_ID);
        if (!(raw instanceof Map)) {
            throw new ModelGatewayException("模型状态缺少 reasoningByMessageId 对象", false);
        }
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
            if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String)) {
                throw new ModelGatewayException("reasoningByMessageId 必须是字符串映射", false);
            }
            result.put((String) entry.getKey(), (String) entry.getValue());
        }
        return result;
    }

    /** 创建不可重试的本地协议异常。 */
    private static ModelGatewayException protocolFailure(String message, Throwable cause) {
        return new ModelGatewayException(message, cause, false);
    }

    /** 已完成请求编码及历史状态裁剪的结果。 */
    public static final class PreparedRequest {
        /** 只包含目标厂商字段的请求体（纯 Java Map，由 Adapter 自行序列化）。 */
        private final Map<String, Object> body;

        /** 当前历史仍需要的 reasoning 状态。 */
        private final Map<String, String> retainedReasoning;

        /** 保存不可外泄的两部分结果。 */
        private PreparedRequest(Map<String, Object> body, Map<String, String> retainedReasoning) {
            this.body = body;
            this.retainedReasoning = retainedReasoning;
        }

        /** 返回上游请求体。 */
        public Map<String, Object> getBody() {
            return body;
        }

        /** 返回传给流解码器的历史 reasoning 副本。 */
        public Map<String, String> getRetainedReasoning() {
            return new LinkedHashMap<String, String>(retainedReasoning);
        }
    }

    /**
     * 单次 OpenAI Chat SSE 的状态化解码器。
     *
     * <p>解码器聚合厂商字符串片段，但只向外发布结构化事件；正常结束时关闭全部块、
     * 发布唯一 message-stop，并把本轮 reasoning 按 responseMessageId 写入下一份状态。
     */
    public static final class Decoder {
        /** JSON 解码器。 */
        private final ObjectMapper json;

        /** 当前响应稳定消息 ID。 */
        private final String responseMessageId;

        /** 当前会话仍需保留的历史 reasoning。 */
        private final Map<String, String> reasoningByMessageId;

        /** 当前响应展示 reasoning 聚合。 */
        private final StringBuilder reasoning = new StringBuilder();

        /** 按厂商 tool index 保存工具流状态。 */
        private final Map<Integer, ToolStream> tools = new LinkedHashMap<Integer, ToolStream>();

        /** 已开始但尚未停止的框架块。 */
        private final List<Integer> openBlocks = new ArrayList<Integer>();

        /** 下一个框架块序号。 */
        private int nextBlockIndex;

        /** 当前连续 text/reasoning 块类型。 */
        private BlockType serialBlockType;

        /** 当前连续 text/reasoning 块序号。 */
        private Integer serialBlockIndex;

        /** 厂商停止原因原文。 */
        private String finishReason;

        /** 最后一次厂商 usage。 */
        private ModelUsage usage;

        /** 是否已观察到工具调用。 */
        private boolean toolUse;

        /** 是否已经生成 message-stop。 */
        private boolean finished;

        /** 创建与单次请求绑定的解码器。 */
        private Decoder(
                ObjectMapper json,
                String responseMessageId,
                Map<String, String> retainedReasoning) {
            this.json = json;
            this.responseMessageId = responseMessageId;
            this.reasoningByMessageId = new LinkedHashMap<String, String>(retainedReasoning);
        }

        /** 解析一条非结束标记的 SSE data 并同步发布零到多条领域事件。 */
        public void accept(String data, ModelStreamListener listener) {
            if (finished) {
                throw protocolFailure("message-stop 后仍收到模型数据", null);
            }
            final JsonNode root;
            try {
                root = json.readTree(data);
            } catch (JsonProcessingException e) {
                throw protocolFailure("模型 SSE data 不是合法 JSON", e);
            }
            if (root.has("error")) {
                throw new ModelGatewayException("模型上游返回错误: " + root.get("error").toString(), true);
            }
            readUsage(root.get("usage"));
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.size() == 0) {
                return;
            }
            JsonNode choice = choices.get(0);
            JsonNode delta = choice.get("delta");
            if (delta != null && delta.isObject()) {
                emitText(delta.get("reasoning_content"), BlockType.REASONING, listener);
                emitText(delta.get("content"), BlockType.TEXT, listener);
                readToolCalls(delta.get("tool_calls"), listener);
            }
            JsonNode reason = choice.get("finish_reason");
            if (reason != null && !reason.isNull()) {
                finishReason = reason.asText();
            }
        }

        /** 收到结束标记或正常连接关闭时完成所有块与消息。 */
        public void finish(ModelStreamListener listener) {
            if (finished) {
                return;
            }
            if (finishReason == null) {
                throw protocolFailure("模型流结束但缺少 finish_reason", null);
            }
            closeSerialBlock(listener);
            flushTools(listener);
            ModelStopReason stopReason = mapStopReason(finishReason);
            validateStopReason(stopReason);
            List<Integer> remaining = new ArrayList<Integer>(openBlocks);
            remaining.sort(Comparator.naturalOrder());
            for (Integer index : remaining) {
                stopBlock(index.intValue(), listener);
            }
            if (toolUse && reasoning.length() > 0) {
                reasoningByMessageId.put(responseMessageId, reasoning.toString());
            }
            ModelState state =
                    reasoningByMessageId.isEmpty() ? null : nextState(reasoningByMessageId);
            listener.onEvent(new ModelMessageStopEvent(stopReason, state, usage));
            finished = true;
        }

        /**
         * 在发布稳定 message-stop 前校验停止原因与 Tool 形状一致。
         *
         * <p>Browser Runtime 仍会对整批 Tool 做最终预检；Provider 在厂商协议边界先拒绝
         * 自相矛盾的 finish_reason，可以防止不安全的截断 Tool 参数被包装成合法框架事件。
         */
        private void validateStopReason(ModelStopReason stopReason) {
            boolean expectsToolUse = stopReason == ModelStopReason.TOOL_USE;
            if (expectsToolUse != toolUse) {
                throw protocolFailure(
                        "模型 finish_reason 与 Tool Call 数量不一致: stopReason="
                                + stopReason.getWireValue()
                                + ", hasToolCall="
                                + toolUse,
                        null);
            }
        }

        /** 发布文本或 reasoning 增量，并在类型切换时关闭上一串行块。 */
        private void emitText(JsonNode value, BlockType type, ModelStreamListener listener) {
            if (value == null || value.isNull()) {
                return;
            }
            if (!value.isTextual()) {
                throw protocolFailure(type.getWireValue() + " 增量必须是字符串", null);
            }
            String text = value.asText();
            if (text.isEmpty()) {
                return;
            }
            if (serialBlockType != type) {
                closeSerialBlock(listener);
                serialBlockType = type;
                serialBlockIndex = Integer.valueOf(startContentBlock(type, listener));
            }
            listener.onEvent(
                    new ModelBlockDeltaEvent(
                            serialBlockIndex.intValue(),
                            ModelBlockDeltaEvent.Delta.text(type, text)));
            if (type == BlockType.REASONING) {
                reasoning.append(text);
            }
        }

        /** 读取一批可能交错的 tool_calls 增量。 */
        private void readToolCalls(JsonNode calls, ModelStreamListener listener) {
            if (calls == null || calls.isNull()) {
                return;
            }
            if (!calls.isArray()) {
                throw protocolFailure("tool_calls 增量必须是数组", null);
            }
            closeSerialBlock(listener);
            for (JsonNode call : calls) {
                JsonNode indexNode = call.get("index");
                if (indexNode == null || !indexNode.canConvertToInt()) {
                    throw protocolFailure("tool_calls 增量缺少整数 index", null);
                }
                int providerIndex = indexNode.intValue();
                ToolStream stream = tools.get(Integer.valueOf(providerIndex));
                if (stream == null) {
                    stream = new ToolStream();
                    tools.put(Integer.valueOf(providerIndex), stream);
                }
                appendIdentity(call.get("id"), stream.callId, "tool_calls.id", stream);
                JsonNode function = call.get("function");
                if (function != null && !function.isNull()) {
                    appendIdentity(
                            function.get("name"), stream.name, "tool_calls.function.name", stream);
                    JsonNode arguments = function.get("arguments");
                    if (arguments != null && !arguments.isNull()) {
                        stream.argumentsSeen = true;
                        appendText(
                                arguments,
                                stream.pendingArguments,
                                "tool_calls.function.arguments");
                    }
                }
                tryStartTool(stream, listener, false);
            }
        }

        /** 参数开始或流结束后身份已稳定，此时发布 tool-call start 及暂存参数。 */
        private void tryStartTool(
                ToolStream stream, ModelStreamListener listener, boolean finishing) {
            if (stream.blockIndex == null
                    && (stream.argumentsSeen || finishing)
                    && stream.callId.length() > 0
                    && stream.name.length() > 0) {
                int index = nextBlockIndex++;
                stream.blockIndex = Integer.valueOf(index);
                openBlocks.add(Integer.valueOf(index));
                toolUse = true;
                listener.onEvent(
                        new ModelBlockStartEvent(
                                index,
                                ModelBlockStartEvent.Block.toolCall(
                                        stream.callId.toString(), stream.name.toString())));
            }
            if (stream.blockIndex != null && stream.pendingArguments.length() > 0) {
                String delta = stream.pendingArguments.toString();
                stream.pendingArguments.setLength(0);
                listener.onEvent(
                        new ModelBlockDeltaEvent(
                                stream.blockIndex.intValue(),
                                ModelBlockDeltaEvent.Delta.toolCall(delta)));
            }
        }

        /** 结束时要求每个工具流都有完整身份，并冲刷参数缓存。 */
        private void flushTools(ModelStreamListener listener) {
            for (ToolStream stream : tools.values()) {
                tryStartTool(stream, listener, true);
                if (stream.blockIndex == null) {
                    throw protocolFailure("工具调用结束时缺少 callId 或 name", null);
                }
            }
        }

        /** 启动 text/reasoning 块并记录为待关闭。 */
        private int startContentBlock(BlockType type, ModelStreamListener listener) {
            int index = nextBlockIndex++;
            openBlocks.add(Integer.valueOf(index));
            listener.onEvent(
                    new ModelBlockStartEvent(index, ModelBlockStartEvent.Block.content(type)));
            return index;
        }

        /** 关闭当前串行文本块。 */
        private void closeSerialBlock(ModelStreamListener listener) {
            if (serialBlockIndex != null) {
                stopBlock(serialBlockIndex.intValue(), listener);
            }
            serialBlockIndex = null;
            serialBlockType = null;
        }

        /** 发布唯一 block-stop 并移出开放集合。 */
        private void stopBlock(int index, ModelStreamListener listener) {
            if (openBlocks.remove(Integer.valueOf(index))) {
                listener.onEvent(new ModelBlockStopEvent(index));
            }
        }

        /** 读取标准 OpenAI usage；字段缺失时保持 null，不自行估算。 */
        private void readUsage(JsonNode node) {
            if (node == null || node.isNull()) {
                return;
            }
            JsonNode prompt = node.get("prompt_tokens");
            JsonNode completion = node.get("completion_tokens");
            JsonNode total = node.get("total_tokens");
            if (prompt == null
                    || completion == null
                    || total == null
                    || !prompt.canConvertToLong()
                    || !completion.canConvertToLong()
                    || !total.canConvertToLong()) {
                throw protocolFailure("模型 usage 字段不完整", null);
            }
            usage = new ModelUsage(prompt.longValue(), completion.longValue(), total.longValue());
        }

        /** 构造下一轮不可见状态。 */
        private static ModelState nextState(Map<String, String> reasoning) {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put(REASONING_BY_MESSAGE_ID, new LinkedHashMap<String, String>(reasoning));
            return new ModelState(STATE_FORMAT, data);
        }

        /** 映射 Chat finish_reason 到稳定框架语义。 */
        private static ModelStopReason mapStopReason(String reason) {
            if ("stop".equals(reason)) {
                return ModelStopReason.END_TURN;
            }
            if ("tool_calls".equals(reason)) {
                return ModelStopReason.TOOL_USE;
            }
            if ("length".equals(reason)) {
                return ModelStopReason.MAX_TOKENS;
            }
            if ("stop_sequence".equals(reason)) {
                return ModelStopReason.STOP_SEQUENCE;
            }
            return ModelStopReason.OTHER;
        }

        /** 严格追加可选字符串增量。 */
        private static void appendText(JsonNode value, StringBuilder target, String field) {
            if (value == null || value.isNull()) {
                return;
            }
            if (!value.isTextual()) {
                throw protocolFailure(field + " 必须是字符串", null);
            }
            target.append(value.asText());
        }

        /** Tool 身份一旦通过 start 发布便不可继续变化，否则 Runtime 会关联到错误调用。 */
        private static void appendIdentity(
                JsonNode value, StringBuilder target, String field, ToolStream stream) {
            if (value == null || value.isNull()) {
                return;
            }
            if (!value.isTextual()) {
                throw protocolFailure(field + " 必须是字符串", null);
            }
            if (stream.blockIndex != null && !value.asText().isEmpty()) {
                throw protocolFailure(field + " 在 tool-call start 后继续变化", null);
            }
            target.append(value.asText());
        }

        /** 单个厂商 tool index 的分片聚合状态。 */
        private static final class ToolStream {
            /** 调用 ID 分片。 */
            private final StringBuilder callId = new StringBuilder();

            /** 工具名分片。 */
            private final StringBuilder name = new StringBuilder();

            /** start 前或本 chunk 收到的参数分片。 */
            private final StringBuilder pendingArguments = new StringBuilder();

            /** 是否已观察到 arguments 字段，用于确定工具身份已经完整。 */
            private boolean argumentsSeen;

            /** 分配后的框架块序号。 */
            private Integer blockIndex;
        }
    }
}
