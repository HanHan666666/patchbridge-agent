package io.patchbridge.agent.core.model;

/** 某个已开始内容块的模型输出增量。 */
public final class ModelBlockDeltaEvent implements ModelStreamEvent {
    /** 被增量更新的块序号。 */
    private final int index;

    /** 与块类型匹配的增量载荷。 */
    private final Delta delta;

    /** 创建块增量事件。 */
    public ModelBlockDeltaEvent(int index, Delta delta) {
        if (index < 0) {
            throw new IllegalArgumentException("块 index 不可为负数");
        }
        if (delta == null) {
            throw new IllegalArgumentException("块 delta 不可为空");
        }
        this.index = index;
        this.delta = delta;
    }

    /** 返回事件类型。 */
    @Override
    public Type getType() {
        return Type.BLOCK_DELTA;
    }

    /** 返回被更新块序号。 */
    public int getIndex() {
        return index;
    }

    /** 返回类型化增量。 */
    public Delta getDelta() {
        return delta;
    }

    /** 文本、推理或工具参数字符串增量。 */
    public static final class Delta {
        /** 增量对应的块类型。 */
        private final BlockType type;

        /** 文本或推理增量；工具参数增量时为空。 */
        private final String text;

        /** 工具参数 JSON 字符串片段；其他增量时为空。 */
        private final String argumentsDelta;

        /** 创建文本或推理增量。 */
        public static Delta text(BlockType type, String text) {
            if (type != BlockType.TEXT && type != BlockType.REASONING) {
                throw new IllegalArgumentException("文本增量类型只支持 text 或 reasoning");
            }
            if (text == null) {
                throw new IllegalArgumentException("delta.text 不可为空");
            }
            return new Delta(type, text, null);
        }

        /** 创建工具参数字符串增量；完整 JSON 的解析属于 Browser Runtime 聚合阶段。 */
        public static Delta toolCall(String argumentsDelta) {
            if (argumentsDelta == null) {
                throw new IllegalArgumentException("delta.argumentsDelta 不可为空");
            }
            return new Delta(BlockType.TOOL_CALL, null, argumentsDelta);
        }

        /** 保存已经校验的互斥增量字段。 */
        private Delta(BlockType type, String text, String argumentsDelta) {
            this.type = type;
            this.text = text;
            this.argumentsDelta = argumentsDelta;
        }

        /** 返回增量块类型。 */
        public BlockType getType() {
            return type;
        }

        /** 返回文本增量；工具调用时为空。 */
        public String getText() {
            return text;
        }

        /** 返回工具参数增量；文本和推理时为空。 */
        public String getArgumentsDelta() {
            return argumentsDelta;
        }
    }
}
