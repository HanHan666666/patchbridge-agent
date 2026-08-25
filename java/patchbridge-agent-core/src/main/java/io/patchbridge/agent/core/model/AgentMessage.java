package io.patchbridge.agent.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 厂商中立的 Agent 稳定消息。
 *
 * <p>消息只拥有身份、角色和有序内容块。Provider 可以根据目标协议展开消息，但不能 把厂商字段反向写进该模型；展示消息也不能代替 {@link ModelState}。
 */
public final class AgentMessage {
    /** 跨模型调用保持稳定的消息标识。 */
    private final String id;

    /** 消息所有者角色。 */
    private final MessageRole role;

    /** 按模型生成顺序排列的不可变内容块。 */
    private final List<ContentBlock> blocks;

    /** 创建不可变稳定消息。 */
    public AgentMessage(String id, MessageRole role, List<ContentBlock> blocks) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("消息 id 不可为空");
        }
        if (role == null) {
            throw new IllegalArgumentException("消息 role 不可为空");
        }
        if (blocks == null || blocks.isEmpty()) {
            throw new IllegalArgumentException("消息 blocks 不可为空");
        }
        for (ContentBlock block : blocks) {
            validateRoleBlock(role, block);
        }
        this.id = id;
        this.role = role;
        this.blocks = Collections.unmodifiableList(new ArrayList<ContentBlock>(blocks));
    }

    /** 返回稳定消息标识。 */
    public String getId() {
        return id;
    }

    /** 返回领域角色。 */
    public MessageRole getRole() {
        return role;
    }

    /** 返回有序不可变内容块。 */
    public List<ContentBlock> getBlocks() {
        return blocks;
    }

    /**
     * 保证领域角色只能拥有明确合法的块类型。
     *
     * <p>该不变量放在 Core 构造器而不是某个 HTTP DTO 中，使会话恢复、测试代码和宿主 自定义 Adapter 都无法绕过同一规则。
     */
    private static void validateRoleBlock(MessageRole role, ContentBlock block) {
        if (block == null) {
            throw new IllegalArgumentException("消息 blocks 不允许包含 null");
        }
        boolean valid =
                (role == MessageRole.SYSTEM && block instanceof TextBlock)
                        || (role == MessageRole.USER
                                && (block instanceof TextBlock || block instanceof ImageBlock))
                        || (role == MessageRole.ASSISTANT
                                && (block instanceof TextBlock
                                        || block instanceof ReasoningBlock
                                        || block instanceof ToolCallBlock))
                        || (role == MessageRole.TOOL && block instanceof ToolResultBlock);
        if (!valid) {
            throw new IllegalArgumentException(
                    "消息 role="
                            + role.getWireValue()
                            + " 不允许 block.type="
                            + block.getType().getWireValue());
        }
    }
}
