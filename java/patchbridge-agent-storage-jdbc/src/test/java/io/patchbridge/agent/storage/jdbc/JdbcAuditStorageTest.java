package io.patchbridge.agent.storage.jdbc;

import io.patchbridge.agent.core.audit.AuditEvent;
import io.patchbridge.agent.core.audit.AuditInvocationType;
import io.patchbridge.agent.core.audit.AuditStats;
import io.patchbridge.agent.core.audit.AuditQueryRepository;
import io.patchbridge.agent.core.audit.InvocationQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.SQLException;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 覆盖：写入 → trace 按调用开始时间还原 → 过滤分页 → 统计聚合。
 */
class JdbcAuditStorageTest {

    private JdbcAuditSink sink;
    private AuditQueryRepository queryRepository;

    @BeforeEach
    void setUp() throws SQLException {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        // DB_CLOSE_DELAY=-1：大数据量用例的分配压力可能回收 setup 里的连接引用，
        // 命名内存库必须跨连接存续到测试结束（与 MCP 配置源测试一致）。
        ds.setURL("jdbc:h2:mem:auditest" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ScriptUtils.executeSqlScript(ds.getConnection(), new ClassPathResource("agent-schema-h2.sql"));
        sink = new JdbcAuditSink(ds);
        queryRepository = new JdbcAuditQueryRepository(ds);
    }

    private AuditEvent event(String traceId, int seq, AuditInvocationType type,
                             String name, boolean success, long durationMs) {
        return AuditEvent.builder()
                .invocationId("inv-" + traceId + "-" + seq)
                .traceId(traceId).conversationId("c1")
                .userId("u1").username("zhangsan").tenantId("t1")
                .type(type).source(type == AuditInvocationType.MODEL ? "MODEL" : "MCP")
                .name(name).success(success).startedAt(new Date(seq * 1000L)).durationMs(durationMs)
                .requestSummary("...").responseSummary("...")
                .build();
    }

    @Test
    void traceOrderedBySequence() {
        sink.write(event("t1", 0, AuditInvocationType.MODEL, "deepseek-chat", true, 800));
        sink.write(event("t1", 1, AuditInvocationType.TOOL, "local.device_get", true, 30));
        sink.write(event("t1", 2, AuditInvocationType.MODEL, "deepseek-chat", true, 500));
        sink.write(event("t2", 0, AuditInvocationType.MCP_TOOL, "mcp.inv.query", false, 120));

        List<AuditEvent> trace = queryRepository.findByTrace("t1");
        assertEquals(3, trace.size());
        assertEquals("local.device_get", trace.get(1).getName(), "开始时间顺序还原调用链路");

        AuditStats stats = queryRepository.stats();
        assertEquals(4, stats.getTotalInvocations());
        assertEquals(2, stats.getModelCalls());
        assertEquals(1, stats.getToolCalls());
        assertEquals(1, stats.getMcpToolCalls());
        assertEquals(3, stats.getSuccessCount());
        assertEquals(1, stats.getFailedCount());
    }

    /**
     * 超过取样上限后，百分位必须基于“最近一万次”时间窗而不是最快的一万次
     * （二次审计 Q-08 回归）：600 次新慢调用占比约 5.7%，足以抬高 P95。
     */
    @Test
    void percentileSamplesMostRecentWindowBeyondLimit() {
        for (int i = 0; i < JdbcAuditQueryRepository.PERCENTILE_SAMPLE_LIMIT; i++) {
            sink.write(event("bulk", i, AuditInvocationType.TOOL, "local.device_get", true, 10));
        }
        for (int i = 0; i < 600; i++) {
            sink.write(event("recent", JdbcAuditQueryRepository.PERCENTILE_SAMPLE_LIMIT + i,
                    AuditInvocationType.MODEL, "deepseek-chat", true, 9_000));
        }

        AuditStats stats = queryRepository.stats();
        assertEquals(JdbcAuditQueryRepository.PERCENTILE_SAMPLE_LIMIT + 600, stats.getTotalInvocations());
        assertEquals(10, stats.getP50DurationMs(), "窗口内中位数仍由快调用主导");
        assertEquals(9_000, stats.getP95DurationMs(), "P95 必须覆盖最近窗口内的新慢调用");
    }

    @Test
    void filterAndPagination() {
        for (int i = 0; i < 5; i++) {
            sink.write(event("t" + i, 0, AuditInvocationType.TOOL, "local.device_get", true, 10));
        }
        InvocationQuery query = new InvocationQuery();
        query.setType(AuditInvocationType.TOOL);
        query.setName("local.device_get");
        query.setPage(1);
        query.setPageSize(2);
        assertEquals(2, queryRepository.query(query).size());
        assertEquals(5, queryRepository.count(query));
    }
}
