package io.patchbridge.agent.core.invocation;

import io.patchbridge.agent.core.model.ModelResponse;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

/**
 * 一次 Java 模型调用的短生命周期句柄。
 *
 * <p>异步结果是主契约；阻塞等待和主动取消只是同一次调用上的便捷操作。实现不得把句柄注册到全局任务表，也不得在终止后接受迟到事件改写结果。
 */
public interface ModelInvocation {

    /** 返回本次调用唯一的异步完整结果。 */
    CompletionStage<ModelResponse> result();

    /**
     * 在明确时限内阻塞等待完整结果。
     *
     * <p>等待超时或线程中断都会先取消真实上游，再以明确异常结束，不返回部分响应。
     */
    ModelResponse await(Duration timeout);

    /** 请求取消本次调用及其真实上游；重复调用不得产生额外副作用。 */
    void cancel();
}
