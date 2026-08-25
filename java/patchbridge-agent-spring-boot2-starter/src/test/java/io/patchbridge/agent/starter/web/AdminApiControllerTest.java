package io.patchbridge.agent.starter.web;

import io.patchbridge.agent.core.audit.AuditEvent;
import io.patchbridge.agent.core.audit.AuditQueryRepository;
import io.patchbridge.agent.core.audit.AuditStats;
import io.patchbridge.agent.core.audit.InvocationQuery;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Admin 审计查询参数测试。
 *
 * <p>日期过滤会直接改变审计检索范围，必须拒绝日期滚动和部分解析，避免管理员输入
 * 与实际查询条件不一致。Repository 使用记录型假实现，不访问数据库。
 */
class AdminApiControllerTest {

    /** 不存在的日历日期与合法日期后的垃圾字符都必须明确失败。 */
    @Test
    void rejectsInvalidOrPartiallyParsedDates() {
        AdminApiController controller = new AdminApiController(new RecordingRepository());

        assertThrows(IllegalArgumentException.class, () -> controller.traces(
                null, null, null, null, null, null,
                "2026-02-30", null, 0, 20));
        assertThrows(IllegalArgumentException.class, () -> controller.traces(
                null, null, null, null, null, null,
                "2026-08-21unexpected", null, 0, 20));
    }

    /** 非法分页与逆序时间范围必须失败，不能被 Controller 静默钳制或转成空查询。 */
    @Test
    void rejectsInvalidPaginationAndReversedRange() {
        AdminApiController controller = new AdminApiController(new RecordingRepository());

        assertThrows(IllegalArgumentException.class, () -> controller.traces(
                null, null, null, null, null, null,
                null, null, -1, 20));
        assertThrows(IllegalArgumentException.class, () -> controller.traces(
                null, null, null, null, null, null,
                null, null, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> controller.traces(
                null, null, null, null, null, null,
                null, null, 0, 101));
        assertThrows(IllegalArgumentException.class, () -> controller.traces(
                null, null, null, null, null, null,
                "2026-08-22", "2026-08-21", 0, 20));
    }

    /** 两种公开格式均应转换为明确的查询边界。 */
    @Test
    void acceptsDocumentedDateFormats() {
        RecordingRepository repository = new RecordingRepository();
        AdminApiController controller = new AdminApiController(repository);

        controller.traces(null, null, null, null, null, null,
                "2026-08-21", "2026-08-21 12:30:45", 0, 20);

        assertNotNull(repository.lastQuery.getFrom());
        assertNotNull(repository.lastQuery.getTo());
    }

    /** 只记录 Controller 交给查询端口的条件，其他返回值保持最小。 */
    private static final class RecordingRepository implements AuditQueryRepository {

        /** 最近一次分页查询条件。 */
        private InvocationQuery lastQuery;

        /** 保存查询条件并返回空页。 */
        @Override
        public List<AuditEvent> query(InvocationQuery query) {
            this.lastQuery = query;
            return Collections.emptyList();
        }

        /** 空页没有匹配记录。 */
        @Override
        public long count(InvocationQuery query) {
            return 0L;
        }

        /** 本测试不需要 Trace 详情。 */
        @Override
        public List<AuditEvent> findByTrace(String traceId) {
            return Collections.emptyList();
        }

        /** 本测试不需要统计数据。 */
        @Override
        public AuditStats stats() {
            return new AuditStats(0L, 0L, 0L, 0L,
                    0L, 0L, 0L, 0L, null, null);
        }
    }
}
