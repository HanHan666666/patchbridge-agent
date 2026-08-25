package io.patchbridge.agent.core.tool;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ToolCallResult 不可变契约测试：构造后修改来源列表不影响结果对象，
 * 暴露的列表不可修改（二次审计 Q-05 的回归防线）。
 */
class ToolCallResultTest {

    @Test
    void constructorCopiesSourceList() {
        List<ToolContent> source = new ArrayList<ToolContent>();
        source.add(ToolContent.text("first"));

        ToolCallResult result = new ToolCallResult(source, false);
        source.add(ToolContent.text("second"));

        assertEquals(1, result.getContent().size());
        assertEquals("first", result.getContent().get(0).getText());
    }

    @Test
    void exposedListIsUnmodifiable() {
        ToolCallResult result = ToolCallResult.ofText("ok");
        assertThrows(UnsupportedOperationException.class,
                () -> result.getContent().add(ToolContent.text("more")));
    }

    @Test
    void nullContentFailsFast() {
        assertThrows(NullPointerException.class, () -> new ToolCallResult(null, false));
    }
}
