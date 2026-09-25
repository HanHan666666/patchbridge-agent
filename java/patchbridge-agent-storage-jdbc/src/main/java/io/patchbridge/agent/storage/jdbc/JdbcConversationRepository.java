package io.patchbridge.agent.storage.jdbc;

import io.patchbridge.agent.core.conversation.Conversation;
import io.patchbridge.agent.core.conversation.ConversationConflictException;
import io.patchbridge.agent.core.conversation.ConversationContext;
import io.patchbridge.agent.core.conversation.ConversationContextValues;
import io.patchbridge.agent.core.conversation.ConversationNotFoundException;
import io.patchbridge.agent.core.conversation.ConversationRepository;
import io.patchbridge.agent.core.conversation.ConversationSnapshot;
import io.patchbridge.agent.core.conversation.ModelContext;
import io.patchbridge.agent.core.model.AgentMessage;
import io.patchbridge.agent.core.model.target.ModelTargetRef;
import io.patchbridge.agent.core.model.MessageRole;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

/**
 * ConversationRepository 的 JDBC 默认实现。
 *
 * <p>ConversationContext 是一个乐观锁聚合：完整消息与 ModelContext 在同一事务、同一 revision 下全量替换。详情读取使用 REPEATABLE_READ
 * 快照，避免并发保存时返回旧 revision 搭配 新上下文。所有入口都在 SQL 层携带 ownerKey，不信任仅凭 conversationId 的访问。
 *
 * <p>消息行只保存稳定 AgentMessage 的 id、role 和 blocks_json。SQL 仅面向项目明确支持的 H2 与 MySQL。
 */
public class JdbcConversationRepository implements ConversationRepository {

    /** 元数据查询的显式列，避免列表读取模型工作上下文 CLOB。 */
    private static final String CONVERSATION_COLUMNS =
            "conversation_id, owner_key, title, revision, status, created_at, updated_at";

    /** 会话元数据行映射；ownerKey 只保留在服务端领域对象中。 */
    private static final RowMapper<Conversation> CONVERSATION_MAPPER =
            new RowMapper<Conversation>() {
                @Override
                public Conversation mapRow(ResultSet rs, int rowNum) throws SQLException {
                    return new Conversation(
                            rs.getString("conversation_id"),
                            rs.getString("owner_key"),
                            rs.getString("title"),
                            rs.getLong("revision"),
                            rs.getString("status"),
                            rs.getTimestamp("created_at"),
                            rs.getTimestamp("updated_at"));
                }
            };

    /** 执行参数化 SQL 的统一入口。 */
    private final JdbcTemplate jdbc;

    /** 保存和删除完整聚合时使用的事务边界。 */
    private final TransactionTemplate writeTransaction;

    /** 保证 revision、消息和 ModelContext 来自同一可重复读快照的事务边界。 */
    private final TransactionTemplate snapshotTransaction;

    /** 只负责稳定 JSON 值与数据库文本之间转换的序列化器。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建 JDBC 会话仓储。
     *
     * @param dataSource 宿主提供的业务数据源
     * @param objectMapper 宿主统一 Jackson 配置，仅用于标准 Map/List JSON 值
     */
    public JdbcConversationRepository(DataSource dataSource, ObjectMapper objectMapper) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource 不可为空");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper 不可为空");
        }
        org.springframework.jdbc.datasource.DataSourceTransactionManager transactionManager =
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        this.jdbc = new JdbcTemplate(dataSource);
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.snapshotTransaction = new TransactionTemplate(transactionManager);
        this.snapshotTransaction.setReadOnly(true);
        this.snapshotTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.objectMapper = objectMapper;
    }

    /** 首轮完整上下文在一个事务内创建，失败回滚，不遗留空会话。 */
    @Override
    public Conversation create(final String ownerKey, final String title, ConversationContext context) {
        requireOwnerKey(ownerKey);
        if (context == null) throw new IllegalArgumentException("context 不可为空");
        final EncodedContext encoded = encodeContext(context);
        return writeTransaction.execute(status -> {
            String id = newId();
            Timestamp now = new Timestamp(System.currentTimeMillis());
            jdbc.update("INSERT INTO agent_conversation "
                    + "(conversation_id, owner_key, title, revision, status, model_context_json, model_target_json, created_at, updated_at) "
                    + "VALUES (?, ?, ?, 0, 'ACTIVE', ?, ?, ?, ?)",
                    id, ownerKey, title, encoded.modelContextJson, encoded.modelTargetJson, now, now);
            insertMessages(id, encoded.messages, now);
            return findConversation(ownerKey, id);
        });
    }

    /** 使用可重复读事务返回 ownerKey 可见的完整一致性快照。 */
    @Override
    public ConversationSnapshot findSnapshot(final String ownerKey, final String conversationId) {
        requireOwnerKey(ownerKey);
        return snapshotTransaction.execute(
                transactionStatus -> doFindSnapshot(ownerKey, conversationId));
    }

    /** 查询指定 ownerKey 可见的活跃会话元数据，不读取模型状态 CLOB。 */
    @Override
    public List<Conversation> listByOwner(String ownerKey, int limit) {
        requireOwnerKey(ownerKey);
        if (limit <= 0) {
            throw new IllegalArgumentException("会话列表 limit 必须大于 0");
        }
        return jdbc.query(
                "SELECT "
                        + CONVERSATION_COLUMNS
                        + " FROM agent_conversation "
                        + "WHERE owner_key = ? AND status = 'ACTIVE' "
                        + "ORDER BY updated_at DESC LIMIT ?",
                CONVERSATION_MAPPER,
                ownerKey,
                limit);
    }

    /** 在修改数据库前编码整个 Context，随后以一个 revision 原子提交。 */
    @Override
    public Conversation save(
            final String ownerKey,
            final String conversationId,
            final long expectedRevision,
            final String title,
            final ConversationContext context)
            throws ConversationConflictException {
        requireOwnerKey(ownerKey);
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision 不能小于 0");
        }
        if (context == null) {
            throw new IllegalArgumentException("conversationContext 不可为空");
        }
        final EncodedContext encoded = encodeContext(context);
        try {
            return writeTransaction.execute(
                    transactionStatus ->
                            doSave(ownerKey, conversationId, expectedRevision, title, encoded));
        } catch (ConflictSignal signal) {
            // TransactionCallback 不能直接声明受检异常，离开事务后恢复领域冲突类型。
            throw signal.conflict;
        }
    }

    /** 幂等删除指定 ownerKey 拥有的会话及消息。 */
    @Override
    public void delete(final String ownerKey, final String conversationId) {
        requireOwnerKey(ownerKey);
        writeTransaction.executeWithoutResult(
                transactionStatus -> {
                    jdbc.update(
                            "DELETE FROM agent_message WHERE conversation_id IN "
                                    + "(SELECT conversation_id FROM agent_conversation "
                                    + "WHERE conversation_id = ? AND owner_key = ?)",
                            conversationId,
                            ownerKey);
                    jdbc.update(
                            "DELETE FROM agent_conversation "
                                    + "WHERE conversation_id = ? AND owner_key = ?",
                            conversationId,
                            ownerKey);
                });
    }

    /** 在同一事务快照内先读取元数据和 ModelContext，再按顺序恢复消息。 */
    private ConversationSnapshot doFindSnapshot(String ownerKey, String conversationId) {
        List<ConversationStateRow> conversations =
                jdbc.query(
                        "SELECT "
                                + CONVERSATION_COLUMNS
                                + ", model_context_json, model_target_json FROM agent_conversation WHERE conversation_id"
                                + " = ? AND owner_key = ?",
                        new RowMapper<ConversationStateRow>() {
                            @Override
                            public ConversationStateRow mapRow(ResultSet rs, int rowNum)
                                    throws SQLException {
                                return new ConversationStateRow(
                                        CONVERSATION_MAPPER.mapRow(rs, rowNum),
                                        rs.getString("model_context_json"), rs.getString("model_target_json"));
                            }
                        },
                        conversationId,
                        ownerKey);
        if (conversations.isEmpty()) {
            return null;
        }
        ConversationStateRow row = conversations.get(0);
        List<AgentMessage> messages = loadMessages(ownerKey, conversationId);
        ModelContext modelContext = decodeModelContext(row.modelContextJson, conversationId);
        return new ConversationSnapshot(
                row.conversation, new ConversationContext(messages, ModelTargetRef.fromValue(readStoredJson(row.modelTargetJson, "modelTarget")), modelContext));
    }

    /** 乐观锁成功后在同一事务中替换 ModelContext 与全部消息。 */
    private Conversation doSave(
            String ownerKey,
            String conversationId,
            long expectedRevision,
            String title,
            EncodedContext context) {
        Date now = new Date();
        int updated =
                jdbc.update(
                        "UPDATE agent_conversation SET revision = revision + 1, updated_at = ?, "
                                + "title = COALESCE(?, title), model_context_json = ?, model_target_json = ? "
                                + "WHERE conversation_id = ? AND owner_key = ? AND revision = ?",
                        new Timestamp(now.getTime()),
                        title,
                        context.modelContextJson,
                        context.modelTargetJson,
                        conversationId,
                        ownerKey,
                        expectedRevision);
        if (updated == 0) {
            Conversation current = findConversation(ownerKey, conversationId);
            if (current == null) {
                throw new ConversationNotFoundException(
                        "会话不存在或当前归属主体无权访问: " + conversationId);
            }
            throw new ConflictSignal(
                    new ConversationConflictException(
                            "会话已被其他端更新: " + conversationId, current.getRevision()));
        }

        jdbc.update("DELETE FROM agent_message WHERE conversation_id = ?", conversationId);
        insertMessages(conversationId, context.messages, new Timestamp(now.getTime()));
        return findConversation(ownerKey, conversationId);
    }

    /** 创建与保存共用消息行写入规则，调用者已经持有完整聚合事务。 */
    private void insertMessages(String conversationId, List<EncodedMessage> messages, Timestamp createdAt) {
        for (EncodedMessage message : messages) {
            jdbc.update("INSERT INTO agent_message (message_id, conversation_id, seq, role, blocks_json, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    message.id, conversationId, message.sequence, message.role, message.blocksJson, createdAt);
        }
    }

    /** 使用显式元数据列查询单个会话，不触碰 ModelContext。 */
    private Conversation findConversation(String ownerKey, String conversationId) {
        List<Conversation> rows =
                jdbc.query(
                        "SELECT "
                                + CONVERSATION_COLUMNS
                                + " FROM agent_conversation "
                                + "WHERE conversation_id = ? AND owner_key = ?",
                        CONVERSATION_MAPPER,
                        conversationId,
                        ownerKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 每条消息查询都通过父表再次约束 ownerKey，避免依赖调用顺序完成隔离。 */
    private List<AgentMessage> loadMessages(String ownerKey, String conversationId) {
        return jdbc.query(
                "SELECT m.message_id, m.role, m.blocks_json FROM agent_message m "
                        + "INNER JOIN agent_conversation c "
                        + "ON c.conversation_id = m.conversation_id "
                        + "WHERE c.conversation_id = ? AND c.owner_key = ? ORDER BY m.seq",
                new RowMapper<AgentMessage>() {
                    @Override
                    public AgentMessage mapRow(ResultSet rs, int rowNum) throws SQLException {
                        String messageId = rs.getString("message_id");
                        String role = rs.getString("role");
                        String blocksJson = rs.getString("blocks_json");
                        return new AgentMessage(
                                messageId,
                                MessageRole.fromWireValue(role),
                                ConversationContextValues.fromBlocksValue(
                                        readStoredJson(blocksJson, "消息 " + messageId + " blocks")));
                    }
                },
                conversationId,
                ownerKey);
    }

    /** 在事务前把领域 Context 编码为不可变 JDBC 参数，序列化失败不得占用 revision。 */
    private EncodedContext encodeContext(ConversationContext context) {
        List<EncodedMessage> messages = new ArrayList<EncodedMessage>(context.getMessages().size());
        int sequence = 0;
        for (AgentMessage message : context.getMessages()) {
            messages.add(
                    new EncodedMessage(
                            message.getId(),
                            sequence++,
                            message.getRole().getWireValue(),
                            writeRequestJson(
                                    ConversationContextValues.toBlocksValue(message.getBlocks()),
                                    "消息 " + message.getId() + " blocks")));
        }
        String modelContextJson =
                writeRequestJson(
                        ConversationContextValues.toModelContextValue(
                                context.getModelContext()),
                        "modelContext");
        return new EncodedContext(messages, modelContextJson, writeRequestJson(context.getModelTarget().toValue(), "modelTarget"));
    }

    /** 把持久化 ModelContext JSON 恢复为领域状态；为空或损坏时明确失败。 */
    private ModelContext decodeModelContext(String modelContextJson, String conversationId) {
        try {
            return ConversationContextValues.fromModelContextValue(
                    readStoredJson(
                            modelContextJson, "会话 " + conversationId + " modelContext"));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("会话模型工作上下文数据损坏: " + conversationId, e);
        }
    }

    /** 保存请求中的稳定值必须在进入事务前成功序列化。 */
    private String writeRequestJson(Object value, String field) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(field + " 无法序列化为 JSON", e);
        }
    }

    /** 数据库 JSON 损坏时显式失败，不能伪造空内容继续对话。 */
    private Object readStoredJson(String json, String field) {
        if (json == null) {
            throw new IllegalStateException(field + " 数据为空");
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            throw new IllegalStateException(field + " 数据损坏，无法解析", e);
        }
    }

    /** 强制 ownerKey 非空，避免错误直接调用退化为共享归属。 */
    private static void requireOwnerKey(String ownerKey) {
        if (ownerKey == null || ownerKey.trim().isEmpty()) {
            throw new IllegalArgumentException("ownerKey 不能为空");
        }
    }

    /** 生成不依赖数据库方言的紧凑 UUID，仅用于服务器拥有的会话标识。 */
    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 一致性读取时会话元数据与原始 ModelContext JSON 的内部组合。 */
    private static final class ConversationStateRow {
        /** 会话元数据。 */
        private final Conversation conversation;

        /** 尚未解释的状态 JSON。 */
        private final String modelContextJson;
        /** 与工作上下文原子保存的目标身份。 */
        private final String modelTargetJson;

        /** 创建数据库内部行快照。 */
        private ConversationStateRow(Conversation conversation, String modelContextJson, String modelTargetJson) {
            this.conversation = conversation;
            this.modelContextJson = modelContextJson;
            this.modelTargetJson = modelTargetJson;
        }
    }

    /** 进入事务前已经完成编码的完整上下文。 */
    private static final class EncodedContext {
        /** 按 sequence 排序的消息行。 */
        private final List<EncodedMessage> messages;

        /** 不可空的完整 ModelContext JSON。 */
        private final String modelContextJson;
        /** 与工作上下文原子保存的目标身份。 */
        private final String modelTargetJson;

        /** 创建不可变 JDBC 参数快照。 */
        private EncodedContext(List<EncodedMessage> messages, String modelContextJson, String modelTargetJson) {
            this.messages = Collections.unmodifiableList(new ArrayList<EncodedMessage>(messages));
            this.modelContextJson = modelContextJson;
            this.modelTargetJson = modelTargetJson;
        }
    }

    /** 单条稳定消息的 JDBC 参数。 */
    private static final class EncodedMessage {
        /** 框架稳定消息 ID。 */
        private final String id;

        /** 会话内顺序。 */
        private final int sequence;

        /** 公共协议角色值。 */
        private final String role;

        /** 有序 ContentBlock JSON。 */
        private final String blocksJson;

        /** 创建不可变消息行参数。 */
        private EncodedMessage(String id, int sequence, String role, String blocksJson) {
            this.id = id;
            this.sequence = sequence;
            this.role = role;
            this.blocksJson = blocksJson;
        }
    }

    /** 只用于把受检乐观锁冲突带出 Spring 事务回调。 */
    private static final class ConflictSignal extends RuntimeException {
        /** 事务外需要恢复的领域冲突。 */
        private final ConversationConflictException conflict;

        /** 创建不生成堆栈的内部控制流信号。 */
        private ConflictSignal(ConversationConflictException conflict) {
            super(conflict.getMessage(), conflict, false, false);
            this.conflict = conflict;
        }
    }
}
