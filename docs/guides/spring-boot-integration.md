# 接入 Spring Boot 2 Starter

## Spring Boot 2 Starter 最小接入

### 引入本地 SNAPSHOT

~~~xml
<dependency>
    <groupId>io.patchbridge.agent</groupId>
    <artifactId>patchbridge-agent-spring-boot2-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
~~~

Starter 基于 Spring Boot <code>2.7.18</code> BOM 和 Java 8，通过 <code>META-INF/spring.factories</code> 注册自动配置。

### 准备数据库

Starter 自带：

- [H2 schema](../../java/patchbridge-agent-storage-jdbc/src/main/resources/agent-schema-h2.sql)
- [MySQL schema](../../java/patchbridge-agent-storage-jdbc/src/main/resources/agent-schema-mysql.sql)

示例 H2 配置：

~~~yaml
spring:
  datasource:
    url: jdbc:h2:file:./data/agent-db;AUTO_SERVER=TRUE
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
  sql:
    init:
      mode: always
      schema-locations: classpath:agent-schema-h2.sql
~~~

生产数据库的账号、密码、网络和 schema 变更由宿主管理。框架不提供旧表自动迁移、双写或内存 Repository fallback。

### 配置默认模型 Provider

~~~yaml
patchbridge-agent:
  model:
    base-url: ${PATCHBRIDGE_AGENT_MODEL_BASE_URL}
    model: ${PATCHBRIDGE_AGENT_MODEL}
    api-key: ${PATCHBRIDGE_AGENT_MODEL_API_KEY:}
    context-window-tokens: ${PATCHBRIDGE_AGENT_MODEL_CONTEXT_WINDOW_TOKENS}
~~~

环境变量示例：

~~~bash
export PATCHBRIDGE_AGENT_MODEL_BASE_URL='https://model-gateway.example/v1'
export PATCHBRIDGE_AGENT_MODEL='model-name-placeholder'
export PATCHBRIDGE_AGENT_MODEL_API_KEY='<model-api-key-placeholder>'
export PATCHBRIDGE_AGENT_MODEL_CONTEXT_WINDOW_TOKENS='128000'
~~~

如果网关不要求认证，api-key 可以为空。默认 Provider 要求 base-url 和 model 在启动时非空。
`context-window-tokens` 无论默认还是自定义 Provider 都必须明确配置，并与真实模型窗口一致；
框架固定在 80% 自动压缩。提供自定义 <code>ModelProvider</code> Bean 时，默认 Provider 退让，
但自定义实现还必须满足正常 usage 和 `ModelStateProjector` 压缩投影契约。详见
[《使用上下文压缩》](context-compaction.md)。

### 满足身份和会话硬依赖

完整默认装配需要：

- 一个 <code>CurrentUserProvider</code>；classpath 中存在 Spring Security 且宿主未覆盖时，Starter 提供默认适配；
- 一个 <code>ConversationRepository</code>；存在 <code>DataSource</code> 时默认使用 JDBC；
- 默认模型配置或自定义 <code>ModelProvider</code>；
- Admin 开启时额外提供 <code>AdminAccessPolicy</code>。

缺少这些依赖时不会创建匿名、内存或 allow-all 替代实现。

### 默认 Bean 与替换边界

| Port/Bean | 默认实现 | 条件与替换方式 |
| --- | --- | --- |
| <code>ToolSchemaGenerator</code> | <code>SimpleReflectionSchemaGenerator</code> | 同类型 Bean 替换 |
| <code>ToolNamingStrategy</code> | <code>DefaultToolNamingStrategy</code> | 同类型 Bean 替换 |
| <code>ToolAccessPolicy</code> | <code>AuthenticatedToolAccessPolicy</code> | 仅检查登录；生产应按权限替换 |
| <code>CurrentUserProvider</code> | <code>SecurityContextCurrentUserProvider</code> | 仅 Spring Security 存在时 |
| <code>ConversationOwnerResolver</code> | <code>UserIdConversationOwnerResolver</code> | 多租户必须替换 |
| <code>ModelProvider</code> | <code>OpenAiCompatibleModelProvider</code> | 同类型 Bean 替换 |
| <code>ModelStateProjector</code> | 当前默认 <code>ModelProvider</code> | 自定义 Provider 必须同时提供投影语义 |
| <code>ContextCompactionProvider</code> | <code>DefaultContextCompactionProvider</code> | 使用当前 ModelGateway；同类型 Bean 可完整替换 |
| <code>ConversationRepository</code> | <code>JdbcConversationRepository</code> | 需要 DataSource |
| <code>AuditSink</code> | <code>JdbcAuditSink</code> | DataSource + audit enabled |
| <code>AuditQueryRepository</code> | <code>JdbcAuditQueryRepository</code> | 需要 DataSource |
| <code>AuditRedactor</code> | 输出 <code>[REDACTED]</code> | 同类型 Bean 替换 |
| <code>RemoteMcpClient</code> | <code>StreamableHttpMcpClient</code> | MCP enabled |
| <code>McpConfigurationStore</code> | properties 或 JDBC Store | 根据 source；同类型 Bean 可替换 |
| <code>McpCredentialCipher</code> | <code>AesGcmMcpCredentialCipher</code> | 默认 JDBC Store 使用 |
| <code>ToolRegistry</code> | <code>DefaultToolRegistry</code> | 聚合所有 ToolProvider |
| <code>ToolInvocationPipeline</code> | 默认 Tool 管线 | 聚合有序 ToolCallInterceptor |
| <code>ModelInvocationPipeline</code> | 默认 Model 管线 | 聚合有序 ModelCallInterceptor |

<code>ToolProvider</code>、<code>ToolCallInterceptor</code> 和 <code>ModelCallInterceptor</code> 是可追加扩展点。静态资源 Configurer 的替换边界是固定 Bean 名 <code>patchbridgeAgentResourceConfigurer</code>，不是任意 <code>WebMvcConfigurer</code> 类型。

### 最小页面入口

默认 <code>base-path=/ai</code> 时：

~~~html
<script src="/ai/assets/patchbridge-agent.js"></script>
<patchbridge-agent
  endpoint="/ai"
  title="AI 助手"
  login-url="/login"
  style="display:block;height:720px">
</patchbridge-agent>
~~~
