package io.patchbridge.agent.model.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.patchbridge.agent.core.error.ModelGatewayException;
import io.patchbridge.agent.core.model.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Messages 协议内核；统一消息不携带签名，续接所需思考块按消息 ID 保存在私有 ModelState。 */
public final class AnthropicMessagesProtocol {
    /** 本协议私有状态的版本边界。 */
    public static final String STATE_FORMAT = "anthropic-messages-thinking/v1";

    /** 唯一 JSON 编解码器，Decoder 每次请求独立。 */
    private final ObjectMapper json;

    /** 创建无 HTTP 与 Spring 依赖的协议实现。 */
    public AnthropicMessagesProtocol(ObjectMapper json) {
        this.json = json;
    }

    /** 编码消息、工具与私有思考状态；不支持的块明确失败。 */
    public Map<String, Object> prepare(ModelRequest request, String model) {
        Map<String, Object> state = readState(request.getModelState());
        List<Object> messages = new ArrayList<Object>();
        List<Object> system = new ArrayList<Object>();
        for (AgentMessage message : request.getMessages()) {
            List<Object> content = new ArrayList<Object>();
            if (message.getRole() == MessageRole.ASSISTANT && state.containsKey(message.getId())) {
                content.addAll((List<?>) state.get(message.getId()));
            }
            for (ContentBlock block : message.getBlocks()) {
                if (block instanceof TextBlock)
                    content.add(map("type", "text", "text", ((TextBlock) block).getText()));
                else if (block instanceof ReasoningBlock) {
                    // 展示思考不作为正文重放，协议续接只使用已校验的私有思考块。
                } else if (block instanceof ImageBlock) {
                    ImageSource source = ((ImageBlock) block).getSource();
                    Map<String, Object> encoded =
                            source.getType() == ImageSource.Type.URL
                                    ? map("type", "url", "url", source.getUrl())
                                    : map(
                                            "type",
                                            "base64",
                                            "media_type",
                                            source.getMediaType(),
                                            "data",
                                            source.getData());
                    content.add(map("type", "image", "source", encoded));
                } else if (block instanceof ToolCallBlock) {
                    ToolCallBlock call = (ToolCallBlock) block;
                    content.add(
                            map(
                                    "type",
                                    "tool_use",
                                    "id",
                                    call.getCallId(),
                                    "name",
                                    wireName(call.getName()),
                                    "input",
                                    call.getInput()));
                } else if (block instanceof ToolResultBlock) {
                    ToolResultBlock result = (ToolResultBlock) block;
                    List<Object> text = new ArrayList<Object>();
                    for (TextBlock item : result.getContent())
                        text.add(map("type", "text", "text", item.getText()));
                    content.add(
                            map(
                                    "type",
                                    "tool_result",
                                    "tool_use_id",
                                    result.getCallId(),
                                    "content",
                                    text,
                                    "is_error",
                                    result.getStatus() == ToolResultStatus.ERROR));
                } else throw error("不支持的输入内容块");
            }
            if (content.isEmpty()) throw error("消息没有可编码内容");
            if (message.getRole() == MessageRole.SYSTEM) system.addAll(content);
            else
                messages.add(
                        map(
                                "role",
                                message.getRole() == MessageRole.ASSISTANT ? "assistant" : "user",
                                "content",
                                content));
        }
        Map<String, Object> body = map("model", model, "stream", true, "messages", messages);
        if (!system.isEmpty()) body.put("system", system);
        if (request.getMaxTokens() == null || request.getMaxTokens() <= 0)
            throw error("Messages 要求明确的 max_tokens");
        body.put("max_tokens", request.getMaxTokens());
        if (request.getTemperature() != null) body.put("temperature", request.getTemperature());
        List<Object> tools = new ArrayList<Object>();
        Set<String> names = new HashSet<String>();
        for (ModelToolDefinition tool : request.getTools()) {
            String name = wireName(tool.getName());
            if (!names.add(name)) throw error("工具名称重复");
            tools.add(
                    map(
                            "name",
                            name,
                            "description",
                            tool.getDescription(),
                            "input_schema",
                            tool.getInputSchema()));
        }
        if (!tools.isEmpty()) body.put("tools", tools);
        return body;
    }

    /** 工具全名可以含点；固定摘要别名满足 Messages 字符集并保持长度边界，回包严格反查。 */
    private static String wireName(String name) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(name.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("t_");
            // 前 31 字节提供 248 bit 标识，连同 t_ 前缀恰好满足 Messages 的 64 字符上限。
            for (int index = 0; index < 31; index++) {
                byte b = digest[index];
                out.append(Character.forDigit((b >> 4) & 15, 16))
                        .append(Character.forDigit(b & 15, 16));
            }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }

    /** 只保留仍对应真实消息的私有思考块；格式不匹配必须显式失败。 */
    public ModelState projectState(ModelState state, List<AgentMessage> messages) {
        Map<String, Object> all = readState(state);
        Map<String, Object> retained = new LinkedHashMap<String, Object>();
        for (AgentMessage message : messages)
            if (all.containsKey(message.getId()))
                retained.put(message.getId(), all.get(message.getId()));
        return stateOf(retained);
    }

    /** 每次请求独占状态机和工具别名表。 */
    public Decoder decoder(ModelRequest request) {
        return new Decoder(request);
    }

    /** 解析私有状态的完整形状，不把未知结构当成无状态调用。 */
    private Map<String, Object> readState(ModelState state) {
        if (state == null) return new LinkedHashMap<String, Object>();
        if (!STATE_FORMAT.equals(state.getFormat())) throw error("ModelState 格式不匹配");
        if (!(state.getData() instanceof Map)) throw error("私有思考状态损坏");
        Map<?, ?> data = (Map<?, ?>) state.getData();
        if (data.size() != 1 || !(data.get("thinkingByMessageId") instanceof Map))
            throw error("私有思考状态损坏");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) data.get("thinkingByMessageId")).entrySet()) {
            if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof List))
                throw error("私有思考条目损坏");
            for (Object raw : (List<?>) entry.getValue()) {
                if (!(raw instanceof Map)) throw error("私有思考块损坏");
                Map<?, ?> block = (Map<?, ?>) raw;
                if ("thinking".equals(block.get("type"))) {
                    if (block.size() != 3
                            || !(block.get("thinking") instanceof String)
                            || !(block.get("signature") instanceof String)) throw error("思考签名缺失");
                } else if ("redacted_thinking".equals(block.get("type"))) {
                    if (block.size() != 2 || !(block.get("data") instanceof String))
                        throw error("加密思考块损坏");
                } else throw error("未知私有思考块");
            }
            result.put((String) entry.getKey(), entry.getValue());
        }
        return result;
    }

    /** 空状态返回 null，以完整替换语义交付下一状态。 */
    private static ModelState stateOf(Map<String, Object> value) {
        return value.isEmpty()
                ? null
                : new ModelState(STATE_FORMAT, map("thinkingByMessageId", value));
    }

    /** 构造仅在协议边界使用的 JSON 对象。 */
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) value.put((String) pairs[i], pairs[i + 1]);
        return value;
    }

    /** 协议错误不公开上游正文，禁止静默继续。 */
    private static ModelGatewayException error(String message) {
        return new ModelGatewayException("Messages 协议错误：" + message, false);
    }

    /** 必需字符串允许正文为空，但不能缺失或改变类型。 */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw error("缺少字符串字段 " + field);
        return value.textValue();
    }

    /** usage 必须来自上游非负整数，不能估算或转换字符串。 */
    private static long count(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToLong()
                || value.longValue() < 0) throw error("无效 usage." + field);
        return value.longValue();
    }

    /** 严格流状态机：内容、签名与 usage 必须在完整 message_stop 后才成为可续接状态。 */
    public final class Decoder {
        /** 响应在领域消息中的 ID。 */
        private final String responseId;

        /** 仅保存当前请求实际保留消息的私有状态。 */
        private final Map<String, Object> nextState;

        /** 别名到框架完整工具名的唯一映射。 */
        private final Map<String, String> toolNames = new LinkedHashMap<String, String>();

        /** 本消息中收集到的私有思考块。 */
        private final List<Object> thinking = new ArrayList<Object>();

        /** 流是否已开始。 */
        private boolean started;

        /** 流是否已完整结束。 */
        private boolean completed;

        /** 上游内容块的下一个连续序号。 */
        private int nextWireIndex;

        /** 框架可见内容块的下一个序号。 */
        private int nextPublicIndex;

        /** 当前上游内容类型，null 表示没有打开的块。 */
        private String activeType;

        /** 当前可见块序号；私有加密思考块没有可见序号。 */
        private int activeIndex;

        /** 当前思考原文或工具参数片段。 */
        private final StringBuilder content = new StringBuilder();

        /** 当前思考签名片段。 */
        private final StringBuilder signature = new StringBuilder();

        /** 首事件提供的完整输入 token 计量。 */
        private long inputTokens;

        /** message_delta 中累计的输出 token 计量。 */
        private long outputTokens;

        /** 明确的结束原因，缺失时 EOF 不能视为成功。 */
        private ModelStopReason reason;

        /** 工具块数量用于验证停止原因与内容一致。 */
        private int toolCount;

        /** 创建状态和别名快照，不持有可变会话。 */
        private Decoder(ModelRequest request) {
            responseId = request.getResponseMessageId();
            nextState = readState(projectState(request.getModelState(), request.getMessages()));
            for (ModelToolDefinition tool : request.getTools())
                toolNames.put(wireName(tool.getName()), tool.getName());
        }

        /** 返回 true 仅表示已消费有效的 message_stop。 */
        public boolean accept(String event, String data, ModelStreamListener listener) {
            if (completed) throw error("结束后仍有数据");
            JsonNode node;
            try {
                node = json.readTree(data);
            } catch (Exception e) {
                throw error("无效事件 JSON");
            }
            if (node == null || !node.isObject()) throw error("事件必须为对象");
            String type = text(node, "type");
            if (event != null && !event.equals(type)) throw error("SSE 名称与事件类型不一致");
            if ("ping".equals(type)) return false;
            if ("error".equals(type)) throw error("上游报告流式错误");
            if ("message_start".equals(type)) {
                if (started) throw error("重复 message_start");
                JsonNode message = node.path("message");
                if (!"assistant".equals(text(message, "role"))
                        || !message.path("content").isArray()
                        || message.path("content").size() != 0) throw error("无效消息起点");
                JsonNode usage = message.path("usage");
                inputTokens = count(usage, "input_tokens");
                if (usage.has("cache_creation_input_tokens"))
                    inputTokens =
                            Math.addExact(inputTokens, count(usage, "cache_creation_input_tokens"));
                if (usage.has("cache_read_input_tokens"))
                    inputTokens =
                            Math.addExact(inputTokens, count(usage, "cache_read_input_tokens"));
                outputTokens = count(usage, "output_tokens");
                started = true;
                return false;
            }
            if (!started) throw error("缺少 message_start");
            if ("content_block_start".equals(type)) start(node, listener);
            else if ("content_block_delta".equals(type)) delta(node, listener);
            else if ("content_block_stop".equals(type)) stop(node, listener);
            else if ("message_delta".equals(type)) {
                if (activeType != null) throw error("内容块尚未关闭");
                String stop = text(node.path("delta"), "stop_reason");
                if ("end_turn".equals(stop)) reason = ModelStopReason.END_TURN;
                else if ("stop_sequence".equals(stop)) reason = ModelStopReason.STOP_SEQUENCE;
                else if ("tool_use".equals(stop)) reason = ModelStopReason.TOOL_USE;
                else if ("max_tokens".equals(stop)) reason = ModelStopReason.MAX_TOKENS;
                else throw error("不支持的停止原因");
                outputTokens = count(node.path("usage"), "output_tokens");
            } else if ("message_stop".equals(type)) {
                if (reason == null || activeType != null) throw error("消息结束不完整");
                if (reason == ModelStopReason.TOOL_USE && toolCount == 0)
                    throw error("工具结束原因没有工具调用");
                if (reason == ModelStopReason.END_TURN && toolCount != 0)
                    throw error("自然结束中包含工具调用");
                if (!thinking.isEmpty()) nextState.put(responseId, thinking);
                listener.onEvent(
                        new ModelMessageStopEvent(
                                reason,
                                stateOf(nextState),
                                new ModelUsage(
                                        inputTokens,
                                        outputTokens,
                                        Math.addExact(inputTokens, outputTokens))));
                completed = true;
                return true;
            } else throw error("不支持的事件类型");
            return false;
        }

        /** EOF 不替代协议结束事件。 */
        public void finish() {
            if (!completed) throw error("连接在 message_stop 前关闭");
        }

        /** 开始一个连续块，工具身份必须来自本轮目录。 */
        private void start(JsonNode node, ModelStreamListener listener) {
            if (activeType != null || reason != null || count(node, "index") != nextWireIndex)
                throw error("内容块开始顺序非法");
            JsonNode block = node.path("content_block");
            activeType = text(block, "type");
            content.setLength(0);
            signature.setLength(0);
            if ("redacted_thinking".equals(activeType)) {
                content.append(text(block, "data"));
                activeIndex = -1;
                return;
            }
            activeIndex = nextPublicIndex++;
            ModelBlockStartEvent.Block start;
            if ("text".equals(activeType))
                start = ModelBlockStartEvent.Block.content(BlockType.TEXT);
            else if ("thinking".equals(activeType))
                start = ModelBlockStartEvent.Block.content(BlockType.REASONING);
            else if ("tool_use".equals(activeType)) {
                String name = toolNames.get(text(block, "name"));
                if (name == null) throw error("上游调用了目录外工具");
                if (!block.path("input").isObject() || block.path("input").size() != 0)
                    throw error("工具起始参数必须为空对象");
                start = ModelBlockStartEvent.Block.toolCall(text(block, "id"), name);
                toolCount++;
            } else throw error("不支持的内容块");
            listener.onEvent(new ModelBlockStartEvent(activeIndex, start));
            if ("text".equals(activeType) || "thinking".equals(activeType)) {
                String initial = text(block, "text".equals(activeType) ? "text" : "thinking");
                if (!initial.isEmpty()) emit(initial, listener);
                if (block.has("signature")) signature.append(text(block, "signature"));
            }
        }

        /** 增量只能更新当前同类型块，签名不进入可见内容。 */
        private void delta(JsonNode node, ModelStreamListener listener) {
            requireActive(node);
            JsonNode delta = node.path("delta");
            String type = text(delta, "type");
            if ("signature_delta".equals(type) && "thinking".equals(activeType))
                signature.append(text(delta, "signature"));
            else if ("text_delta".equals(type) && "text".equals(activeType))
                emit(text(delta, "text"), listener);
            else if ("thinking_delta".equals(type) && "thinking".equals(activeType))
                emit(text(delta, "thinking"), listener);
            else if ("input_json_delta".equals(type) && "tool_use".equals(activeType))
                emit(text(delta, "partial_json"), listener);
            else throw error("内容块与增量类型不匹配");
        }

        /** 累积协议私有内容，并交付厂商中立增量。 */
        private void emit(String value, ModelStreamListener listener) {
            content.append(value);
            listener.onEvent(
                    new ModelBlockDeltaEvent(
                            activeIndex,
                            "tool_use".equals(activeType)
                                    ? ModelBlockDeltaEvent.Delta.toolCall(value)
                                    : ModelBlockDeltaEvent.Delta.text(
                                            "thinking".equals(activeType)
                                                    ? BlockType.REASONING
                                                    : BlockType.TEXT,
                                            value)));
        }

        /** 关闭块前验证参数和私有状态完整性。 */
        private void stop(JsonNode node, ModelStreamListener listener) {
            requireActive(node);
            if ("thinking".equals(activeType)) {
                if (signature.length() == 0) throw error("思考块缺少签名");
                thinking.add(
                        map(
                                "type",
                                "thinking",
                                "thinking",
                                content.toString(),
                                "signature",
                                signature.toString()));
            } else if ("redacted_thinking".equals(activeType))
                thinking.add(map("type", activeType, "data", content.toString()));
            else if ("tool_use".equals(activeType)) {
                if (content.length() == 0) emit("{}", listener);
                try {
                    if (!json.readTree(content.toString()).isObject()) throw error("工具参数必须为对象");
                } catch (java.io.IOException e) {
                    throw error("工具参数 JSON 不完整");
                }
            }
            if (activeIndex >= 0) listener.onEvent(new ModelBlockStopEvent(activeIndex));
            activeType = null;
            nextWireIndex++;
        }

        /** 任何增量或结束都必须引用唯一打开的块。 */
        private void requireActive(JsonNode node) {
            if (activeType == null || count(node, "index") != nextWireIndex) throw error("内容块索引无效");
        }
    }
}
