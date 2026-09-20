package io.patchbridge.agent.core.compaction;

import io.patchbridge.agent.core.model.AgentMessage;
import io.patchbridge.agent.core.model.ContentBlock;
import io.patchbridge.agent.core.model.ImageBlock;
import io.patchbridge.agent.core.model.ImageSource;
import io.patchbridge.agent.core.model.ReasoningBlock;
import io.patchbridge.agent.core.model.TextBlock;
import io.patchbridge.agent.core.model.ToolCallBlock;
import io.patchbridge.agent.core.model.ToolResultBlock;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 模型输入 token 估算器：按 UTF-8 字节上界估算一次请求的工作上下文规模。
 *
 * <p>缺少与当前模型严格匹配的服务端 tokenizer 时，一个 UTF-8 字节按一个 token
 * 计入上界。该策略有意高估普通英文与图片数据，但不会低估随机 ASCII、代码和其他
 * 低压缩率内容，与 Browser ContextManager 的估算口径一致。估算只用于摘要请求的
 * 预算检查，绝不替代 Provider 正常返回的 usage 计量。
 */
final class ModelInputEstimator {

    /** 工具类只承载静态估算逻辑，不允许实例化。 */
    private ModelInputEstimator() {
    }

    /** 估算一组消息的 token 上界；口径与 Browser 侧 estimateModelMessages 保持一致。 */
    static int estimateMessages(Iterable<AgentMessage> messages) {
        int bytes = 0;
        for (AgentMessage message : messages) {
            bytes += 32 + utf8Length(message.getId()) + utf8Length(message.getRole().name());
            for (ContentBlock block : message.getBlocks()) {
                bytes += 16 + estimateBlock(block);
            }
        }
        return bytes;
    }

    /** 按内容块真实字段估算字节；图片按 URL 或内联数据全量计入，不使用占位符。 */
    private static int estimateBlock(ContentBlock block) {
        if (block instanceof TextBlock) {
            return utf8Length(((TextBlock) block).getText());
        }
        if (block instanceof ReasoningBlock) {
            return utf8Length(((ReasoningBlock) block).getText());
        }
        if (block instanceof ImageBlock) {
            ImageSource source = ((ImageBlock) block).getSource();
            if (source.getType() == ImageSource.Type.URL) {
                return utf8Length(source.getUrl());
            }
            return utf8Length(source.getMediaType()) + utf8Length(source.getData());
        }
        if (block instanceof ToolCallBlock) {
            ToolCallBlock call = (ToolCallBlock) block;
            return utf8Length(call.getCallId()) + utf8Length(call.getName())
                    + estimateJsonMap(call.getInput());
        }
        if (block instanceof ToolResultBlock) {
            ToolResultBlock result = (ToolResultBlock) block;
            int contentBytes = 0;
            for (TextBlock content : result.getContent()) {
                contentBytes += utf8Length(content.getText());
            }
            return utf8Length(result.getCallId()) + utf8Length(result.getName()) + contentBytes;
        }
        throw new IllegalArgumentException("未支持的上下文内容块: " + block.getClass().getName());
    }

    /**
     * 递归估算业务参数 Map 的序列化字节。
     * Core 不依赖 JSON 库，这里按 UTF-8 字节与结构开销保守计数，与 JSON 序列化结果同量级。
     */
    private static int estimateJsonMap(Map<String, Object> input) {
        if (input == null) {
            return 2;
        }
        int bytes = 2;
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            bytes += utf8Length(entry.getKey()) + 2 + estimateJsonValue(entry.getValue());
        }
        return bytes;
    }

    /** 递归估算 JSON 值字节：容器逐元素展开，标量按 toString 字节计入。 */
    private static int estimateJsonValue(Object value) {
        if (value == null) {
            return 4;
        }
        if (value instanceof Map) {
            return estimateJsonMap((Map<String, Object>) value);
        }
        if (value instanceof Iterable) {
            int bytes = 2;
            for (Object element : (Iterable<?>) value) {
                bytes += estimateJsonValue(element) + 1;
            }
            return bytes;
        }
        return utf8Length(String.valueOf(value));
    }

    /** UTF-8 字节数；TextEncoder 语义的服务端等价实现。 */
    private static int utf8Length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }
}
