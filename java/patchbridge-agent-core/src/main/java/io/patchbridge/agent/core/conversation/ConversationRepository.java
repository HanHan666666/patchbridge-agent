package io.patchbridge.agent.core.conversation;

import java.util.List;

/**
 * 会话持久化 SPI。后端是 Persisted Conversation 的 Source of Truth； localStorage 只能做 UX 缓存。
 *
 * <p>所有方法都显式携带不透明 ownerKey：归属校验是存储层的责任，不是调用方的礼貌。 ownerKey 的业务语义由宿主通过 {@link
 * ConversationOwnerResolver} 定义，Repository 只进行严格等值匹配，不自行拆解租户或用户标识。 保存策略为“整回合替换”：客户端提交完整
 * ConversationContext，服务端在事务内 同时更新稳定消息和 ModelState。
 */
public interface ConversationRepository {

    /**
     * 在指定归属主体下新建空会话，revision = 0。
     *
     * @param ownerKey 非空的不透明会话归属键
     * @param title 可选会话标题
     */
    Conversation create(String ownerKey, String title);

    /**
     * 按归属键查询单个一致性会话快照；不存在或不属于该归属返回 null。
     *
     * @param ownerKey 非空的不透明会话归属键
     * @param conversationId 会话全局标识
     */
    ConversationSnapshot findSnapshot(String ownerKey, String conversationId);

    /**
     * 查询当前归属主体的会话列表，按 updatedAt 倒序。
     *
     * @param ownerKey 非空的不透明会话归属键
     * @param limit 最大返回数量
     */
    List<Conversation> listByOwner(String ownerKey, int limit);

    /**
     * 保存整个会话（乐观锁）。
     *
     * @param ownerKey 非空的不透明会话归属键
     * @param conversationId 会话全局标识
     * @param expectedRevision 客户端持有的 revision；与服务端不一致时抛 {@link ConversationConflictException}（多 Tab
     *     并发写）
     * @param title 会话标题（null 表示不变更）
     * @param context 完整会话上下文，消息与 ModelState 必须作为一个聚合保存
     * @throws ConversationNotFoundException 当前 owner 范围内找不到目标会话
     * @throws ConversationConflictException 目标存在但 revision 已被其他端推进
     */
    Conversation save(
            String ownerKey,
            String conversationId,
            long expectedRevision,
            String title,
            ConversationContext context)
            throws ConversationConflictException;

    /**
     * 删除会话及其消息；不存在或不属于该归属时静默返回（删除是幂等操作）。
     *
     * @param ownerKey 非空的不透明会话归属键
     * @param conversationId 会话全局标识
     */
    void delete(String ownerKey, String conversationId);
}
