package io.patchbridge.agent.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 验证 Java 单次调用 Builder 只组合既有领域消息，并保持多模态块顺序。 */
class ModelRequestsTest {

    /** System 与 User 分别形成一条消息，用户文本和图片保留在同一个有序多模态消息中。 */
    @Test
    void buildsSystemAndMultimodalUserMessages() {
        ImageSource image = ImageSource.url("https://example.com/image.png");
        ModelState state = new ModelState("provider/v1", null);

        ModelRequest request =
                ModelRequests.builder()
                        .responseMessageId("response-1")
                        .systemText("规则一")
                        .systemText("规则二")
                        .userText("检查图片")
                        .userImage(image)
                        .modelTarget(ModelTestTargets.REF)
                        .modelState(state)
                        .temperature(0.2)
                        .maxTokens(300)
                        .build();

        assertEquals("response-1", request.getResponseMessageId());
        assertEquals(ModelTestTargets.REF, request.getModelTarget());
        assertSame(state, request.getModelState());
        assertEquals(0.2, request.getTemperature());
        assertEquals(300, request.getMaxTokens());
        assertTrue(request.getTools().isEmpty());
        assertEquals(2, request.getMessages().size());

        AgentMessage system = request.getMessages().get(0);
        assertEquals(MessageRole.SYSTEM, system.getRole());
        assertEquals(2, system.getBlocks().size());
        assertEquals("规则一", ((TextBlock) system.getBlocks().get(0)).getText());
        assertEquals("规则二", ((TextBlock) system.getBlocks().get(1)).getText());

        AgentMessage user = request.getMessages().get(1);
        assertEquals(MessageRole.USER, user.getRole());
        assertEquals("检查图片", ((TextBlock) user.getBlocks().get(0)).getText());
        assertSame(image, ((ImageBlock) user.getBlocks().get(1)).getSource());
    }

    /** 未显式提供消息标识时，每次构建都生成非空且互不复用的调用身份。 */
    @Test
    void generatesFreshMessageIdentifiers() {
        ModelRequest first = ModelRequests.builder().modelTarget(ModelTestTargets.REF).userText("a").build();
        ModelRequest second = ModelRequests.builder().modelTarget(ModelTestTargets.REF).userText("b").build();

        assertNotNull(first.getResponseMessageId());
        assertNotNull(first.getMessages().get(0).getId());
        assertTrue(!first.getResponseMessageId().equals(second.getResponseMessageId()));
    }
}
