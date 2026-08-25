package io.patchbridge.agent.core.audit;

import java.util.List;

/**
 * 审计查询 SPI（Admin 读模型）。
 *
 * <p>与 AuditSink 是同一份数据的两个面：Sink 负责写、本接口负责查。
 * 后台只用于查询历史事实，不负责恢复或推进 Agent（审计与 Runtime 严格分离）。
 * 查询结果复用 {@link AuditEvent} 作为行结构。
 */
public interface AuditQueryRepository {

    /** 分页 + 过滤查询调用记录，按时间倒序。返回当前页数据。 */
    List<AuditEvent> query(InvocationQuery query);

    /** 满足过滤条件的总行数（用于分页）。 */
    long count(InvocationQuery query);

    /** 单条 trace 的完整调用序列（按开始时间升序），用于还原调用链路。 */
    List<AuditEvent> findByTrace(String traceId);

    /** 全量统计（Admin 总览）。 */
    AuditStats stats();
}
