package io.patchbridge.agent.core.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.conversation.ContextCompactionTrigger;
import io.patchbridge.agent.core.error.ModelGatewayException;
import io.patchbridge.agent.core.invocation.ModelGateway;
import io.patchbridge.agent.core.invocation.ModelInvocation;
import io.patchbridge.agent.core.model.AgentMessage;
import io.patchbridge.agent.core.model.ContentBlock;
import io.patchbridge.agent.core.model.MessageRole;
import io.patchbridge.agent.core.model.ModelRequest;
import io.patchbridge.agent.core.model.ModelResponse;
import io.patchbridge.agent.core.model.ModelState;
import io.patchbridge.agent.core.model.ModelStateProjector;
import io.patchbridge.agent.core.model.ModelStopReason;
import io.patchbridge.agent.core.model.ModelUsage;
import io.patchbridge.agent.core.model.TextBlock;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 验证默认压缩服务的摘要输入、状态投影、显式失败与取消原子性。 */
class DefaultContextCompactionProviderTest {

    /** 成功摘要必须复用当前 Gateway、禁用 Tool，并用保留尾部核对当前任务状态。 */
    @Test
    void summarizesWithCurrentGatewayAndProjectsRetainedState() {
        ModelUsage usage = new ModelUsage(80L, 20L, 100L);
        ModelResponse response = response("精确摘要", ModelStopReason.END_TURN, usage);
        RecordingGateway gateway = new RecordingGateway(new ImmediateInvocation(response));
        RecordingProjector projector = new RecordingProjector();
        ContextCompactionRequest request = request(true, "上一份摘要");
        DefaultContextCompactionProvider provider =
                new DefaultContextCompactionProvider(gateway, projector);

        ContextCompactionResult result =
                provider.compact(request, context()).result().toCompletableFuture().join();

        assertEquals("精确摘要", result.getSummary());
        assertSame(usage, result.getUsage());
        assertEquals("projection-2", result.getModelState().getData());
        ModelRequest modelRequest = gateway.received.get();
        assertNull(modelRequest.getModel(), "摘要必须沿用当前 Provider 默认模型");
        assertTrue(modelRequest.getTools().isEmpty(), "摘要调用不得开放 Tool");
        assertEquals("projection-1", modelRequest.getModelState().getData());
        assertEquals(
                Arrays.asList(
                        "system-1",
                        "context-previous-summary-response",
                        "user-old",
                        "assistant-old",
                        "context-retained-summary-response",
                        "assistant-recent",
                        "context-instruction-summary-response"),
                messageIds(modelRequest.getMessages()));
        assertTrue(lastText(modelRequest).contains("retained context starts with an assistant"));
        assertTrue(lastText(modelRequest).contains("must not be listed as unfinished"));
        assertEquals(2, projector.messageSets.size());
        assertEquals(
                Arrays.asList(
                        "system-1", "user-old", "assistant-old", "assistant-recent"),
                messageIds(projector.messageSets.get(0)));
        assertEquals(
                Arrays.asList("system-1", "assistant-recent"),
                messageIds(projector.messageSets.get(1)));
    }

    /** 前缀中的剩余数量已经被保留尾部完成时，摘要模型必须能同时看到两份事实。 */
    @Test
    void retainedTailReconcilesStaleRemainingWork() {
        List<AgentMessage> summarized = Arrays.asList(
                text("user-goal", MessageRole.USER, "连续调用工具 100 次"),
                text("assistant-83", MessageRole.ASSISTANT, "已完成 83 次，还剩 17 次"));
        List<AgentMessage> retained = Arrays.asList(
                text("assistant-100", MessageRole.ASSISTANT, "第 100 次调用完成"),
                text("assistant-final", MessageRole.ASSISTANT, "100/100 已全部完成"));
        ContextCompactionRequest request = new ContextCompactionRequest(
                ContextCompactionTrigger.MANUAL,
                summarized,
                retained,
                null,
                null,
                "summary-reconciliation",
                false);
        RecordingGateway gateway = new RecordingGateway(
                new ImmediateInvocation(
                        response(
                                "100 次调用已经完成",
                                ModelStopReason.END_TURN,
                                new ModelUsage(80L, 20L, 100L))));
        DefaultContextCompactionProvider provider =
                new DefaultContextCompactionProvider(gateway, new RecordingProjector());

        provider.compact(request, context()).result().toCompletableFuture().join();

        ModelRequest modelRequest = gateway.received.get();
        assertEquals(
                Arrays.asList(
                        "user-goal",
                        "assistant-83",
                        "context-retained-summary-reconciliation",
                        "assistant-100",
                        "assistant-final",
                        "context-instruction-summary-reconciliation"),
                messageIds(modelRequest.getMessages()));
        assertTrue(lastText(modelRequest).contains("current effective state"));
        assertTrue(lastText(modelRequest).contains("must not be listed as unfinished"));
    }

    /** 摘要响应缺失 usage 时整体失败，且不能提前投影保留状态。 */
    @Test
    void missingUsageFailsBeforeRetainedStateProjection() {
        RecordingProjector projector = new RecordingProjector();
        DefaultContextCompactionProvider provider =
                new DefaultContextCompactionProvider(
                        new RecordingGateway(
                                new ImmediateInvocation(
                                        response("摘要", ModelStopReason.END_TURN, null))),
                        projector);

        CompletionException failure =
                assertThrows(
                        CompletionException.class,
                        () -> provider.compact(request(false, null), context())
                                .result().toCompletableFuture().join());

        assertTrue(failure.getCause() instanceof ModelGatewayException);
        assertTrue(failure.getCause().getMessage().contains("usage"));
        assertEquals(1, projector.messageSets.size());
    }

    /** 截断摘要不能成为检查点，也不能产生保留状态。 */
    @Test
    void truncatedSummaryFailsExplicitly() {
        RecordingProjector projector = new RecordingProjector();
        DefaultContextCompactionProvider provider =
                new DefaultContextCompactionProvider(
                        new RecordingGateway(
                                new ImmediateInvocation(
                                        response(
                                                "部分摘要",
                                                ModelStopReason.MAX_TOKENS,
                                                new ModelUsage(80L, 20L, 100L)))),
                        projector);

        CompletionException failure =
                assertThrows(
                        CompletionException.class,
                        () -> provider.compact(request(false, null), context())
                                .result().toCompletableFuture().join());

        assertTrue(failure.getCause().getMessage().contains("max-tokens"));
        assertEquals(1, projector.messageSets.size());
    }

    /** 外层取消必须幂等委托同一次真实模型调用。 */
    @Test
    void cancellationDelegatesToModelInvocation() {
        PendingInvocation invocation = new PendingInvocation();
        DefaultContextCompactionProvider provider =
                new DefaultContextCompactionProvider(
                        new RecordingGateway(invocation), new RecordingProjector());
        ContextCompactionInvocation compaction = provider.compact(request(false, null), context());

        compaction.cancel();
        compaction.cancel();

        assertEquals(1, invocation.cancellations.get());
        assertFalse(compaction.result().toCompletableFuture().isDone());
    }

    /** 创建包含固定 system、淘汰前缀与 assistant 保留边界的请求。 */
    private static ContextCompactionRequest request(
            boolean splitTurn, String previousSummary) {
        List<AgentMessage> summarized = Arrays.asList(
                text("system-1", MessageRole.SYSTEM, "系统规则"),
                text("user-old", MessageRole.USER, "旧问题"),
                text("assistant-old", MessageRole.ASSISTANT, "旧答案"));
        List<AgentMessage> retained = Arrays.asList(
                text("system-1", MessageRole.SYSTEM, "系统规则"),
                text("assistant-recent", MessageRole.ASSISTANT, "近期答案"));
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("opaque", "state");
        return new ContextCompactionRequest(
                ContextCompactionTrigger.MANUAL,
                summarized,
                retained,
                previousSummary,
                new ModelState("provider/v1", data),
                "summary-response",
                splitTurn);
    }

    /** 创建可信链路上下文。 */
    private static AiRequestContext context() {
        return new AiRequestContext(null, "trace-1", null, null, "conversation-1");
    }

    /** 创建单文本消息。 */
    private static AgentMessage text(String id, MessageRole role, String value) {
        return new AgentMessage(
                id, role, Collections.<ContentBlock>singletonList(new TextBlock(value)));
    }

    /** 创建摘要模型响应。 */
    private static ModelResponse response(
            String value, ModelStopReason reason, ModelUsage usage) {
        return new ModelResponse(
                text("summary-response", MessageRole.ASSISTANT, value),
                reason,
                usage,
                null);
    }

    /** 提取模型消息稳定 ID。 */
    private static List<String> messageIds(List<AgentMessage> messages) {
        List<String> ids = new ArrayList<String>();
        for (AgentMessage message : messages) {
            ids.add(message.getId());
        }
        return ids;
    }

    /** 返回摘要请求最后一条文本指令。 */
    private static String lastText(ModelRequest request) {
        AgentMessage message = request.getMessages().get(request.getMessages().size() - 1);
        return ((TextBlock) message.getBlocks().get(0)).getText();
    }

    /** 记录唯一模型请求并返回预设 Invocation。 */
    private static final class RecordingGateway implements ModelGateway {
        /** 收到的摘要模型请求。 */
        private final AtomicReference<ModelRequest> received =
                new AtomicReference<ModelRequest>();
        /** 返回给压缩服务的句柄。 */
        private final ModelInvocation invocation;

        /** 绑定预设 Invocation。 */
        private RecordingGateway(ModelInvocation invocation) {
            this.invocation = invocation;
        }

        /** 无可信上下文入口不应由默认压缩服务使用。 */
        @Override
        public ModelInvocation invoke(ModelRequest request) {
            throw new AssertionError("压缩必须显式传递 AiRequestContext");
        }

        /** 记录请求并返回预设句柄。 */
        @Override
        public ModelInvocation invoke(ModelRequest request, AiRequestContext context) {
            received.set(request);
            return invocation;
        }
    }

    /** 每次投影返回可区分状态，并记录保留消息集合。 */
    private static final class RecordingProjector implements ModelStateProjector {
        /** 每次投影收到的消息集合。 */
        private final List<List<AgentMessage>> messageSets =
                new ArrayList<List<AgentMessage>>();

        /** 记录消息并返回本次投影编号。 */
        @Override
        public ModelState project(ModelState state, List<AgentMessage> retainedMessages) {
            messageSets.add(new ArrayList<AgentMessage>(retainedMessages));
            return new ModelState("provider/v1", "projection-" + messageSets.size());
        }
    }

    /** 已完成的模型调用句柄。 */
    private static final class ImmediateInvocation implements ModelInvocation {
        /** 已完成结果。 */
        private final ModelResponse response;

        /** 绑定响应。 */
        private ImmediateInvocation(ModelResponse response) {
            this.response = response;
        }

        /** 返回已完成 Stage。 */
        @Override
        public CompletionStage<ModelResponse> result() {
            return CompletableFuture.completedFuture(response);
        }

        /** 立即返回响应。 */
        @Override
        public ModelResponse await(Duration timeout) { return response; }

        /** 已完成调用无需取消。 */
        @Override
        public void cancel() {}
    }

    /** 永不自行完成、可观察取消委托的模型句柄。 */
    private static final class PendingInvocation implements ModelInvocation {
        /** 未完成结果。 */
        private final CompletableFuture<ModelResponse> result =
                new CompletableFuture<ModelResponse>();
        /** 取消委托次数。 */
        private final AtomicInteger cancellations = new AtomicInteger();
        /** 模拟 ModelInvocation 的幂等取消契约。 */
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        /** 返回未完成 Stage。 */
        @Override
        public CompletionStage<ModelResponse> result() { return result; }

        /** 本测试不使用阻塞入口。 */
        @Override
        public ModelResponse await(Duration timeout) {
            throw new UnsupportedOperationException();
        }

        /** 记录取消委托。 */
        @Override
        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                cancellations.incrementAndGet();
            }
        }
    }
}
