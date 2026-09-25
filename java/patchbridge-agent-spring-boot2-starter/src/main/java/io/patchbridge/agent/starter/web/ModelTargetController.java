package io.patchbridge.agent.starter.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import io.patchbridge.agent.core.auth.CurrentUserProvider;
import io.patchbridge.agent.core.conversation.*;
import io.patchbridge.agent.core.model.ModelToolDefinition;
import io.patchbridge.agent.core.model.target.*;
import io.patchbridge.agent.core.user.UserContext;
import io.patchbridge.agent.starter.web.dto.ModelStreamEnvelope;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 模型目录与显式切换 HTTP 入口；不提供配置写入和管理后台。 */
@RestController
@RequestMapping("${patchbridge-agent.base-path:/ai}")
@ConditionalOnProperty(
        prefix = "patchbridge-agent",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ModelTargetController {
    /** 唯一目录和权限边界。 */
    private final ModelProviderRouter router;

    /** 草稿与保存会话共用的切换应用服务。 */
    private final ModelConversationService conversations;

    /** 身份只从宿主登录态解析。 */
    private final CurrentUserResolver currentUser;

    /** 装配 HTTP Adapter，不持有会话或执行状态。 */
    public ModelTargetController(
            ModelProviderRouter router,
            ModelConversationService conversations,
            CurrentUserProvider users) {
        this.router = router;
        this.conversations = conversations;
        this.currentUser = new CurrentUserResolver(users);
    }

    /** 只公开有权限目标及窗口参数，不序列化地址、凭据或 Adapter 对象。 */
    @GetMapping("/model/targets")
    public Map<String, Object> catalog() {
        ModelAccessContext access = ModelAccessContext.authenticated(currentUser.requiredUser());
        List<Object> targets = new ArrayList<Object>();
        for (ResolvedModelTarget target : router.available(access)) targets.add(target.toValue());
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("targets", targets);
        ModelTargetRef ref = router.defaultTarget(access);
        body.put("defaultTarget", ref == null ? null : ref.toValue());
        return body;
    }

    /** 本地草稿切换只返回完整候选，浏览器在请求成功后才替换旧状态。 */
    @PostMapping("/model/handoff")
    public Map<String, Object> draft(@RequestBody HandoffRequest request) {
        UserContext user = currentUser.requiredUser();
        if (request == null) throw new IllegalArgumentException("缺少模型切换请求体");
        request.validate(false);
        ConversationContext next =
                conversations.switchDraft(
                        user,
                        ConversationContextValues.fromValue(request.context),
                        ModelTargetRef.fromValue(request.target),
                        request.tools());
        return Collections.<String, Object>singletonMap(
                "context", ConversationContextValues.toValue(next));
    }

    /** 读取服务器原历史，在一个 revision 下保存新目标与重估上下文。 */
    @PostMapping("/conversations/{id}/model-target")
    public Map<String, Object> saved(@PathVariable String id, @RequestBody HandoffRequest request)
            throws ConversationConflictException {
        UserContext user = currentUser.requiredUser();
        if (request == null) throw new IllegalArgumentException("缺少模型切换请求体");
        request.validate(true);
        ConversationSnapshot next =
                conversations.switchTarget(
                        user,
                        id,
                        request.revision,
                        ModelTargetRef.fromValue(request.target),
                        request.tools());
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("conversation", ConversationController.conversationView(next.getConversation()));
        body.put("context", ConversationContextValues.toValue(next.getContext()));
        return body;
    }

    /** 两种切换只共享字段解析；字段组合必须匹配草稿或持久化语义。 */
    public static final class HandoffRequest {
        /** 公开目标引用。 */
        private Map<String, Object> target;

        /** 仅草稿携带真实完整上下文。 */
        private Map<String, Object> context;

        /** 仅持久化场景携带预期 revision。 */
        private Long revision;

        /** 当前工具目录用于能力预检。 */
        private List<ModelStreamEnvelope.Tool> tools;

        /** 明确拒绝未知字段。 */
        private final Map<String, Object> unknown = new LinkedHashMap<String, Object>();

        /** 绑定目标。 */
        public void setTarget(Map<String, Object> value) {
            target = value;
        }

        /** 绑定草稿。 */
        public void setContext(Map<String, Object> value) {
            context = value;
        }

        /** 绑定乐观锁。 */
        public void setRevision(Long value) {
            revision = value;
        }

        /** 绑定目录。 */
        public void setTools(List<ModelStreamEnvelope.Tool> value) {
            tools = value;
        }

        /** 收集意外字段，不能由全局宽松 Jackson 吞掉。 */
        @JsonAnySetter
        public void unknown(String key, Object value) {
            unknown.put(key, value);
        }

        /** 校验两种入口互斥的必要字段。 */
        void validate(boolean saved) {
            if (!unknown.isEmpty()
                    || target == null
                    || tools == null
                    || (saved
                            ? context != null || revision == null || revision < 0
                            : context == null || revision != null)) {
                throw new IllegalArgumentException("模型切换请求字段不完整或不匹配");
            }
        }

        /** 工具定义沿用模型流的严格解析，不复制另一套 Schema 规则。 */
        List<ModelToolDefinition> tools() {
            List<ModelToolDefinition> result = new ArrayList<ModelToolDefinition>();
            for (ModelStreamEnvelope.Tool tool : tools) {
                if (tool == null) throw new IllegalArgumentException("tools 不能包含 null");
                result.add(tool.toDomain("tools"));
            }
            return result;
        }
    }
}
