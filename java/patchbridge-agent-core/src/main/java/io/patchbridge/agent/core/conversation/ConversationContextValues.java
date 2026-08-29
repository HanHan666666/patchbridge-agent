package io.patchbridge.agent.core.conversation;

import io.patchbridge.agent.core.model.AgentMessage;
import io.patchbridge.agent.core.model.BlockType;
import io.patchbridge.agent.core.model.ContentBlock;
import io.patchbridge.agent.core.model.ImageBlock;
import io.patchbridge.agent.core.model.ImageSource;
import io.patchbridge.agent.core.model.MessageRole;
import io.patchbridge.agent.core.model.ModelState;
import io.patchbridge.agent.core.model.ReasoningBlock;
import io.patchbridge.agent.core.model.TextBlock;
import io.patchbridge.agent.core.model.ToolCallBlock;
import io.patchbridge.agent.core.model.ToolResultBlock;
import io.patchbridge.agent.core.model.ToolResultStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ConversationContext 与稳定 JSON 值之间的唯一映射入口。
 *
 * <p>Core 不依赖 Jackson；HTTP 和 JDBC Adapter 把 JSON 解析为 Map/List 后统一调用本类， 避免两个边界分别猜测 ContentBlock
 * 字段。映射只接受当前明确支持的协议字段，未知类型、缺失字段和额外字段都会失败。
 */
public final class ConversationContextValues {

    /** 工具类不允许实例化。 */
    private ConversationContextValues() {}

    /**
     * 把稳定 JSON 对象解析为会话上下文。
     *
     * @param value 包含 messages 与显式 modelContext 的 JSON 对象
     * @return 已校验的不可变领域上下文
     */
    public static ConversationContext fromValue(Object value) {
        Map<String, Object> context = requireObject(value, "context");
        requireFields(context, "context", "messages", "modelContext");
        List<AgentMessage> messages = fromMessagesValue(context.get("messages"));
        ModelContext modelContext = fromModelContextValue(context.get("modelContext"));
        return new ConversationContext(messages, modelContext);
    }

    /** 把会话上下文转换为可交给任意 JSON Adapter 的稳定值对象。 */
    public static Map<String, Object> toValue(ConversationContext context) {
        if (context == null) {
            throw new IllegalArgumentException("conversationContext 不可为空");
        }
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("messages", toMessagesValue(context.getMessages()));
        value.put("modelContext", toModelContextValue(context.getModelContext()));
        return value;
    }

    /** 把 JSON ModelContext 解析为不可变工作上下文。 */
    public static ModelContext fromModelContextValue(Object value) {
        Map<String, Object> context = requireObject(value, "context.modelContext");
        requireFields(
                context,
                "context.modelContext",
                "checkpoint",
                "firstRetainedMessageId",
                "modelState",
                "usage");
        ContextCompactionCheckpoint checkpoint =
                fromCheckpointValue(context.get("checkpoint"));
        String firstRetainedMessageId =
                requireNullableText(
                        context.get("firstRetainedMessageId"),
                        "context.modelContext.firstRetainedMessageId");
        ModelState modelState = fromModelStateValue(context.get("modelState"));
        ModelContextUsage usage = fromModelContextUsageValue(context.get("usage"));
        return new ModelContext(checkpoint, firstRetainedMessageId, modelState, usage);
    }

    /** 把模型工作上下文转换为稳定 JSON 值。 */
    public static Map<String, Object> toModelContextValue(ModelContext context) {
        if (context == null) {
            throw new IllegalArgumentException("modelContext 不可为空");
        }
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("checkpoint", toCheckpointValue(context.getCheckpoint()));
        value.put("firstRetainedMessageId", context.getFirstRetainedMessageId());
        value.put("modelState", toModelStateValue(context.getModelState()));
        value.put("usage", toModelContextUsageValue(context.getUsage()));
        return value;
    }

    /** 解析可空的上下文压缩检查点。 */
    private static ContextCompactionCheckpoint fromCheckpointValue(Object value) {
        if (value == null) {
            return null;
        }
        Map<String, Object> checkpoint =
                requireObject(value, "context.modelContext.checkpoint");
        requireFields(
                checkpoint,
                "context.modelContext.checkpoint",
                "id",
                "summary",
                "trigger",
                "compactedAt",
                "tokensBefore",
                "estimatedTokensAfter",
                "compactionCount");
        return new ContextCompactionCheckpoint(
                requireText(checkpoint.get("id"), "checkpoint.id"),
                requireText(checkpoint.get("summary"), "checkpoint.summary"),
                ContextCompactionTrigger.fromWireValue(
                        requireText(checkpoint.get("trigger"), "checkpoint.trigger")),
                requireText(checkpoint.get("compactedAt"), "checkpoint.compactedAt"),
                requireNonNegativeLong(checkpoint.get("tokensBefore"), "checkpoint.tokensBefore"),
                requireNonNegativeLong(
                        checkpoint.get("estimatedTokensAfter"),
                        "checkpoint.estimatedTokensAfter"),
                requirePositiveInt(
                        checkpoint.get("compactionCount"), "checkpoint.compactionCount"));
    }

    /** 把可空检查点转换为稳定 JSON 值。 */
    private static Map<String, Object> toCheckpointValue(
            ContextCompactionCheckpoint checkpoint) {
        if (checkpoint == null) {
            return null;
        }
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("id", checkpoint.getId());
        value.put("summary", checkpoint.getSummary());
        value.put("trigger", checkpoint.getTrigger().getWireValue());
        value.put("compactedAt", checkpoint.getCompactedAt());
        value.put("tokensBefore", checkpoint.getTokensBefore());
        value.put("estimatedTokensAfter", checkpoint.getEstimatedTokensAfter());
        value.put("compactionCount", checkpoint.getCompactionCount());
        return value;
    }

    /** 解析可空的模型工作上下文用量。 */
    private static ModelContextUsage fromModelContextUsageValue(Object value) {
        if (value == null) {
            return null;
        }
        Map<String, Object> usage = requireObject(value, "context.modelContext.usage");
        requireFields(
                usage,
                "context.modelContext.usage",
                "totalTokens",
                "source",
                "measuredThroughMessageId");
        return new ModelContextUsage(
                requireNonNegativeLong(usage.get("totalTokens"), "usage.totalTokens"),
                ModelContextUsage.Source.fromWireValue(
                        requireText(usage.get("source"), "usage.source")),
                requireNullableText(
                        usage.get("measuredThroughMessageId"),
                        "usage.measuredThroughMessageId"));
    }

    /** 把可空模型上下文用量转换为稳定 JSON 值。 */
    private static Map<String, Object> toModelContextUsageValue(ModelContextUsage usage) {
        if (usage == null) {
            return null;
        }
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("totalTokens", usage.getTotalTokens());
        value.put("source", usage.getSource().getWireValue());
        value.put("measuredThroughMessageId", usage.getMeasuredThroughMessageId());
        return value;
    }

    /** 把 JSON 消息数组解析为稳定领域消息。 */
    public static List<AgentMessage> fromMessagesValue(Object value) {
        List<?> rawMessages = requireList(value, "context.messages");
        List<AgentMessage> messages = new ArrayList<AgentMessage>(rawMessages.size());
        for (int index = 0; index < rawMessages.size(); index++) {
            messages.add(fromMessageValue(rawMessages.get(index), index));
        }
        return messages;
    }

    /** 把稳定领域消息转换为 JSON 消息数组。 */
    public static List<Map<String, Object>> toMessagesValue(List<AgentMessage> messages) {
        if (messages == null) {
            throw new IllegalArgumentException("messages 不可为空");
        }
        List<Map<String, Object>> values = new ArrayList<Map<String, Object>>(messages.size());
        for (AgentMessage message : messages) {
            values.add(toMessageValue(message));
        }
        return values;
    }

    /** 把 JSON Blocks 数组解析为领域内容块，供 JDBC 行恢复使用。 */
    public static List<ContentBlock> fromBlocksValue(Object value) {
        List<?> rawBlocks = requireList(value, "message.blocks");
        List<ContentBlock> blocks = new ArrayList<ContentBlock>(rawBlocks.size());
        for (int index = 0; index < rawBlocks.size(); index++) {
            blocks.add(fromBlockValue(rawBlocks.get(index), "message.blocks[" + index + "]"));
        }
        return blocks;
    }

    /** 把领域内容块转换为稳定 JSON 数组，供 JDBC 持久化使用。 */
    public static List<Map<String, Object>> toBlocksValue(List<ContentBlock> blocks) {
        if (blocks == null) {
            throw new IllegalArgumentException("blocks 不可为空");
        }
        List<Map<String, Object>> values = new ArrayList<Map<String, Object>>(blocks.size());
        for (ContentBlock block : blocks) {
            values.add(toBlockValue(block));
        }
        return values;
    }

    /** 把 JSON ModelState 解析为领域状态；JSON null 是唯一合法的空状态表达。 */
    public static ModelState fromModelStateValue(Object value) {
        if (value == null) {
            return null;
        }
        Map<String, Object> state = requireObject(value, "context.modelContext.modelState");
        requireFields(state, "context.modelContext.modelState", "format", "data");
        return new ModelState(
                requireText(state.get("format"), "context.modelContext.modelState.format"),
                state.get("data"));
    }

    /** 把领域 ModelState 转换为稳定 JSON 值；无状态时返回 null。 */
    public static Map<String, Object> toModelStateValue(ModelState state) {
        if (state == null) {
            return null;
        }
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("format", state.getFormat());
        value.put("data", state.getData());
        return value;
    }

    /** 解析单条 AgentMessage，并保留数组中报告错误所需的位置。 */
    private static AgentMessage fromMessageValue(Object value, int index) {
        String path = "context.messages[" + index + "]";
        Map<String, Object> message = requireObject(value, path);
        requireFields(message, path, "id", "role", "blocks");
        return new AgentMessage(
                requireText(message.get("id"), path + ".id"),
                MessageRole.fromWireValue(requireText(message.get("role"), path + ".role")),
                fromBlocksValue(message.get("blocks")));
    }

    /** 把单条领域消息转换为稳定字段，不暴露 Java 枚举名称。 */
    private static Map<String, Object> toMessageValue(AgentMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("messages 不允许包含 null");
        }
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("id", message.getId());
        value.put("role", message.getRole().getWireValue());
        value.put("blocks", toBlocksValue(message.getBlocks()));
        return value;
    }

    /** 根据稳定 type 判别字段构造唯一的领域 ContentBlock。 */
    private static ContentBlock fromBlockValue(Object value, String path) {
        Map<String, Object> block = requireObject(value, path);
        String type = requireText(block.get("type"), path + ".type");
        if (BlockType.TEXT.getWireValue().equals(type)) {
            requireFields(block, path, "type", "text");
            return new TextBlock(requireString(block.get("text"), path + ".text"));
        }
        if (BlockType.REASONING.getWireValue().equals(type)) {
            requireFields(block, path, "type", "text");
            return new ReasoningBlock(requireString(block.get("text"), path + ".text"));
        }
        if (BlockType.IMAGE.getWireValue().equals(type)) {
            requireFields(block, path, "type", "source");
            return new ImageBlock(fromImageSourceValue(block.get("source"), path + ".source"));
        }
        if (BlockType.TOOL_CALL.getWireValue().equals(type)) {
            requireFields(block, path, "type", "callId", "name", "input");
            return new ToolCallBlock(
                    requireText(block.get("callId"), path + ".callId"),
                    requireText(block.get("name"), path + ".name"),
                    requireObject(block.get("input"), path + ".input"));
        }
        if (BlockType.TOOL_RESULT.getWireValue().equals(type)) {
            requireFields(block, path, "type", "callId", "name", "status", "content");
            return new ToolResultBlock(
                    requireText(block.get("callId"), path + ".callId"),
                    requireText(block.get("name"), path + ".name"),
                    ToolResultStatus.fromWireValue(
                            requireText(block.get("status"), path + ".status")),
                    fromToolResultContentValue(block.get("content"), path + ".content"));
        }
        throw new IllegalArgumentException("不支持的 ContentBlock 类型: " + type);
    }

    /** 把领域 ContentBlock 转换为稳定字段，并对实现与声明类型不一致显式失败。 */
    private static Map<String, Object> toBlockValue(ContentBlock block) {
        if (block == null) {
            throw new IllegalArgumentException("blocks 不允许包含 null");
        }
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("type", block.getType().getWireValue());
        switch (block.getType()) {
            case TEXT:
                value.put("text", requireBlockType(block, TextBlock.class).getText());
                return value;
            case REASONING:
                value.put("text", requireBlockType(block, ReasoningBlock.class).getText());
                return value;
            case IMAGE:
                value.put(
                        "source",
                        toImageSourceValue(requireBlockType(block, ImageBlock.class).getSource()));
                return value;
            case TOOL_CALL:
                ToolCallBlock call = requireBlockType(block, ToolCallBlock.class);
                value.put("callId", call.getCallId());
                value.put("name", call.getName());
                value.put("input", call.getInput());
                return value;
            case TOOL_RESULT:
                ToolResultBlock result = requireBlockType(block, ToolResultBlock.class);
                value.put("callId", result.getCallId());
                value.put("name", result.getName());
                value.put("status", result.getStatus().getWireValue());
                value.put("content", toTextBlocksValue(result.getContent()));
                return value;
            default:
                throw new IllegalArgumentException("不支持的 ContentBlock 类型: " + block.getType());
        }
    }

    /** 解析 URL 或 Base64 图片来源，不允许两类字段混用。 */
    private static ImageSource fromImageSourceValue(Object value, String path) {
        Map<String, Object> source = requireObject(value, path);
        String type = requireText(source.get("type"), path + ".type");
        if (ImageSource.Type.URL.getWireValue().equals(type)) {
            requireFields(source, path, "type", "url");
            return ImageSource.url(requireText(source.get("url"), path + ".url"));
        }
        if (ImageSource.Type.BASE64.getWireValue().equals(type)) {
            requireFields(source, path, "type", "mediaType", "data");
            return ImageSource.base64(
                    requireText(source.get("mediaType"), path + ".mediaType"),
                    requireText(source.get("data"), path + ".data"));
        }
        throw new IllegalArgumentException("不支持的图片来源类型: " + type);
    }

    /** 把互斥图片来源转换为稳定 JSON 字段。 */
    private static Map<String, Object> toImageSourceValue(ImageSource source) {
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("type", source.getType().getWireValue());
        if (source.getType() == ImageSource.Type.URL) {
            value.put("url", source.getUrl());
        } else {
            value.put("mediaType", source.getMediaType());
            value.put("data", source.getData());
        }
        return value;
    }

    /** Tool Result 第一版只接受文本内容块。 */
    private static List<TextBlock> fromToolResultContentValue(Object value, String path) {
        List<?> rawContent = requireList(value, path);
        List<TextBlock> content = new ArrayList<TextBlock>(rawContent.size());
        for (int index = 0; index < rawContent.size(); index++) {
            ContentBlock block = fromBlockValue(rawContent.get(index), path + "[" + index + "]");
            if (!(block instanceof TextBlock)) {
                throw new IllegalArgumentException(path + " 只支持 text 内容块");
            }
            content.add((TextBlock) block);
        }
        return content;
    }

    /** 把 Tool Result 的文本内容转换为稳定块数组。 */
    private static List<Map<String, Object>> toTextBlocksValue(List<TextBlock> blocks) {
        List<Map<String, Object>> values = new ArrayList<Map<String, Object>>(blocks.size());
        for (TextBlock block : blocks) {
            values.add(toBlockValue(block));
        }
        return values;
    }

    /** 校验 JSON 值是字符串，空字符串是否允许由具体字段决定。 */
    private static String requireString(Object value, String path) {
        if (!(value instanceof String)) {
            throw new IllegalArgumentException(path + " 必须是字符串");
        }
        return (String) value;
    }

    /** 校验 JSON 值是非空字符串。 */
    private static String requireText(Object value, String path) {
        String text = requireString(value, path);
        if (text.trim().isEmpty()) {
            throw new IllegalArgumentException(path + " 不可为空");
        }
        return text;
    }

    /** 校验可空字符串；非空时不允许只有空白。 */
    private static String requireNullableText(Object value, String path) {
        return value == null ? null : requireText(value, path);
    }

    /** token 等计数只接受 JSON 整数语义和非负范围。 */
    private static long requireNonNegativeLong(Object value, String path) {
        if (!(value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long)) {
            throw new IllegalArgumentException(path + " 必须是整数");
        }
        long parsed = ((Number) value).longValue();
        if (parsed < 0) {
            throw new IllegalArgumentException(path + " 不可为负数");
        }
        return parsed;
    }

    /** 累计次数只接受 Java int 范围内的正整数。 */
    private static int requirePositiveInt(Object value, String path) {
        long parsed = requireNonNegativeLong(value, path);
        if (parsed <= 0 || parsed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(path + " 必须是 int 范围内的正整数");
        }
        return (int) parsed;
    }

    /** 校验 JSON 值是对象且所有键都是字符串。 */
    private static Map<String, Object> requireObject(Object value, String path) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(path + " 必须是 JSON 对象");
        }
        Map<?, ?> raw = (Map<?, ?>) value;
        Map<String, Object> object = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new IllegalArgumentException(path + " 的字段名必须是字符串");
            }
            object.put((String) entry.getKey(), entry.getValue());
        }
        return object;
    }

    /** 校验 JSON 值是数组。 */
    private static List<?> requireList(Object value, String path) {
        if (!(value instanceof List)) {
            throw new IllegalArgumentException(path + " 必须是 JSON 数组");
        }
        return (List<?>) value;
    }

    /**
     * 要求对象字段集合精确匹配当前稳定协议。
     *
     * <p>额外字段通常代表客户端拼错协议或仍在发送旧结构，不能静默忽略。
     */
    private static void requireFields(
            Map<String, Object> value, String path, String... expectedFields) {
        Set<String> expected = new LinkedHashSet<String>(Arrays.asList(expectedFields));
        if (!value.keySet().equals(expected)) {
            Set<String> missing = new LinkedHashSet<String>(expected);
            missing.removeAll(value.keySet());
            Set<String> extra = new LinkedHashSet<String>(value.keySet());
            extra.removeAll(expected);
            throw new IllegalArgumentException(path + " 字段不匹配，缺少=" + missing + "，多余=" + extra);
        }
    }

    /** 校验 ContentBlock 的类型声明与 Java 实现一致。 */
    private static <T extends ContentBlock> T requireBlockType(ContentBlock block, Class<T> type) {
        if (!type.isInstance(block)) {
            throw new IllegalArgumentException("ContentBlock 类型声明与实现不一致: " + block.getType());
        }
        return type.cast(block);
    }
}
