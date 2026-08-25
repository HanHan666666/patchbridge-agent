package io.patchbridge.agent.starter.web.dto;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** 创建与保存会话必须共享同一个标题持久化上限。 */
class ConversationRequestValidatorTest {

    /** 两个公开写入口都必须在进入 JDBC 前拒绝超过 Schema 上限的标题。 */
    @Test
    void createAndSaveRejectOversizedTitles() {
        StringBuilder title = new StringBuilder();
        for (int index = 0; index < 257; index += 1) {
            title.append('x');
        }

        ConversationCreateRequest create = new ConversationCreateRequest();
        create.setTitle(title.toString());
        assertThrows(IllegalArgumentException.class, create::validate);

        ConversationSaveRequest save = new ConversationSaveRequest();
        save.setTitle(title.toString());
        save.setRevision(Long.valueOf(0L));
        save.setContext(new LinkedHashMap<String, Object>());
        assertThrows(IllegalArgumentException.class, save::validate);
    }
}
