package io.patchbridge.agent.starter.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.patchbridge.agent.core.audit.AuditEvent;
import io.patchbridge.agent.core.audit.AuditSink;
import io.patchbridge.agent.core.auth.CurrentUserProvider;
import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.model.BlockType;
import io.patchbridge.agent.core.model.ModelBlockDeltaEvent;
import io.patchbridge.agent.core.model.ModelCall;
import io.patchbridge.agent.core.model.ModelProvider;
import io.patchbridge.agent.core.model.ModelRequest;
import io.patchbridge.agent.core.model.ModelStreamEvent;
import io.patchbridge.agent.core.model.ModelStreamListener;
import io.patchbridge.agent.core.model.ModelProtocolAdapter;
import io.patchbridge.agent.core.model.ModelState;
import io.patchbridge.agent.core.model.AgentMessage;
import io.patchbridge.agent.core.model.target.*;
import io.patchbridge.agent.core.conversation.ConversationRepository;
import io.patchbridge.agent.core.compaction.ContextCompactionSettings;
import io.patchbridge.agent.core.user.UserContext;
import io.patchbridge.agent.core.invocation.ModelInvocationPipeline;
import io.patchbridge.agent.starter.PatchBridgeAgentProperties;
import io.patchbridge.agent.starter.audit.AuditRecorder;
import io.patchbridge.agent.starter.web.dto.ModelStreamEnvelope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 浏览器 SSE 会话的事件发送与终止切换串行化测试（二次审计 Q-09）。
 *
 * <p>用一个首帧停在栅栏上的 ObjectMapper 把事件发送真实卡在会话锁内，
 * 与此并发的失败终止在修复前可以越过未完成的发送先完成审计与关流——
 * 即终止之后仍可能出现迟到帧。SseEmitter 未初始化时会缓冲发送内容，
 * 因此无需容器即可驱动完整会话。
 */
class ModelStreamControllerStreamSessionTest {
    /** 真实路由与请求 DTO 共同使用的模型目标。 */
    private static final ModelTargetRef REF = new ModelTargetRef("race-model", 1);

    /** 事件发送进行中时，失败终止必须等待发送结束；终止后不得再出现任何新帧。 */
    @Test
    void errorTerminationWaitsForInFlightEventFrame() throws Exception {
        ParkedFirstFrameMapper mapper = new ParkedFirstFrameMapper();
        List<AuditEvent> audits = Collections.synchronizedList(new ArrayList<AuditEvent>());
        AtomicReference<ModelStreamListener> providerListener = new AtomicReference<ModelStreamListener>();
        AtomicInteger providerCancels = new AtomicInteger();

        ModelProvider provider = new ModelProvider() {
            @Override
            public ModelCall stream(ModelRequest request, ModelStreamListener listener) {
                providerListener.set(listener);
                return () -> providerCancels.incrementAndGet();
            }
        };
        ModelProviderRouter modelRouter = router(provider);
        ModelStreamController controller = new ModelStreamController(
                new ModelInvocationPipeline(modelRouter, Collections.emptyList()),
                userProvider(),
                recorder(audits),
                new ModelConversationService(mock(ConversationRepository.class),
                        user -> user.getUserId(), modelRouter),
                mapper);

        Map<String, Object> block = new LinkedHashMap<String, Object>();
        block.put("type", "text");
        block.put("text", "race-probe");
        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("id", "user-1");
        message.put("role", "user");
        message.put("blocks", Collections.singletonList(block));
        ModelStreamEnvelope.Request requestDto = new ModelStreamEnvelope.Request();
        requestDto.setResponseMessageId("resp-race");
        requestDto.setModelTarget(REF.toValue());
        requestDto.setMessages(Collections.singletonList(message));
        requestDto.setTools(Collections.emptyList());
        requestDto.setModelState(null);
        ModelStreamEnvelope envelope = new ModelStreamEnvelope();
        envelope.setTraceId("trace-race");
        envelope.setRequest(requestDto);
        ResponseEntity<SseEmitter> response = controller.stream(envelope);
        assertTrue(response.getStatusCode().is2xxSuccessful());
        ModelStreamListener terminal = providerListener.get();

        Thread eventThread = new Thread(() -> terminal.onEvent(deltaEvent()));
        eventThread.start();
        assertTrue(mapper.firstFrameStarted.await(5, TimeUnit.SECONDS), "事件帧应已进入序列化");

        Thread errorThread = new Thread(() -> terminal.onError(new IllegalStateException("upstream failed")));
        errorThread.start();
        Thread.sleep(200);
        assertEquals(1, mapper.serializedFrames.get(), "事件帧未送完前不得开始序列化错误帧");
        assertEquals(0, audits.size(), "事件帧未送完前失败终止不得先完成审计");

        mapper.releaseFirstFrame.countDown();
        eventThread.join(5000);
        errorThread.join(5000);
        assertFalse(eventThread.isAlive());
        assertFalse(errorThread.isAlive());

        assertEquals(2, mapper.serializedFrames.get(), "事件帧与错误帧各序列化一次");
        assertEquals(1, audits.size(), "审计恰记录一次");
        assertEquals("MODEL_FAILED", audits.get(0).getErrorCode());

        // 终止后的迟到事件必须被会话入口丢弃，不再产生任何序列化。
        terminal.onEvent(deltaEvent());
        terminal.onEvent(deltaEvent());
        assertEquals(2, mapper.serializedFrames.get(), "终止后不得再发送任何事件帧");
        assertEquals(1, audits.size());
    }

    /** 并发发送测试仍验证真实 Router 到模型流的完整管线。 */
    private static ModelProviderRouter router(ModelProvider provider) {
        ModelProtocolAdapter adapter = new ModelProtocolAdapter() {
            /** 此用例仅检查 SSE 终态，不解释厂商编码。 */
            @Override public void validate(ModelRequest request) { }
            /** 将监听器交给可控模型替身。 */
            @Override public ModelCall stream(ModelRequest request, ModelStreamListener listener) {
                return provider.stream(request, listener);
            }
            /** 该请求没有私有续接状态。 */
            @Override public ModelState project(ModelState state, List<AgentMessage> messages) { return state; }
        };
        ResolvedModelTarget target = new ResolvedModelTarget(REF, "并发模型", "test", true,
                true, true, new ContextCompactionSettings(128000, 20000, 12800), adapter);
        return new ModelProviderRouter(new ImmutableModelTargetCatalog(
                Collections.singletonList(target), REF), (access, selected) -> true);
    }

    /** 首帧停在栅栏上的序列化器：把事件发送卡在会话锁内，制造真实并发窗口。 */
    private static final class ParkedFirstFrameMapper extends ObjectMapper {
        private final CountDownLatch firstFrameStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirstFrame = new CountDownLatch(1);
        private final AtomicInteger serializedFrames = new AtomicInteger();

        @Override
        public String writeValueAsString(Object value) throws JsonProcessingException {
            serializedFrames.incrementAndGet();
            if (serializedFrames.get() == 1) {
                firstFrameStarted.countDown();
                try {
                    if (!releaseFirstFrame.await(5, TimeUnit.SECONDS)) {
                        throw new JsonProcessingException("首帧栅栏超时") {};
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new JsonProcessingException("首帧栅栏被中断", e) {};
                }
            }
            return super.writeValueAsString(value);
        }
    }

    /** 返回固定登录用户的认证 Provider。 */
    private static CurrentUserProvider userProvider() {
        return new CurrentUserProvider() {
            @Override
            public UserContext currentUser(AiRequestContext request) {
                return UserContext.builder().userId("u-1").username("tester").tenantId("t-1").build();
            }
        };
    }

    /** 记录审计事件的内存记录器，payload 关闭以避免无关序列化。 */
    private static AuditRecorder recorder(List<AuditEvent> audits) {
        PatchBridgeAgentProperties.Audit config = new PatchBridgeAgentProperties().getAudit();
        config.setEnabled(true);
        return new AuditRecorder(
                new AuditSink() {
                    @Override
                    public void write(AuditEvent event) {
                        audits.add(event);
                    }
                },
                (event, summary) -> summary,
                new ObjectMapper(),
                config);
    }

    /** 创建最小文本增量事件。 */
    private static ModelStreamEvent deltaEvent() {
        return new ModelBlockDeltaEvent(0, ModelBlockDeltaEvent.Delta.text(BlockType.TEXT, "x"));
    }
}
