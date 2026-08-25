package io.patchbridge.agent.core.audit;

import java.util.Date;

/**
 * 审计调用记录查询条件（Admin Trace 页的过滤模型）。
 *
 * <p>所有字段都可选；分页从 0 开始。
 */
public final class InvocationQuery {

    private String traceId;
    private String userId;
    private String username;
    private AuditInvocationType type;
    private String name;
    private Boolean success;
    private Date from;
    private Date to;
    private int page = 0;
    private int pageSize = 20;

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public AuditInvocationType getType() { return type; }
    public void setType(AuditInvocationType type) { this.type = type; }

    /** 模型名或 Tool 全名（精确匹配）。 */
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean success) { this.success = success; }

    public Date getFrom() { return from; }
    public void setFrom(Date from) { this.from = from; }

    public Date getTo() { return to; }
    public void setTo(Date to) { this.to = to; }

    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }

    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
}
