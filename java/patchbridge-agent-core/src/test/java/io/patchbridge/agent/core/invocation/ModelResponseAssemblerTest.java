package io.patchbridge.agent.core.invocation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.patchbridge.agent.core.error.ModelGatewayException;
import io.patchbridge.agent.core.model.BlockType;
import io.patchbridge.agent.core.model.ContentBlock;
import io.patchbridge.agent.core.model.ModelBlockDeltaEvent;
import io.patchbridge.agent.core.model.ModelBlockStartEvent;
import io.patchbridge.agent.core.model.ModelBlockStopEvent;
import io.patchbridge.agent.core.model.ModelMessageStopEvent;
import io.patchbridge.agent.core.model.ModelResponse;
import io.patchbridge.agent.core.model.ModelState;
import io.patchbridge.agent.core.model.ModelStopReason;
import io.patchbridge.agent.core.model.ModelUsage;
import io.patchbridge.agent.core.model.ReasoningBlock;
import io.patchbridge.agent.core.model.TextBlock;

import org.junit.jupiter.api.Test;

import java.util.Collections;

/** 验证完整结果聚合严格遵守块索引、类型和唯一终止协议。 */
class ModelResponseAssemblerTest {

    /** 多块可以交错接收，但最终消息按 index 排序并无损保留停止元数据。 */
    @Test
    void assemblesOrderedBlocksAndPreservesMetadata() {
        RecordingTerminal terminal = new RecordingTerminal();
        ModelResponseAssembler assembler = new ModelResponseAssembler("response-1", terminal);
        ModelState state =
                new ModelState(
                        "provider/v1",
                        Collections.<String, Object>singletonMap("token", "opaque"));
        ModelUsage usage = new ModelUsage(10, 3, 13);

        assembler.onEvent(
                new ModelBlockStartEvent(
                        1, ModelBlockStartEvent.Block.content(BlockType.TEXT)));
        assembler.onEvent(
                new ModelBlockStartEvent(
                        0, ModelBlockStartEvent.Block.content(BlockType.REASONING)));
        assembler.onEvent(
                new ModelBlockDeltaEvent(
                        1, ModelBlockDeltaEvent.Delta.text(BlockType.TEXT, "结果")));
        assembler.onEvent(
                new ModelBlockDeltaEvent(
                        0, ModelBlockDeltaEvent.Delta.text(BlockType.REASONING, "思考")));
        assembler.onEvent(new ModelBlockStopEvent(1));
        assembler.onEvent(new ModelBlockStopEvent(0));
        assembler.onEvent(new ModelMessageStopEvent(ModelStopReason.END_TURN, state, usage));

        assertNull(terminal.response, "Provider onCompleted 前不得发布部分结果");
        assembler.onCompleted();

        ModelResponse response = terminal.response;
        assertEquals("response-1", response.getMessage().getId());
        assertEquals(2, response.getMessage().getBlocks().size());
        ContentBlock first = response.getMessage().getBlocks().get(0);
        ContentBlock second = response.getMessage().getBlocks().get(1);
        assertEquals("思考", ((ReasoningBlock) first).getText());
        assertEquals("结果", ((TextBlock) second).getText());
        assertEquals("结果", response.getText(), "便捷文本不得混入 reasoning");
        assertEquals(ModelStopReason.END_TURN, response.getStopReason());
        assertSame(state, response.getModelState());
        assertSame(usage, response.getUsage());
        assertNull(terminal.failure);
    }

    /** delta 不得在相应 block-start 之前出现。 */
    @Test
    void rejectsDeltaBeforeStart() {
        RecordingTerminal terminal = new RecordingTerminal();
        ModelResponseAssembler assembler = new ModelResponseAssembler("response-1", terminal);

        assembler.onEvent(
                new ModelBlockDeltaEvent(
                        0, ModelBlockDeltaEvent.Delta.text(BlockType.TEXT, "x")));

        assertProtocolFailure(terminal);
    }

    /** 同一个 index 不能重复开始或在停止后再次停止。 */
    @Test
    void rejectsDuplicateStartAndStop() {
        RecordingTerminal duplicateStart = new RecordingTerminal();
        ModelResponseAssembler first = new ModelResponseAssembler("response-1", duplicateStart);
        first.onEvent(textStart(0));
        first.onEvent(textStart(0));
        assertProtocolFailure(duplicateStart);

        RecordingTerminal duplicateStop = new RecordingTerminal();
        ModelResponseAssembler second = new ModelResponseAssembler("response-2", duplicateStop);
        second.onEvent(textStart(0));
        second.onEvent(new ModelBlockStopEvent(0));
        second.onEvent(new ModelBlockStopEvent(0));
        assertProtocolFailure(duplicateStop);
    }

    /** 增量类型必须与 block-start 确定的类型完全一致。 */
    @Test
    void rejectsDeltaTypeMismatch() {
        RecordingTerminal terminal = new RecordingTerminal();
        ModelResponseAssembler assembler = new ModelResponseAssembler("response-1", terminal);

        assembler.onEvent(textStart(0));
        assembler.onEvent(
                new ModelBlockDeltaEvent(
                        0, ModelBlockDeltaEvent.Delta.text(BlockType.REASONING, "x")));

        assertProtocolFailure(terminal);
    }

    /** Provider 完成前必须发布唯一 message-stop，并且所有块已经关闭。 */
    @Test
    void rejectsMissingOrPrematureMessageStop() {
        RecordingTerminal missing = new RecordingTerminal();
        ModelResponseAssembler first = new ModelResponseAssembler("response-1", missing);
        first.onEvent(textStart(0));
        first.onEvent(new ModelBlockStopEvent(0));
        first.onCompleted();
        assertProtocolFailure(missing);

        RecordingTerminal openBlock = new RecordingTerminal();
        ModelResponseAssembler second = new ModelResponseAssembler("response-2", openBlock);
        second.onEvent(textStart(0));
        second.onEvent(new ModelMessageStopEvent(ModelStopReason.END_TURN, null, null));
        assertProtocolFailure(openBlock);
    }

    /** Java 单次调用既不接受 tool-call 块，也不接受没有 Tool 定义的 tool-use 终止原因。 */
    @Test
    void rejectsToolOutput() {
        RecordingTerminal toolBlock = new RecordingTerminal();
        ModelResponseAssembler first = new ModelResponseAssembler("response-1", toolBlock);
        first.onEvent(
                new ModelBlockStartEvent(
                        0, ModelBlockStartEvent.Block.toolCall("call-1", "local.test")));
        assertProtocolFailure(toolBlock);

        RecordingTerminal toolStop = new RecordingTerminal();
        ModelResponseAssembler second = new ModelResponseAssembler("response-2", toolStop);
        second.onEvent(textStart(0));
        second.onEvent(new ModelBlockStopEvent(0));
        second.onEvent(new ModelMessageStopEvent(ModelStopReason.TOOL_USE, null, null));
        assertProtocolFailure(toolStop);
    }

    /** message-stop 只能出现一次，且其后任何事件都不能被当成另一条响应继续聚合。 */
    @Test
    void rejectsDuplicateMessageStop() {
        RecordingTerminal terminal = new RecordingTerminal();
        ModelResponseAssembler assembler = new ModelResponseAssembler("response-1", terminal);
        assembler.onEvent(textStart(0));
        assembler.onEvent(new ModelBlockStopEvent(0));
        assembler.onEvent(new ModelMessageStopEvent(ModelStopReason.END_TURN, null, null));

        assembler.onEvent(new ModelMessageStopEvent(ModelStopReason.END_TURN, null, null));

        assertProtocolFailure(terminal);
    }

    /** 上游错误保留原始身份且不要求二次取消已经失败的连接，迟到事件不得改写失败。 */
    @Test
    void preservesUpstreamFailureAndDropsLateEvents() {
        RecordingTerminal terminal = new RecordingTerminal();
        ModelResponseAssembler assembler = new ModelResponseAssembler("response-1", terminal);
        IllegalStateException failure = new IllegalStateException("upstream failed");

        assembler.onError(failure);
        assembler.onEvent(textStart(0));
        assembler.onCompleted();

        assertSame(failure, terminal.failure);
        assertFalse(terminal.cancelUpstream);
        assertNull(terminal.response);
        assertEquals(1, terminal.terminalCalls);
    }

    /** 创建最小文本块开始事件。 */
    private static ModelBlockStartEvent textStart(int index) {
        return new ModelBlockStartEvent(
                index, ModelBlockStartEvent.Block.content(BlockType.TEXT));
    }

    /** 校验失败被标记为不可重试的协议错误并要求取消上游。 */
    private static void assertProtocolFailure(RecordingTerminal terminal) {
        assertTrue(terminal.failure instanceof ModelGatewayException);
        assertFalse(((ModelGatewayException) terminal.failure).isRetryable());
        assertTrue(terminal.cancelUpstream);
        assertNull(terminal.response);
        assertEquals(1, terminal.terminalCalls);
    }

    /** 记录聚合器唯一终止输出的测试接收端。 */
    private static final class RecordingTerminal implements ModelResponseAssembler.Terminal {

        /** 成功响应。 */
        private ModelResponse response;

        /** 失败原因。 */
        private Throwable failure;

        /** 失败是否要求关闭上游。 */
        private boolean cancelUpstream;

        /** 终止回调总数。 */
        private int terminalCalls;

        /** 记录唯一成功响应。 */
        @Override
        public void succeed(ModelResponse response) {
            this.response = response;
            terminalCalls += 1;
        }

        /** 记录唯一失败和取消意图。 */
        @Override
        public void fail(Throwable failure, boolean cancelUpstream) {
            this.failure = failure;
            this.cancelUpstream = cancelUpstream;
            terminalCalls += 1;
        }
    }
}
