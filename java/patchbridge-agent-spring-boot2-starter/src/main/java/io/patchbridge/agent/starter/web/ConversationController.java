package io.patchbridge.agent.starter.web;

import io.patchbridge.agent.core.auth.CurrentUserProvider;
import io.patchbridge.agent.core.conversation.Conversation;
import io.patchbridge.agent.core.conversation.ConversationConflictException;
import io.patchbridge.agent.core.conversation.ConversationContext;
import io.patchbridge.agent.core.conversation.ConversationContextValues;
import io.patchbridge.agent.core.conversation.ConversationNotFoundException;
import io.patchbridge.agent.core.conversation.ConversationOwnerResolver;
import io.patchbridge.agent.core.conversation.ConversationRepository;
import io.patchbridge.agent.core.conversation.ConversationSnapshot;
import io.patchbridge.agent.core.user.UserContext;
import io.patchbridge.agent.starter.PatchBridgeAgentProperties;
import io.patchbridge.agent.starter.web.dto.ConversationCreateRequest;
import io.patchbridge.agent.starter.web.dto.ConversationSaveRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Conversation API：持久化跨刷新和设备恢复所需的稳定 Agent 上下文。
 *
 * <p>GET 和 PUT 只接受厂商中立的 {@code context = messages + modelState}。保存使用整回合 全量替换和 revision 乐观锁，多窗口冲突以
 * 409 暴露。ownerKey 始终来自服务端可信身份， 不进入请求或响应。
 */
@RestController
@ConditionalOnProperty(
        prefix = "patchbridge-agent",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequestMapping("${patchbridge-agent.base-path:/ai}")
public class ConversationController {

    /** 会话持久化端口，在存储边界再次执行 ownerKey 隔离。 */
    private final ConversationRepository repository;

    /** 当前登录用户解析入口，身份只取自宿主可信安全上下文。 */
    private final CurrentUserResolver currentUser;

    /** 宿主可替换的归属规则，将 UserContext 映射为不透明 ownerKey。 */
    private final ConversationOwnerResolver ownerResolver;

    /** 单次列表查询上限，避免会话历史无限制加载。 */
    private final int listLimit;

    /**
     * 创建会话接口控制器。
     *
     * @param repository 会话持久化端口
     * @param userProvider 宿主身份适配器
     * @param ownerResolver 会话归属键解析器
     * @param properties 框架配置
     */
    public ConversationController(
            ConversationRepository repository,
            CurrentUserProvider userProvider,
            ConversationOwnerResolver ownerResolver,
            PatchBridgeAgentProperties properties) {
        this.repository = repository;
        this.currentUser = new CurrentUserResolver(userProvider);
        this.ownerResolver = ownerResolver;
        this.listLimit = properties.getConversations().getListLimit();
    }

    /** 查询当前归属主体可见的会话元数据列表，不返回 ModelState。 */
    @GetMapping("/conversations")
    public Map<String, Object> list() {
        UserContext user = currentUser.requiredUser();
        String ownerKey = requiredOwnerKey(user);
        List<Map<String, Object>> conversations = new ArrayList<Map<String, Object>>();
        for (Conversation conversation : repository.listByOwner(ownerKey, listLimit)) {
            conversations.add(conversationView(conversation));
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("conversations", conversations);
        return body;
    }

    /**
     * 在当前归属主体下新建空会话；首轮完整 Context 仍通过 PUT 保存。
     *
     * <p>无请求体表示创建未命名会话；提供请求体时必须是严格契约
     * （仅允许可选 title），未知字段由 DTO 校验统一拒绝。
     */
    @PostMapping("/conversations")
    public Map<String, Object> create(
            @RequestBody(required = false) ConversationCreateRequest request) {
        UserContext user = currentUser.requiredUser();
        String title = null;
        if (request != null) {
            request.validate();
            title = request.getTitle();
        }
        Conversation conversation = repository.create(requiredOwnerKey(user), title);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("conversation", conversationView(conversation));
        return body;
    }

    /** 按 ownerKey 返回 revision、消息和 ModelState 属于同一数据库快照的详情。 */
    @GetMapping("/conversations/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable("id") String id) {
        UserContext user = currentUser.requiredUser();
        ConversationSnapshot snapshot = repository.findSnapshot(requiredOwnerKey(user), id);
        if (snapshot == null) {
            throw new ConversationNotFoundException("会话不存在或当前用户无权访问");
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("conversation", conversationView(snapshot.getConversation()));
        body.put("context", ConversationContextValues.toValue(snapshot.getContext()));
        return ResponseEntity.ok(body);
    }

    /** 使用乐观锁原子保存完整 messages + modelState 上下文。 */
    @PutMapping("/conversations/{id}")
    public ResponseEntity<Map<String, Object>> save(
            @PathVariable("id") String id, @RequestBody ConversationSaveRequest request)
            throws ConversationConflictException {
        UserContext user = currentUser.requiredUser();
        if (request == null) {
            throw new IllegalArgumentException("缺少会话保存请求体");
        }
        request.validate();
        ConversationContext context = ConversationContextValues.fromValue(request.getContext());
        Conversation saved =
                repository.save(
                        requiredOwnerKey(user),
                        id,
                        request.getRevision().longValue(),
                        request.getTitle(),
                        context);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("conversation", conversationView(saved));
        return ResponseEntity.ok(body);
    }

    /** 幂等删除当前归属主体拥有的会话，其他主体的同标识不会受影响。 */
    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) {
        UserContext user = currentUser.requiredUser();
        repository.delete(requiredOwnerKey(user), id);
        return ResponseEntity.noContent().build();
    }

    /** 校验宿主归属规则，避免错误配置退化成共享空归属。 */
    private String requiredOwnerKey(UserContext user) {
        String ownerKey = ownerResolver.resolveOwnerKey(user);
        if (ownerKey == null || ownerKey.trim().isEmpty()) {
            throw new IllegalStateException("ConversationOwnerResolver 必须返回非空 ownerKey");
        }
        return ownerKey;
    }

    /** 对外视图不回传 ownerKey 或 ModelState；它们不属于列表展示元数据。 */
    private Map<String, Object> conversationView(Conversation conversation) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("conversationId", conversation.getConversationId());
        view.put("title", conversation.getTitle());
        view.put("revision", conversation.getRevision());
        view.put("status", conversation.getStatus());
        view.put("createdAt", conversation.getCreatedAt());
        view.put("updatedAt", conversation.getUpdatedAt());
        return view;
    }
}
