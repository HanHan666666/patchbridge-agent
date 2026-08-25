package io.patchbridge.agent.core.model;

/** 开始一个有稳定序号的模型输出内容块。 */
public final class ModelBlockStartEvent implements ModelStreamEvent {
    /** 本条 assistant 消息内从零递增的块序号。 */
    private final int index;

    /** 块描述；工具调用开始时同时携带 callId 和 name。 */
    private final Block block;

    /** 创建块开始事件。 */
    public ModelBlockStartEvent(int index, Block block) {
        if (index < 0) {
            throw new IllegalArgumentException("块 index 不可为负数");
        }
        if (block == null) {
            throw new IllegalArgumentException("块描述不可为空");
        }
        this.index = index;
        this.block = block;
    }

    /** 返回事件类型。 */
    @Override
    public Type getType() {
        return Type.BLOCK_START;
    }

    /** 返回消息内块序号。 */
    public int getIndex() {
        return index;
    }

    /** 返回块开始描述。 */
    public Block getBlock() {
        return block;
    }

    /** 块开始的最小稳定描述。 */
    public static final class Block {
        /** 当前内容块类型。 */
        private final BlockType type;

        /** 工具调用标识，仅 tool-call 有效。 */
        private final String callId;

        /** 工具名称，仅 tool-call 有效。 */
        private final String name;

        /** 创建普通文本或推理块描述。 */
        public static Block content(BlockType type) {
            if (type != BlockType.TEXT && type != BlockType.REASONING) {
                throw new IllegalArgumentException("流式普通块只支持 text 或 reasoning");
            }
            return new Block(type, null, null);
        }

        /** 创建已知调用身份的工具块描述。 */
        public static Block toolCall(String callId, String name) {
            requireText(callId, "tool-call start.callId 不可为空");
            requireText(name, "tool-call start.name 不可为空");
            return new Block(BlockType.TOOL_CALL, callId, name);
        }

        /** 保存已经校验的块描述。 */
        private Block(BlockType type, String callId, String name) {
            this.type = type;
            this.callId = callId;
            this.name = name;
        }

        /** 返回块类型。 */
        public BlockType getType() {
            return type;
        }

        /** 返回工具调用标识；非工具块时为空。 */
        public String getCallId() {
            return callId;
        }

        /** 返回工具名称；非工具块时为空。 */
        public String getName() {
            return name;
        }

        /** 校验工具调用必填文本。 */
        private static void requireText(String value, String message) {
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException(message);
            }
        }
    }
}
