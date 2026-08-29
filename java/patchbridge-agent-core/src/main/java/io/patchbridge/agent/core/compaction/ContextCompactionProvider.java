package io.patchbridge.agent.core.compaction;

import io.patchbridge.agent.core.context.AiRequestContext;

/** 使用当前模型生成上下文摘要的应用端口。 */
public interface ContextCompactionProvider {

    /** 启动一次压缩并立即返回可取消句柄。 */
    ContextCompactionInvocation compact(
            ContextCompactionRequest request, AiRequestContext context);
}
