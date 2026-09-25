package io.patchbridge.agent.storage.jdbc;

import io.patchbridge.agent.core.model.target.ModelTargetRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.patchbridge.agent.core.conversation.Conversation;
import io.patchbridge.agent.core.conversation.ConversationConflictException;
import io.patchbridge.agent.core.conversation.ConversationContext;
import io.patchbridge.agent.core.conversation.ConversationContextValues;
import io.patchbridge.agent.core.conversation.ConversationNotFoundException;
import io.patchbridge.agent.core.conversation.ConversationSnapshot;
import io.patchbridge.agent.core.conversation.ModelContext;
import io.patchbridge.agent.core.conversation.ModelContextUsage;
import io.patchbridge.agent.core.model.AgentMessage;
import io.patchbridge.agent.core.model.ContentBlock;
import io.patchbridge.agent.core.model.ImageBlock;
import io.patchbridge.agent.core.model.ImageSource;
import io.patchbridge.agent.core.model.MessageRole;
import io.patchbridge.agent.core.model.ModelState;
import io.patchbridge.agent.core.model.ReasoningBlock;
import io.patchbridge.agent.core.model.TextBlock;
import io.patchbridge.agent.core.model.ToolCallBlock;
import io.patchbridge.agent.core.model.ToolResultBlock;
import io.patchbridge.agent.core.model.ToolResultStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC Conversation 聚合测试。
 *
 * <p>覆盖消息与 ModelContext 同 revision 原子保存、ContentBlock 结构往返、工作上下文重置、乐观锁、ownerKey 存储隔离以及损坏数据显式失败，不测试任何旧
 * payload 兼容行为。
 */
class JdbcConversationRepositoryTest {
    /** 本模块验证目标身份与完整上下文同事务保存。 */
    private static final ModelTargetRef REF = new ModelTargetRef("jdbc-model", 1);

    /** 被测仓储。 */
    private JdbcConversationRepository repository;

    /** 测试损坏持久化数据时使用的直接 SQL 入口。 */
    private JdbcTemplate jdbc;

    /** 每个测试使用独立内存库，避免 revision 与消息数据相互污染。 */
    @BeforeEach
    void setUp() throws SQLException {
        org.h2.jdbcx.JdbcDataSource dataSource = new org.h2.jdbcx.JdbcDataSource();
        // DB_CLOSE_DELAY=-1：setup 里的建库连接在方法返回后即可被回收，
        // 命名内存库必须跨连接存续到测试结束，否则 GC 后整库消失（与审计测试一致）。
        dataSource.setURL("jdbc:h2:mem:convtest" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ScriptUtils.executeSqlScript(
                dataSource.getConnection(), new ClassPathResource("agent-schema-h2.sql"));
        repository = new JdbcConversationRepository(dataSource, new ObjectMapper());
        jdbc = new JdbcTemplate(dataSource);
    }

    /** 创建和读取始终按不透明 ownerKey 严格隔离。 */
    @Test
    void createFindAndOwnership() {
        String tenantAOwner = "tenant-a:user-1";
        String tenantBOwner = "tenant-b:user-1";
        Conversation created = repository.create(tenantAOwner, "设备对话", emptyContext());

        assertEquals(0, created.getRevision());
        assertEquals(tenantAOwner, created.getOwnerKey());
        ConversationSnapshot loaded =
                repository.findSnapshot(tenantAOwner, created.getConversationId());
        assertEquals("设备对话", loaded.getConversation().getTitle());
        assertTrue(loaded.getContext().getMessages().isEmpty());
        assertEquals(
                ConversationContextValues.toModelContextValue(ModelContext.empty()),
                ConversationContextValues.toModelContextValue(
                        loaded.getContext().getModelContext()));
        assertNull(repository.findSnapshot(tenantBOwner, created.getConversationId()));
        assertTrue(repository.listByOwner(tenantBOwner, 10).isEmpty());
    }

    /** 所有第一版 Block 与嵌套 ModelState 均按稳定协议完整往返。 */
    @Test
    void savesAndRestoresCompleteConversationContext() throws Exception {
        Conversation created = repository.create("u1", null, emptyContext());
        ConversationContext expected = completeContext("state-1");

        Conversation saved =
                repository.save("u1", created.getConversationId(), 0, "第一回合", expected);
        ConversationSnapshot loaded = repository.findSnapshot("u1", created.getConversationId());

        assertEquals(1, saved.getRevision());
        assertEquals(1, loaded.getConversation().getRevision());
        assertEquals("第一回合", loaded.getConversation().getTitle());
        assertEquals(
                ConversationContextValues.toValue(expected),
                ConversationContextValues.toValue(loaded.getContext()));
    }

    /** 未知执行事实与目录基线必须经过真实 JDBC JSON 存储一起恢复。 */
    @Test
    void persistsUnknownExecutionAndToolBudgetBaseline() throws Exception {
        Conversation created = repository.create("u1", "未知执行", emptyContext());
        ConversationContext original = new ConversationContext(Arrays.asList(
                new AgentMessage("assistant", MessageRole.ASSISTANT,
                        Collections.<ContentBlock>singletonList(new ToolCallBlock(
                                "call", "local.action", Collections.<String, Object>emptyMap()))),
                new AgentMessage("tool", MessageRole.TOOL,
                        Collections.<ContentBlock>singletonList(new ToolResultBlock(
                                "call", "local.action", ToolResultStatus.ERROR,
                                ToolResultBlock.Execution.UNKNOWN,
                                Collections.singletonList(new TextBlock("中止记录")))))),
                REF, new ModelContext(null, null, null, new ModelContextUsage(
                        120, ModelContextUsage.Source.PROVIDER, "assistant", 70)));
        repository.save("u1", created.getConversationId(), 0, null, original);
        ConversationContext restored = repository.findSnapshot("u1", created.getConversationId()).getContext();
        assertEquals(ConversationContextValues.toValue(original), ConversationContextValues.toValue(restored));
        assertEquals(70, restored.getModelContext().getUsage().getToolDefinitionTokens());
        assertEquals(ToolResultBlock.Execution.UNKNOWN,
                ((ToolResultBlock) restored.getMessages().get(1).getBlocks().get(0)).getExecution());
    }

    /** 第二次保存全量替换消息，并且显式空 ModelContext 会清除旧工作状态。 */
    @Test
    void replacesMessagesAndClearsModelContext() throws Exception {
        Conversation created = repository.create("u1", null, emptyContext());
        repository.save("u1", created.getConversationId(), 0, null, completeContext("state-1"));
        ConversationContext replacement =
                new ConversationContext(
                        Collections.singletonList(
                                textMessage("replacement", MessageRole.USER, "继续")),
                        REF, ModelContext.empty());

        repository.save("u1", created.getConversationId(), 1, null, replacement);
        ConversationSnapshot loaded = repository.findSnapshot("u1", created.getConversationId());

        assertEquals(2, loaded.getConversation().getRevision());
        assertEquals(
                ConversationContextValues.toValue(replacement),
                ConversationContextValues.toValue(loaded.getContext()));
        String storedModelContext =
                jdbc.queryForObject(
                        "SELECT model_context_json FROM agent_conversation WHERE conversation_id = ?",
                        String.class,
                        created.getConversationId());
        assertEquals(
                ConversationContextValues.toModelContextValue(ModelContext.empty()),
                new ObjectMapper().readValue(storedModelContext, Map.class));
    }

    /** 过期 revision 既不能覆盖消息，也不能覆盖或清除模型状态。 */
    @Test
    void staleRevisionPreservesWholeContext() throws Exception {
        Conversation created = repository.create("u1", null, emptyContext());
        ConversationContext current = completeContext("current-state");
        repository.save("u1", created.getConversationId(), 0, null, current);

        ConversationConflictException conflict =
                assertThrows(
                        ConversationConflictException.class,
                        () ->
                                repository.save(
                                        "u1",
                                        created.getConversationId(),
                                        0,
                                        null,
                                        new ConversationContext(
                                                Collections.singletonList(
                                                        textMessage(
                                                                "stale", MessageRole.USER, "过期")),
                                                REF, ModelContext.empty())));

        assertEquals(1, conflict.getCurrentRevision());
        ConversationSnapshot loaded = repository.findSnapshot("u1", created.getConversationId());
        assertEquals(
                ConversationContextValues.toValue(current),
                ConversationContextValues.toValue(loaded.getContext()));
    }

    /** 其他 ownerKey 不能保存或删除目标会话的 Context。 */
    @Test
    void saveAndDeleteRemainTenantIsolated() throws Exception {
        Conversation created = repository.create("tenant-a:user-1", "a", emptyContext());
        ConversationContext context = completeContext("tenant-secret-state");
        repository.save("tenant-a:user-1", created.getConversationId(), 0, null, context);

        assertThrows(
                ConversationNotFoundException.class,
                () ->
                        repository.save(
                                "tenant-b:user-1", created.getConversationId(), 1, null, context));
        repository.delete("tenant-b:user-1", created.getConversationId());

        ConversationSnapshot loaded =
                repository.findSnapshot("tenant-a:user-1", created.getConversationId());
        assertEquals(
                ConversationContextValues.toValue(context),
                ConversationContextValues.toValue(loaded.getContext()));
    }

    /** 会话列表按更新时间排序，归属内删除保持幂等。 */
    @Test
    void listOrderedByUpdatedDescAndDeleteIsIdempotent() throws Exception {
        Conversation first = repository.create("u1", "a", emptyContext());
        Conversation second = repository.create("u1", "b", emptyContext());
        repository.save(
                "u1",
                first.getConversationId(),
                0,
                null,
                new ConversationContext(
                        Collections.singletonList(textMessage("m-1", MessageRole.USER, "更新")),
                        REF, ModelContext.empty()));

        List<Conversation> list = repository.listByOwner("u1", 10);
        assertEquals(2, list.size());
        assertEquals(first.getConversationId(), list.get(0).getConversationId());

        repository.delete("u1", first.getConversationId());
        repository.delete("u1", first.getConversationId());
        assertNull(repository.findSnapshot("u1", first.getConversationId()));
        assertTrue(
                repository
                        .findSnapshot("u1", second.getConversationId())
                        .getContext()
                        .getMessages()
                        .isEmpty());
    }

    /** 持久化 Block JSON 损坏时必须报错，不能用空消息伪造会话历史。 */
    @Test
    void corruptedBlocksFailExplicitly() throws Exception {
        Conversation created = repository.create("u1", null, emptyContext());
        repository.save(
                "u1",
                created.getConversationId(),
                0,
                null,
                new ConversationContext(
                        Collections.singletonList(textMessage("m-broken", MessageRole.USER, "正常")),
                        REF, ModelContext.empty()));
        jdbc.update(
                "UPDATE agent_message SET blocks_json = ? WHERE conversation_id = ?",
                "{not-json",
                created.getConversationId());

        assertThrows(
                IllegalStateException.class,
                () -> repository.findSnapshot("u1", created.getConversationId()));
    }

    /** 持久化 ModelContext 结构损坏时必须报错，不能静默丢弃后继续调用模型。 */
    @Test
    void corruptedModelStateFailsExplicitly() throws Exception {
        Conversation created = repository.create("u1", null, emptyContext());
        repository.save("u1", created.getConversationId(), 0, null, completeContext("state"));
        jdbc.update(
                "UPDATE agent_conversation SET model_context_json = ? " + "WHERE conversation_id = ?",
                "{\"format\":\"x\"}",
                created.getConversationId());

        assertThrows(
                IllegalStateException.class,
                () -> repository.findSnapshot("u1", created.getConversationId()));
    }

    /** 构造覆盖文本、图片、思考、Tool Call 与 Tool Result 的完整上下文。 */
    private ConversationContext completeContext(String stateMarker) {
        List<AgentMessage> messages = new ArrayList<AgentMessage>();
        messages.add(
                new AgentMessage(
                        "user-1",
                        MessageRole.USER,
                        Arrays.<ContentBlock>asList(
                                new TextBlock("查看设备"),
                                new ImageBlock(ImageSource.url("https://example.test/device.png")),
                                new ImageBlock(ImageSource.base64("image/png", "QUJD")))));

        Map<String, Object> input = new LinkedHashMap<String, Object>();
        input.put("deviceId", "D-1001");
        input.put("force", Boolean.FALSE);
        messages.add(
                new AgentMessage(
                        "assistant-1",
                        MessageRole.ASSISTANT,
                        Arrays.<ContentBlock>asList(
                                new ReasoningBlock("需要先查询状态"),
                                new ToolCallBlock("call-1", "local.device_get", input))));
        messages.add(
                new AgentMessage(
                        "tool-1",
                        MessageRole.TOOL,
                        Collections.<ContentBlock>singletonList(
                                new ToolResultBlock(
                                        "call-1",
                                        "local.device_get",
                                        ToolResultStatus.SUCCESS,
                                        ToolResultBlock.Execution.COMPLETED,
                                        Collections.singletonList(
                                                new TextBlock("{\"online\":true}"))))));
        messages.add(textMessage("assistant-2", MessageRole.ASSISTANT, "设备在线"));

        Map<String, Object> stateData = new LinkedHashMap<String, Object>();
        stateData.put("marker", stateMarker);
        stateData.put("items", Arrays.<Object>asList("opaque", Integer.valueOf(2), Boolean.TRUE));
        Map<String, Object> nested = new LinkedHashMap<String, Object>();
        nested.put("encryptedContent", "ciphertext");
        stateData.put("nested", nested);
        return new ConversationContext(
                messages,
                REF,
                new ModelContext(
                        null,
                        null,
                        new ModelState("openai-chat-reasoning/v1", stateData),
                        new ModelContextUsage(
                                120,
                                ModelContextUsage.Source.PROVIDER,
                                "assistant-2", 0)));
    }

    /** 空会话仍明确保存目标身份，供后续 revision 测试使用。 */
    private static ConversationContext emptyContext() {
        return new ConversationContext(Collections.<AgentMessage>emptyList(), REF, ModelContext.empty());
    }

    /** 构造单文本块稳定消息。 */
    private AgentMessage textMessage(String id, MessageRole role, String text) {
        return new AgentMessage(
                id, role, Collections.<ContentBlock>singletonList(new TextBlock(text)));
    }
}
