package io.patchbridge.agent.storage.jdbc;

import io.patchbridge.agent.core.audit.AuditEvent;
import io.patchbridge.agent.core.audit.AuditInvocationType;
import io.patchbridge.agent.core.audit.AuditStats;
import io.patchbridge.agent.core.audit.AuditQueryRepository;
import io.patchbridge.agent.core.audit.InvocationQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AuditQueryRepository 的 JDBC 实现（Admin Trace 页数据源）。
 *
 * <p>动态条件用 NamedParameterJdbcTemplate 组装，避免手拼 SQL；
 * 分页使用 LIMIT ? OFFSET ?（H2 / MySQL 语法一致）。
 */
public class JdbcAuditQueryRepository implements AuditQueryRepository {

    private static final RowMapper<AuditEvent> ROW_MAPPER = new RowMapper<AuditEvent>() {
        @Override
        public AuditEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp startedAt = rs.getTimestamp("started_at");
            Long inputTokens = rs.getObject("input_tokens") == null ? null : rs.getLong("input_tokens");
            Long outputTokens = rs.getObject("output_tokens") == null ? null : rs.getLong("output_tokens");
            return AuditEvent.builder()
                    .invocationId(rs.getString("invocation_id"))
                    .traceId(rs.getString("trace_id"))
                    .conversationId(rs.getString("conversation_id"))
                    .userId(rs.getString("user_id"))
                    .username(rs.getString("username"))
                    .tenantId(rs.getString("tenant_id"))
                    .type(AuditInvocationType.valueOf(rs.getString("type")))
                    .source(rs.getString("source"))
                    .name(rs.getString("name"))
                    .success(rs.getBoolean("success"))
                    .errorCode(rs.getString("error_code"))
                    .errorMessage(rs.getString("error_message"))
                    .startedAt(startedAt == null ? null : new java.util.Date(startedAt.getTime()))
                    .durationMs(rs.getLong("duration_ms"))
                    .inputTokens(inputTokens)
                    .outputTokens(outputTokens)
                    .requestSummary(rs.getString("request_summary"))
                    .responseSummary(rs.getString("response_summary"))
                    .build();
        }
    };

    /**
     * 百分位计算的取样窗口大小：统计页不需要全表精确分位数。
     * 取样按开始时间取最近 N 次调用（二次审计 Q-08）：此前按 duration_ms 升序
     * 截断会把样本偏置成“最快的一万条”，数据量超过上限后 P50/P95 系统性偏低；
     * 时间窗取样对耗时分布无偏，且语义可以在 Admin 页面如实标注。
     */
    static final int PERCENTILE_SAMPLE_LIMIT = 10000;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAuditQueryRepository(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(new JdbcTemplate(dataSource));
    }

    @Override
    public List<AuditEvent> query(InvocationQuery query) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = buildWhere(query, params);
        String sql = "SELECT * FROM agent_invocation " + where
                + " ORDER BY started_at DESC LIMIT :limit OFFSET :offset";
        params.addValue("limit", query.getPageSize());
        params.addValue("offset", query.getPage() * query.getPageSize());
        return jdbc.query(sql, params, ROW_MAPPER);
    }

    @Override
    public long count(InvocationQuery query) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = buildWhere(query, params);
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_invocation " + where, params, Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public List<AuditEvent> findByTrace(String traceId) {
        return jdbc.query(
                "SELECT * FROM agent_invocation WHERE trace_id = :traceId "
                        + "ORDER BY started_at, invocation_id",
                Collections.singletonMap("traceId", traceId), ROW_MAPPER);
    }

    @Override
    public AuditStats stats() {
        MapSqlParameterSource empty = new MapSqlParameterSource();
        long total = countOrZero("SELECT COUNT(*) FROM agent_invocation", empty);
        long model = countOrZero("SELECT COUNT(*) FROM agent_invocation WHERE type = 'MODEL'", empty);
        long tool = countOrZero("SELECT COUNT(*) FROM agent_invocation WHERE type = 'TOOL'", empty);
        long mcp = countOrZero("SELECT COUNT(*) FROM agent_invocation WHERE type = 'MCP_TOOL'", empty);
        long success = countOrZero("SELECT COUNT(*) FROM agent_invocation WHERE success = 1", empty);
        Long inputTokens = jdbc.queryForObject(
                "SELECT SUM(input_tokens) FROM agent_invocation", empty, Long.class);
        Long outputTokens = jdbc.queryForObject(
                "SELECT SUM(output_tokens) FROM agent_invocation", empty, Long.class);

        // 百分位：最近 N 次调用的时间窗取样后内存计算，避免依赖数据库方言的
        // percentile 函数；invocation_id 只作稳定排序的决胜列，不影响窗口选择。
        List<Long> durations = jdbc.queryForList(
                "SELECT duration_ms FROM agent_invocation "
                        + "ORDER BY started_at DESC, invocation_id DESC LIMIT " + PERCENTILE_SAMPLE_LIMIT,
                empty, Long.class);
        List<Long> sorted = new ArrayList<Long>(durations);
        Collections.sort(sorted);

        return new AuditStats(total, model, tool, mcp, success, total - success,
                percentile(sorted, 0.50), percentile(sorted, 0.95),
                inputTokens, outputTokens);
    }

    private String buildWhere(InvocationQuery query, MapSqlParameterSource params) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (notBlank(query.getTraceId())) {
            where.append(" AND trace_id = :traceId");
            params.addValue("traceId", query.getTraceId());
        }
        if (notBlank(query.getUserId())) {
            where.append(" AND user_id = :userId");
            params.addValue("userId", query.getUserId());
        }
        if (notBlank(query.getUsername())) {
            where.append(" AND username = :username");
            params.addValue("username", query.getUsername());
        }
        if (query.getType() != null) {
            where.append(" AND type = :type");
            params.addValue("type", query.getType().name());
        }
        if (notBlank(query.getName())) {
            where.append(" AND name = :name");
            params.addValue("name", query.getName());
        }
        if (query.getSuccess() != null) {
            where.append(" AND success = :success");
            // H2 的 TINYINT 列与 BOOLEAN 参数不可比，统一按整数比较
            params.addValue("success", query.getSuccess() ? 1 : 0);
        }
        if (query.getFrom() != null) {
            where.append(" AND started_at >= :fromTime");
            params.addValue("fromTime", new Timestamp(query.getFrom().getTime()));
        }
        if (query.getTo() != null) {
            where.append(" AND started_at < :toTime");
            params.addValue("toTime", new Timestamp(query.getTo().getTime()));
        }
        return where.toString();
    }

    private long countOrZero(String sql, MapSqlParameterSource params) {
        Long value = jdbc.queryForObject(sql, params, Long.class);
        return value == null ? 0 : value;
    }

    private static long percentile(List<Long> sortedAsc, double p) {
        if (sortedAsc.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(p * sortedAsc.size()) - 1;
        if (index < 0) {
            index = 0;
        }
        return sortedAsc.get(index);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
