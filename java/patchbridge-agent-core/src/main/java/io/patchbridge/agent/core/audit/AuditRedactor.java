package io.patchbridge.agent.core.audit;

/**
 * 审计脱敏 SPI。
 *
 * <p>企业数据不能默认把全部 Tool 入参 / 返回值原样落库：手机号、身份证、
 * 凭据等的脱敏规则属于企业数据治理决策，由宿主应用实现本接口接管。
 * 输入是序列化后的摘要字符串，输出是脱敏后字符串。
 */
public interface AuditRedactor {

    String redact(AuditEvent event, String summary);
}
