package io.patchbridge.agent.core.compaction;

import java.util.concurrent.CompletionStage;

/** 一次短生命周期上下文压缩调用，可观察异步结果并主动取消真实上游。 */
public interface ContextCompactionInvocation {

    /** 返回本次调用唯一的异步原子结果。 */
    CompletionStage<ContextCompactionResult> result();

    /** 幂等取消摘要模型调用。 */
    void cancel();
}
