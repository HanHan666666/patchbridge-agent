package io.patchbridge.agent.core.compaction;

import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.error.ModelGatewayException;
import io.patchbridge.agent.core.invocation.ModelGateway;
import io.patchbridge.agent.core.invocation.ModelInvocation;
import io.patchbridge.agent.core.model.AgentMessage;
import io.patchbridge.agent.core.model.MessageRole;
import io.patchbridge.agent.core.model.ModelRequest;
import io.patchbridge.agent.core.model.ModelResponse;
import io.patchbridge.agent.core.model.ModelState;
import io.patchbridge.agent.core.model.ModelStateProjector;
import io.patchbridge.agent.core.model.ModelStopReason;
import io.patchbridge.agent.core.model.TextBlock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 复用当前 ModelGateway 与 Provider 状态投影器的默认压缩服务。
 *
 * <p>摘要请求不开放 Tool，但会同时携带待摘要前缀和近期保留消息：前者形成检查点，
 * 后者只用于核对任务是否已经完成，避免把边界时刻的“剩余工作”误写成当前状态。
 * 任何截断、缺失 usage、空摘要或状态不兼容都会使整体调用失败。保留消息状态只在
 * 摘要完整成功后投影，Browser 因而不会收到部分候选状态。
 *
 * <p>摘要请求本身遵守与普通请求相同的窗口预算：摘要输入（含淘汰前缀、保留尾部、
 * 合并的旧摘要与固定指令）加输出预留超过窗口时，调用在发起前明确失败。
 * 失败不重试、不更换模型、不删改任何真实历史，由用户调整输入后重新发起压缩。
 */
public final class DefaultContextCompactionProvider implements ContextCompactionProvider {

    /** 复用模型拦截器、Provider 与取消生命周期的 Java 门面。 */
    private final ModelGateway modelGateway;
    /** 唯一有权解释当前 Provider 私有状态的投影器。 */
    private final ModelStateProjector stateProjector;
    /** 窗口与输出预留的唯一派生来源。 */
    private final ContextCompactionSettings settings;

    /** 创建不持有会话状态的默认压缩服务。 */
    public DefaultContextCompactionProvider(
            ModelGateway modelGateway, ModelStateProjector stateProjector,
            ContextCompactionSettings settings) {
        if (modelGateway == null || stateProjector == null || settings == null) {
            throw new IllegalArgumentException(
                    "modelGateway / stateProjector / settings 不可为空");
        }
        this.modelGateway = modelGateway;
        this.stateProjector = stateProjector;
        this.settings = settings;
    }

    /** 构造摘要模型请求，执行预算检查后把最终结果映射成原子压缩结果。 */
    @Override
    public ContextCompactionInvocation compact(
            ContextCompactionRequest request, AiRequestContext context) {
        if (request == null || context == null) {
            throw new IllegalArgumentException("request / context 不可为空");
        }
        List<AgentMessage> summaryMessages = buildSummaryMessages(request);
        // 摘要请求与普通请求共用同一窗口预算：淘汰前缀加保留尾部可能远大于压缩后的
        // 工作上下文，不能默认认为摘要请求天然更小；超限必须在调用模型前明确失败。
        int estimatedInputTokens = ModelInputEstimator.estimateMessages(summaryMessages);
        int inputBudget = settings.getContextWindowTokens() - settings.getReservedOutputTokens();
        if (estimatedInputTokens > inputBudget) {
            throw new ModelGatewayException(
                    "上下文摘要请求超过模型窗口预算：估算输入 " + estimatedInputTokens
                            + " tokens，可用预算 " + inputBudget
                            + "（窗口 " + settings.getContextWindowTokens()
                            + " − 输出预留 " + settings.getReservedOutputTokens()
                            + "）。完整历史已保留，请缩小本次压缩范围后重试",
                    false);
        }
        ModelState summaryState =
                stateProjector.project(request.getModelState(), summaryStateMessages(request));
        ModelRequest modelRequest =
                new ModelRequest(
                        request.getResponseMessageId(),
                        null,
                        summaryMessages,
                        Collections.emptyList(),
                        summaryState,
                        null,
                        null);
        ModelInvocation modelInvocation = modelGateway.invoke(modelRequest, context);
        CompletableFuture<ContextCompactionResult> result =
                new CompletableFuture<ContextCompactionResult>();
        modelInvocation.result().whenComplete(
                (response, failure) -> {
                    if (failure != null) {
                        result.completeExceptionally(failure);
                        return;
                    }
                    try {
                        result.complete(toResult(request, response));
                    } catch (RuntimeException e) {
                        result.completeExceptionally(e);
                    }
                });
        return new DefaultInvocation(modelInvocation, result);
    }

    /** system 消息固定置前，随后合并旧摘要、淘汰前缀、对账尾部与明确压缩指令。 */
    private static List<AgentMessage> buildSummaryMessages(ContextCompactionRequest request) {
        List<AgentMessage> messages = new ArrayList<AgentMessage>();
        for (AgentMessage message : request.getMessagesToSummarize()) {
            if (message.getRole() == MessageRole.SYSTEM) {
                messages.add(message);
            }
        }
        if (request.getPreviousSummary() != null) {
            messages.add(
                    textMessage(
                            "context-previous-" + request.getResponseMessageId(),
                            MessageRole.USER,
                            "<previous-context-checkpoint>\n"
                                    + request.getPreviousSummary()
                                    + "\n</previous-context-checkpoint>"));
        }
        for (AgentMessage message : request.getMessagesToSummarize()) {
            if (message.getRole() != MessageRole.SYSTEM) {
                messages.add(message);
            }
        }
        messages.add(
                textMessage(
                        "context-retained-" + request.getResponseMessageId(),
                        MessageRole.USER,
                        "<retained-context-reference>\n"
                                + "The messages after this marker remain verbatim after the "
                                + "checkpoint. Use them to reconcile the current state of goals "
                                + "and unfinished work. Do not duplicate their details unless "
                                + "they resolve or correct an earlier dependency.\n"
                                + "</retained-context-reference>"));
        for (AgentMessage message : request.getRetainedMessages()) {
            if (message.getRole() != MessageRole.SYSTEM) {
                messages.add(message);
            }
        }
        messages.add(
                textMessage(
                        "context-instruction-" + request.getResponseMessageId(),
                        MessageRole.USER,
                        compactionPrompt(request.isSplitTurn())));
        return Collections.unmodifiableList(messages);
    }

    /** 摘要调用的私有状态必须覆盖实际送入模型的全部真实消息，不关联合成提示消息。 */
    private static List<AgentMessage> summaryStateMessages(ContextCompactionRequest request) {
        List<AgentMessage> messages = new ArrayList<AgentMessage>();
        messages.addAll(request.getMessagesToSummarize());
        for (AgentMessage message : request.getRetainedMessages()) {
            if (message.getRole() != MessageRole.SYSTEM) {
                messages.add(message);
            }
        }
        return Collections.unmodifiableList(messages);
    }

    /** 摘要必须覆盖决策、约束、未完成工作与 Tool 事实，且不能假装已完成未知事项。 */
    private static String compactionPrompt(boolean splitTurn) {
        return "Create a precise context checkpoint for continuing this conversation.\n"
                + "Preserve user goals, confirmed decisions, constraints, important facts, "
                + "file or symbol references, tool outcomes, errors, and unfinished work.\n"
                + "Separate completed work from remaining work and describe the current effective "
                + "state after considering the retained context. A task completed, cancelled, or "
                + "superseded there must not be listed as unfinished. Do not invent details.\n"
                + "The original system messages and retained context remain verbatim after this "
                + "checkpoint, so do not reproduce them unless needed to resolve or correct an "
                + "earlier dependency.\n"
                + (splitTurn
                        ? "The retained context starts with an assistant message; summarize the "
                                + "earlier part of that turn so it remains coherent.\n"
                        : "")
                + "Return only the checkpoint text.";
    }

    /** 拒绝截断或缺计量响应，再投影保留消息所需的原 Provider 状态。 */
    private ContextCompactionResult toResult(
            ContextCompactionRequest request, ModelResponse response) {
        if (response == null) {
            throw new ModelGatewayException("上下文摘要模型返回空响应", false);
        }
        if (response.getStopReason() == ModelStopReason.MAX_TOKENS
                || response.getStopReason() == ModelStopReason.TOOL_USE) {
            throw new ModelGatewayException(
                    "上下文摘要未完整结束: " + response.getStopReason().getWireValue(), false);
        }
        if (response.getUsage() == null) {
            throw new ModelGatewayException("上下文摘要模型未返回必需 usage", false);
        }
        String summary = response.getText();
        if (summary == null || summary.trim().isEmpty()) {
            throw new ModelGatewayException("上下文摘要模型返回空摘要", false);
        }
        ModelState retainedState =
                stateProjector.project(request.getModelState(), request.getRetainedMessages());
        return new ContextCompactionResult(summary, response.getUsage(), retainedState);
    }

    /** 创建只包含正文的厂商中立模型消息。 */
    private static AgentMessage textMessage(String id, MessageRole role, String text) {
        return new AgentMessage(
                id, role, Collections.singletonList(new TextBlock(text)));
    }

    /** 把 ModelInvocation 的取消能力原样暴露给 Servlet Adapter。 */
    private static final class DefaultInvocation implements ContextCompactionInvocation {
        /** 真实模型调用。 */
        private final ModelInvocation modelInvocation;
        /** 映射后的不可变异步结果。 */
        private final CompletionStage<ContextCompactionResult> result;

        /** 绑定同一次模型调用和结果投影。 */
        private DefaultInvocation(
                ModelInvocation modelInvocation,
                CompletionStage<ContextCompactionResult> result) {
            this.modelInvocation = modelInvocation;
            this.result = result;
        }

        /** 返回压缩结果投影。 */
        @Override
        public CompletionStage<ContextCompactionResult> result() { return result; }

        /** 幂等取消真实模型调用。 */
        @Override
        public void cancel() { modelInvocation.cancel(); }
    }
}
