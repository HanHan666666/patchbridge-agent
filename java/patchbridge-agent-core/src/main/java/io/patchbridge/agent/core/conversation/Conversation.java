package io.patchbridge.agent.core.conversation;

import java.util.Date;

/**
 * 持久化会话（跨刷新、跨设备恢复的单位）。
 *
 * <p>注意与 Runtime State 的边界：本对象只描述“对话事实”，
 * 不承载“当前执行到哪一步”——后者属于浏览器，服务器不维护。
 * revision 用于多 Tab / 多设备并发保存时的乐观锁。
 */
public final class Conversation {

    /** 会话全局标识。 */
    private final String conversationId;

    /** 由宿主解析、仅供存储隔离使用的不透明归属键。 */
    private final String ownerKey;

    /** 用户可见的会话标题。 */
    private final String title;

    /** 防止多端静默覆盖的乐观锁版本。 */
    private final long revision;

    /** 会话生命周期状态。 */
    private final String status;

    /** 会话创建时间。 */
    private final Date createdAt;

    /** 最近一次持久化更新时间。 */
    private final Date updatedAt;

    /**
     * 创建持久化会话快照；对象不承载浏览器 Agent 的运行时状态。
     *
     * @param conversationId 会话全局标识
     * @param ownerKey 不透明会话归属键
     * @param title 用户可见标题
     * @param revision 乐观锁版本
     * @param status 会话生命周期状态
     * @param createdAt 创建时间
     * @param updatedAt 最近更新时间
     */
    public Conversation(String conversationId, String ownerKey, String title,
                        long revision, String status, Date createdAt, Date updatedAt) {
        this.conversationId = conversationId;
        this.ownerKey = ownerKey;
        this.title = title;
        this.revision = revision;
        this.status = status;
        this.createdAt = copyDate(createdAt);
        this.updatedAt = copyDate(updatedAt);
    }

    /** 返回会话全局标识。 */
    public String getConversationId() {
        return conversationId;
    }

    /** 不透明归属键；所有读写都必须校验该字段，具体租户语义由宿主定义。 */
    public String getOwnerKey() {
        return ownerKey;
    }

    /** 返回用户可见的会话标题。 */
    public String getTitle() {
        return title;
    }

    /** 乐观锁版本：每次成功保存 +1；保存时携带 expected revision，不匹配即 409。 */
    public long getRevision() {
        return revision;
    }

    /** 返回会话生命周期状态。 */
    public String getStatus() {
        return status;
    }

    /** 返回会话创建时间。 */
    public Date getCreatedAt() {
        return copyDate(createdAt);
    }

    /** 返回最近一次持久化更新时间。 */
    public Date getUpdatedAt() {
        return copyDate(updatedAt);
    }

    /** Date 是可变类型，领域快照在输入与输出边界都必须复制。 */
    private static Date copyDate(Date value) {
        return value == null ? null : new Date(value.getTime());
    }
}
