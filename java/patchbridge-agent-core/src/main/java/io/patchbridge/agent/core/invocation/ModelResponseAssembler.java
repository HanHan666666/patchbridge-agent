package io.patchbridge.agent.core.invocation;

import io.patchbridge.agent.core.error.ModelGatewayException;
import io.patchbridge.agent.core.model.AgentMessage;
import io.patchbridge.agent.core.model.BlockType;
import io.patchbridge.agent.core.model.ContentBlock;
import io.patchbridge.agent.core.model.MessageRole;
import io.patchbridge.agent.core.model.ModelBlockDeltaEvent;
import io.patchbridge.agent.core.model.ModelBlockStartEvent;
import io.patchbridge.agent.core.model.ModelBlockStopEvent;
import io.patchbridge.agent.core.model.ModelMessageStopEvent;
import io.patchbridge.agent.core.model.ModelResponse;
import io.patchbridge.agent.core.model.ModelStopReason;
import io.patchbridge.agent.core.model.ModelStreamEvent;
import io.patchbridge.agent.core.model.ModelStreamListener;
import io.patchbridge.agent.core.model.ReasoningBlock;
import io.patchbridge.agent.core.model.TextBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 把一次 Provider 结构化事件流严格聚合成完整 {@link ModelResponse}。
 *
 * <p>该实现只理解 Core 事件，不解析厂商字段或 Tool 参数 JSON。任何索引、块类型和终止序列冲突都会使当前调用明确失败，避免宿主收到不完整或被猜测修复的结果。
 */
final class ModelResponseAssembler implements ModelStreamListener {

    /** 聚合完成、失败与取消上游之间的最小协作端口。 */
    interface Terminal {

        /** 接收唯一的完整成功结果。 */
        void succeed(ModelResponse response);

        /** 接收唯一失败，并指明是否需要主动关闭尚在运行的上游。 */
        void fail(Throwable failure, boolean cancelUpstream);
    }

    /** 事件处理与终止切换的串行化边界。 */
    private final Object lifecycleLock = new Object();

    /** 响应 assistant 消息使用的稳定标识。 */
    private final String responseMessageId;

    /** Invocation 生命周期接收端；终止时清空，避免 Provider 保留 listener 间接持有 Invocation。 */
    private Terminal terminal;

    /** 以 index 排序的当前块聚合状态。 */
    private final Map<Integer, BlockAssembly> blocks = new TreeMap<Integer, BlockAssembly>();

    /** message-stop 到达后冻结的候选完整响应。 */
    private ModelResponse completedResponse;

    /** 完成、失败和协议拒绝共用的唯一终止标记。 */
    private boolean terminated;

    /** 创建只服务于一次调用的短生命周期聚合器。 */
    ModelResponseAssembler(String responseMessageId, Terminal terminal) {
        if (responseMessageId == null || responseMessageId.trim().isEmpty()) {
            throw new IllegalArgumentException("responseMessageId 不可为空");
        }
        if (terminal == null) {
            throw new IllegalArgumentException("terminal 不可为空");
        }
        this.responseMessageId = responseMessageId;
        this.terminal = terminal;
    }

    /** 严格处理一条结构化事件；协议失败后立即关闭事件入口并请求取消上游。 */
    @Override
    public void onEvent(ModelStreamEvent event) {
        ModelGatewayException failure = null;
        Terminal target = null;
        synchronized (lifecycleLock) {
            if (terminated) {
                return;
            }
            try {
                acceptEvent(event);
            } catch (RuntimeException e) {
                failure = protocolFailure("模型结构化事件序列非法", e);
                target = terminateAndClear();
            }
        }
        if (failure != null) {
            target.fail(failure, true);
        }
    }

    /** 上游正常结束时只接受已经由唯一 message-stop 冻结的完整响应。 */
    @Override
    public void onCompleted() {
        ModelResponse response = null;
        ModelGatewayException failure = null;
        Terminal target;
        synchronized (lifecycleLock) {
            if (terminated) {
                return;
            }
            if (completedResponse == null) {
                failure = protocolFailure("模型流在 message-stop 前结束", null);
            } else {
                response = completedResponse;
            }
            target = terminateAndClear();
        }
        if (failure == null) {
            target.succeed(response);
        } else {
            target.fail(failure, true);
        }
    }

    /** 上游异步失败时保留原始异常，不把网络或厂商失败伪装成协议成功。 */
    @Override
    public void onError(Throwable error) {
        boolean accepted;
        Terminal target = null;
        synchronized (lifecycleLock) {
            accepted = !terminated;
            if (accepted) {
                target = terminateAndClear();
            }
        }
        if (accepted) {
            target.fail(
                    error == null
                            ? new ModelGatewayException("模型 Provider 返回空失败", false)
                            : error,
                    false);
        }
    }

    /**
     * 主动关闭聚合器并释放所有中间态及 Invocation 回调引用。
     *
     * <p>Invocation 在自己的生命周期锁外调用该方法；本方法不反向调用 Invocation，从而避免取消与 Provider 回调之间形成锁顺序环。
     */
    void discard() {
        synchronized (lifecycleLock) {
            if (!terminated) {
                terminateAndClear();
            }
        }
    }

    /** 根据稳定事件类型分派到唯一协议校验入口。 */
    private void acceptEvent(ModelStreamEvent event) {
        if (event == null || event.getType() == null) {
            throw new IllegalArgumentException("模型流事件及类型不可为空");
        }
        if (completedResponse != null) {
            throw new IllegalStateException("message-stop 后不允许继续发布事件");
        }
        switch (event.getType()) {
            case BLOCK_START:
                requireEventClass(event, ModelBlockStartEvent.class);
                startBlock((ModelBlockStartEvent) event);
                return;
            case BLOCK_DELTA:
                requireEventClass(event, ModelBlockDeltaEvent.class);
                appendDelta((ModelBlockDeltaEvent) event);
                return;
            case BLOCK_STOP:
                requireEventClass(event, ModelBlockStopEvent.class);
                stopBlock((ModelBlockStopEvent) event);
                return;
            case MESSAGE_STOP:
                requireEventClass(event, ModelMessageStopEvent.class);
                stopMessage((ModelMessageStopEvent) event);
                return;
            default:
                throw new IllegalArgumentException("不支持的模型流事件类型: " + event.getType());
        }
    }

    /** 开始一个此前未出现且无需 Tool JSON 解析的文本或推理块。 */
    private void startBlock(ModelBlockStartEvent event) {
        if (blocks.containsKey(event.getIndex())) {
            throw new IllegalStateException("块 index 重复开始: " + event.getIndex());
        }
        BlockType type = event.getBlock().getType();
        if (type == BlockType.TOOL_CALL) {
            throw new IllegalStateException("Java 单次模型调用不接受 tool-call 输出");
        }
        if (type != BlockType.TEXT && type != BlockType.REASONING) {
            throw new IllegalStateException("Java 单次模型调用不支持输出块类型: " + type);
        }
        blocks.put(event.getIndex(), new BlockAssembly(type));
    }

    /** 只把同类型增量追加到已经开始且尚未停止的块。 */
    private void appendDelta(ModelBlockDeltaEvent event) {
        BlockAssembly block = requireOpenBlock(event.getIndex());
        if (block.type != event.getDelta().getType()) {
            throw new IllegalStateException("块增量类型与 block-start 不一致: " + event.getIndex());
        }
        block.content.append(event.getDelta().getText());
    }

    /** 精确关闭一个已经开始且尚未停止的块。 */
    private void stopBlock(ModelBlockStopEvent event) {
        BlockAssembly block = requireOpenBlock(event.getIndex());
        block.stopped = true;
    }

    /** 在所有块关闭后冻结唯一完整响应，实际成功仍等待 Provider onCompleted。 */
    private void stopMessage(ModelMessageStopEvent event) {
        if (blocks.isEmpty()) {
            throw new IllegalStateException("message-stop 前必须至少生成一个内容块");
        }
        for (Map.Entry<Integer, BlockAssembly> entry : blocks.entrySet()) {
            if (!entry.getValue().stopped) {
                throw new IllegalStateException("message-stop 时块尚未关闭: " + entry.getKey());
            }
        }
        if (event.getStopReason() == ModelStopReason.TOOL_USE) {
            throw new IllegalStateException("未声明 Tool 的 Java 单次调用不能以 tool-use 结束");
        }
        List<ContentBlock> content = new ArrayList<ContentBlock>(blocks.size());
        for (BlockAssembly block : blocks.values()) {
            content.add(block.toContentBlock());
        }
        AgentMessage message =
                new AgentMessage(responseMessageId, MessageRole.ASSISTANT, content);
        completedResponse =
                new ModelResponse(
                        message,
                        event.getStopReason(),
                        event.getUsage(),
                        event.getModelState());
    }

    /** 返回可接受增量或停止事件的已开始块。 */
    private BlockAssembly requireOpenBlock(int index) {
        BlockAssembly block = blocks.get(index);
        if (block == null) {
            throw new IllegalStateException("块尚未开始: " + index);
        }
        if (block.stopped) {
            throw new IllegalStateException("块已经停止: " + index);
        }
        return block;
    }

    /** 校验 Type 与事件运行时类型一致，拒绝自定义事件冒充已有协议类型。 */
    private static void requireEventClass(
            ModelStreamEvent event, Class<? extends ModelStreamEvent> expectedClass) {
        if (!expectedClass.isInstance(event)) {
            throw new IllegalArgumentException(
                    "事件类型与载荷类型不一致: " + event.getClass().getName());
        }
    }

    /** 用不可重试的网关异常统一表达 Provider 已归一化协议违反。 */
    private static ModelGatewayException protocolFailure(String message, Throwable cause) {
        return new ModelGatewayException(message, cause, false);
    }

    /** 释放本次聚合中间态和回调引用，返回出锁后需要接收终态的 Invocation。 */
    private Terminal terminateAndClear() {
        Terminal target = terminal;
        terminated = true;
        blocks.clear();
        completedResponse = null;
        terminal = null;
        return target;
    }

    /** 一个文本或推理块的短生命周期可变聚合状态。 */
    private static final class BlockAssembly {

        /** block-start 确定且后续不可改变的块类型。 */
        private final BlockType type;

        /** 尚未冻结为领域块的增量内容。 */
        private final StringBuilder content = new StringBuilder();

        /** block-stop 是否已经精确到达。 */
        private boolean stopped;

        /** 创建指定类型的空聚合状态。 */
        private BlockAssembly(BlockType type) {
            this.type = type;
        }

        /** 按块类型冻结为领域内容块，不保留 StringBuilder 引用。 */
        private ContentBlock toContentBlock() {
            String value = content.toString();
            return type == BlockType.TEXT ? new TextBlock(value) : new ReasoningBlock(value);
        }
    }
}
