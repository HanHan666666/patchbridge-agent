package io.patchbridge.agent.starter.web;

import io.patchbridge.agent.core.audit.AuditEvent;
import io.patchbridge.agent.core.audit.AuditInvocationType;
import io.patchbridge.agent.core.audit.AuditQueryRepository;
import io.patchbridge.agent.core.audit.AuditStats;
import io.patchbridge.agent.core.audit.InvocationQuery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Admin 审计查询 API（Trace 列表 / 详情 / 统计）。
 *
 * <p>权限由 AdminAuthorizationInterceptor 统一调用宿主 AdminAccessPolicy 校验，
 * 本控制器只负责查询。后台只读历史事实，不负责恢复或推进 Agent。
 */
@RestController
@ConditionalOnExpression("${patchbridge-agent.enabled:true}"
        + " and ${patchbridge-agent.admin.enabled:false}")
@ConditionalOnBean(AdminAuthorizationInterceptor.class)
@RequestMapping("${patchbridge-agent.base-path:/ai}")
public class AdminApiController {

    /** Admin 读模型端口；查询路径不依赖审计写入实现。 */
    private final AuditQueryRepository queryRepository;

    /**
     * 创建只读审计查询入口。
     *
     * @param queryRepository 宿主或 Starter 提供的审计查询端口
     */
    public AdminApiController(AuditQueryRepository queryRepository) {
        this.queryRepository = queryRepository;
    }

    /** 返回全量审计统计；权限在进入 Controller 前已经统一校验。 */
    @GetMapping("/admin/stats")
    public AuditStats stats() {
        return queryRepository.stats();
    }

    /**
     * 按稳定过滤条件分页查询审计事件。
     *
     * <p>分页和时间范围不做静默钳制：非法值直接返回参数错误，确保管理员看到的
     * 查询条件与真正交给 Repository 的条件完全一致。
     */
    @GetMapping("/admin/traces")
    public Map<String, Object> traces(
            @RequestParam(value = "traceId", required = false) String traceId,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "username", required = false) String username,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "success", required = false) Boolean success,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        Date parsedFrom = parseDate(from);
        Date parsedTo = parseDate(to);
        validateRange(parsedFrom, parsedTo);
        validatePagination(page, pageSize);

        InvocationQuery query = new InvocationQuery();
        query.setTraceId(traceId);
        query.setUserId(userId);
        query.setUsername(username);
        query.setType(parseType(type));
        query.setName(name);
        query.setSuccess(success);
        query.setFrom(parsedFrom);
        query.setTo(parsedTo);
        query.setPage(page);
        query.setPageSize(pageSize);

        List<AuditEvent> items = queryRepository.query(query);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("items", items);
        body.put("total", queryRepository.count(query));
        body.put("page", query.getPage());
        body.put("pageSize", query.getPageSize());
        return body;
    }

    /** 返回一个 traceId 下按时间排序的完整调用序列。 */
    @GetMapping("/admin/traces/{traceId}")
    public Map<String, Object> trace(@PathVariable("traceId") String traceId) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("traceId", traceId);
        body.put("items", queryRepository.findByTrace(traceId));
        return body;
    }

    /** 将可选调用类型按与系统 Locale 无关的规则转换为领域枚举。 */
    private static AuditInvocationType parseType(String type) {
        if (type == null || type.isEmpty()) {
            return null;
        }
        return AuditInvocationType.valueOf(type.toUpperCase(Locale.ROOT));
    }

    /** 时间下界不得晚于上界，避免执行与输入含义相反的空查询。 */
    private static void validateRange(Date from, Date to) {
        if (from != null && to != null && from.after(to)) {
            throw new IllegalArgumentException("from 不得晚于 to");
        }
    }

    /** 分页边界是公开契约，非法值必须失败而不是静默修改。 */
    private static void validatePagination(int page, int pageSize) {
        if (page < 0) {
            throw new IllegalArgumentException("page 必须是非负整数");
        }
        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("pageSize 必须介于 1 与 100 之间");
        }
    }

    /**
     * 解析 Admin 日期过滤条件。
     *
     * <p>解析必须关闭日期滚动并完整消费输入，避免将不存在的日期或带垃圾后缀的文本
     * 静默转换成另一时间范围，导致审计查询结果与管理员输入不一致。
     */
    private static Date parseDate(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        for (String pattern : new String[]{"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd"}) {
            SimpleDateFormat format = new SimpleDateFormat(pattern);
            format.setLenient(false);
            ParsePosition position = new ParsePosition(0);
            Date parsed = format.parse(value, position);
            if (parsed != null && position.getIndex() == value.length()) {
                return parsed;
            }
        }
        throw new IllegalArgumentException(
                "日期格式应为 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss: " + value);
    }
}
