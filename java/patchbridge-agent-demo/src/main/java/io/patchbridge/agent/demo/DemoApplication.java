package io.patchbridge.agent.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Demo 入口：模拟一个典型的存量企业系统（Java 8 / Boot 2.7 / Spring Security RBAC / H2），
 * 只引入 patchbridge-agent-spring-boot2-starter 依赖即获得浏览器 Agent 能力与同 JVM
 * 后端单次模型调用门面。
 *
 * <p>Global MCP Server 通过 /ai-admin 动态写入 JDBC，
 * Demo 不内置 Mock 协议服务，用户直接配置真实 MCP endpoint。
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
