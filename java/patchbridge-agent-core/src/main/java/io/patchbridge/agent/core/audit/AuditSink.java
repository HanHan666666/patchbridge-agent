package io.patchbridge.agent.core.audit;

/**
 * 审计写入 SPI。
 *
 * <p>框架只负责产生事件，不替企业决定存储与治理策略：
 * 默认实现写 JDBC（Starter 提供），企业可替换为写 Elasticsearch、Kafka
 * 或对接企业审计平台。实现必须线程安全；写入失败不应中断业务调用，
 * 但应记录到应用日志，审计失败不能静默。
 */
public interface AuditSink {

    void write(AuditEvent event);
}
