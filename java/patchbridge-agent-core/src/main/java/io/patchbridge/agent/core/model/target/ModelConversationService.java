package io.patchbridge.agent.core.model.target;

import io.patchbridge.agent.core.conversation.*;
import io.patchbridge.agent.core.model.ModelToolDefinition;
import io.patchbridge.agent.core.user.UserContext;

import java.util.List;

/** 会话目标一致性的应用入口；普通保存不能绕过切换，HTTP 调用不能脱离已保存目标。 */
public final class ModelConversationService {
    /** 持久化同时约束 owner 和 revision。 */
    private final ConversationRepository repository;

    /** 宿主身份到持久化归属的唯一映射。 */
    private final ConversationOwnerResolver owners;

    /** 唯一目标授权和配置边界。 */
    private final ModelProviderRouter router;

    /** 草稿和已保存会话共用切换算法。 */
    private final ModelHandoffService handoff;

    /** 不持有会话内存，只在调用期间读取快照。 */
    public ModelConversationService(
            ConversationRepository repository,
            ConversationOwnerResolver owners,
            ModelProviderRouter router) {
        this.repository = repository;
        this.owners = owners;
        this.router = router;
        this.handoff = new ModelHandoffService(router);
    }

    /** 新会话原子保存首轮稳定结果与用户实际选择的目标。 */
    public Conversation create(UserContext user, String title, ConversationContext context) {
        router.resolve(context.getModelTarget(), ModelAccessContext.authenticated(user));
        return repository.create(owner(user), title, context);
    }

    /** 普通保存只能继续原目标，目标变更必须显式 handoff。 */
    public Conversation save(
            UserContext user, String id, long revision, String title, ConversationContext context)
            throws ConversationConflictException {
        ConversationSnapshot source = snapshot(user, id);
        if (!source.getContext().getModelTarget().equals(context.getModelTarget())) {
            throw new ModelTargetException("MODEL_TARGET_MISMATCH", "普通保存不能更改会话模型目标");
        }
        return repository.save(owner(user), id, revision, title, context);
    }

    /** 模型与压缩 HTTP 入口在发起调用前核对已持久化目标；草稿只校验目录。 */
    public void requireCurrent(UserContext user, String id, ModelTargetRef ref) {
        ModelAccessContext access = ModelAccessContext.authenticated(user);
        if (id != null && !snapshot(user, id).getContext().getModelTarget().equals(ref)) {
            throw new ModelTargetException("MODEL_TARGET_MISMATCH", "请求目标与会话当前目标不同");
        }
        router.resolve(ref, access);
    }

    /** 切换已保存会话时读取服务端历史，拒绝客户端用另一份历史覆盖原会话。 */
    public ConversationSnapshot switchTarget(
            UserContext user,
            String id,
            long revision,
            ModelTargetRef target,
            List<ModelToolDefinition> tools)
            throws ConversationConflictException {
        ConversationSnapshot source = snapshot(user, id);
        if (source.getConversation().getRevision() != revision)
            throw new ConversationConflictException(
                    "会话已更新", source.getConversation().getRevision());
        ConversationContext next =
                handoff.prepare(
                        source.getContext(), target, tools, ModelAccessContext.authenticated(user));
        Conversation saved =
                repository.save(
                        owner(user), id, revision, source.getConversation().getTitle(), next);
        return new ConversationSnapshot(saved, next);
    }

    /** 本地草稿复用同一校验规则，服务端不保存草稿或执行状态。 */
    public ConversationContext switchDraft(
            UserContext user,
            ConversationContext source,
            ModelTargetRef target,
            List<ModelToolDefinition> tools) {
        return handoff.prepare(source, target, tools, ModelAccessContext.authenticated(user));
    }

    /** 会话不存在与越权统一处理，禁止泄漏归属信息。 */
    private ConversationSnapshot snapshot(UserContext user, String id) {
        ConversationSnapshot value = repository.findSnapshot(owner(user), id);
        if (value == null) throw new ConversationNotFoundException("会话不存在或当前用户无权访问");
        return value;
    }

    /** 宿主 owner 规则必须返回明确身份，不使用空归属默认值。 */
    private String owner(UserContext user) {
        String owner = owners.resolveOwnerKey(user);
        if (owner == null || owner.trim().isEmpty())
            throw new IllegalStateException("会话 owner 不可为空");
        return owner;
    }
}
