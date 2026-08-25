package io.patchbridge.agent.core.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Tool 调用结果（对齐 MCP tools/call 返回语义）。
 *
 * <p>约定：业务执行“成功但逻辑上失败”（如设备不存在）也应返回 isError=true 的结构化结果，
 * 让模型继续推理；只有框架级异常（参数绑定失败、权限拒绝等）才以异常形式抛出。
 *
 * <p>构造时对 content 做防御性复制并封装为不可变列表（二次审计 Q-05）：
 * 调用方在构造后继续修改来源列表，不允许影响已发出的结果对象。
 */
public final class ToolCallResult {

    private final List<ToolContent> content;
    private final boolean error;

    public ToolCallResult(List<ToolContent> content, boolean error) {
        Objects.requireNonNull(content, "content 不能为空：无内容结果请用 ofText/ofError 或空列表");
        this.content = Collections.unmodifiableList(new ArrayList<ToolContent>(content));
        this.error = error;
    }

    /** 正常结果：单文本块。 */
    public static ToolCallResult ofText(String text) {
        return new ToolCallResult(Collections.singletonList(ToolContent.text(text)), false);
    }

    /** 业务失败结果：文本错误说明，isError=true，供模型理解并调整策略。 */
    public static ToolCallResult ofError(String message) {
        return new ToolCallResult(Collections.singletonList(ToolContent.text(message)), true);
    }

    public List<ToolContent> getContent() {
        return content;
    }

    public boolean isError() {
        return error;
    }
}
