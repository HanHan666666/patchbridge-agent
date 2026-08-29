package io.patchbridge.agent.core.conversation;

import java.time.Instant;

/**
 * 当前模型生成的上下文压缩检查点。
 *
 * <p>检查点只属于模型工作上下文，不是聊天消息；完整历史继续保存在消息表中。
 * tokensAfter 是 Browser 对新工作上下文的保守估算，下一次正常模型调用会由 Provider
 * usage 替换该过渡值。
 */
public final class ContextCompactionCheckpoint {

    /** 检查点稳定标识。 */
    private final String id;
    /** 当前模型生成的摘要正文。 */
    private final String summary;
    /** 自动或手动触发来源。 */
    private final ContextCompactionTrigger trigger;
    /** ISO-8601 完成时间。 */
    private final String compactedAt;
    /** 压缩前模型工作上下文 token 数。 */
    private final long tokensBefore;
    /** 压缩后模型工作上下文估算 token 数。 */
    private final long estimatedTokensAfter;
    /** 当前会话累计成功压缩次数。 */
    private final int compactionCount;

    /** 创建经过完整边界校验的不可变检查点。 */
    public ContextCompactionCheckpoint(
            String id,
            String summary,
            ContextCompactionTrigger trigger,
            String compactedAt,
            long tokensBefore,
            long estimatedTokensAfter,
            int compactionCount) {
        this.id = requireText(id, "checkpoint.id");
        this.summary = requireText(summary, "checkpoint.summary");
        if (trigger == null) {
            throw new IllegalArgumentException("checkpoint.trigger 不可为空");
        }
        this.trigger = trigger;
        this.compactedAt = requireInstant(compactedAt);
        if (tokensBefore < 0 || estimatedTokensAfter < 0) {
            throw new IllegalArgumentException("checkpoint token 数不可为负数");
        }
        if (compactionCount <= 0) {
            throw new IllegalArgumentException("checkpoint.compactionCount 必须大于 0");
        }
        this.tokensBefore = tokensBefore;
        this.estimatedTokensAfter = estimatedTokensAfter;
        this.compactionCount = compactionCount;
    }

    /** 返回检查点标识。 */
    public String getId() { return id; }
    /** 返回模型生成的摘要。 */
    public String getSummary() { return summary; }
    /** 返回压缩触发来源。 */
    public ContextCompactionTrigger getTrigger() { return trigger; }
    /** 返回 ISO-8601 完成时间。 */
    public String getCompactedAt() { return compactedAt; }
    /** 返回压缩前 token 数。 */
    public long getTokensBefore() { return tokensBefore; }
    /** 返回压缩后估算 token 数。 */
    public long getEstimatedTokensAfter() { return estimatedTokensAfter; }
    /** 返回累计压缩次数。 */
    public int getCompactionCount() { return compactionCount; }

    /** 检查点身份与摘要不允许使用空白字符串。 */
    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " 不可为空");
        }
        return value;
    }

    /** 时间必须是可解析的 UTC/带偏移 ISO-8601 瞬时，避免 UI 遇到厂商本地格式。 */
    private static String requireInstant(String value) {
        requireText(value, "checkpoint.compactedAt");
        try {
            Instant.parse(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("checkpoint.compactedAt 必须是 ISO-8601 瞬时", e);
        }
        return value;
    }
}
