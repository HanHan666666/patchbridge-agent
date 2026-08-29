# 生产接入准备、身份权限与排障

## 身份、权限与会话归属

### CurrentUserProvider

Spring Security 默认适配规则：

- <code>authentication.name</code> 进入 userId；
- <code>UserDetails.username</code> 或 authentication.name 进入 username；
- 以 <code>ROLE_</code> 开头的 authority 原样进入 roles；
- 其他 authority 进入 permissions；
- 未认证或匿名 Authentication 返回 null，端点返回 <code>401 AUTH_REQUIRED</code>。

Shiro、Sa-Token 或自研认证必须提供自己的 <code>CurrentUserProvider</code>。示例：

~~~java
@Bean
public CurrentUserProvider currentUserProvider(HostIdentityService identities) {
    return request -> {
        HostIdentity identity = identities.requireCurrentIdentity();
        return UserContext.builder()
                .userId(identity.getUserId())
                .username(identity.getDisplayName())
                .tenantId(identity.getTenantId())
                .roles(identity.getRoles())
                .permissions(identity.getPermissions())
                .build();
    };
}
~~~

userId 必须稳定、非空白，并且只能来自服务端可信登录态。

### ToolAccessPolicy

默认策略只要求 userId 非空，不解释 <code>@AiTool.permissions</code>。生产宿主必须明确 ALL、ANY、ABAC 或业务 ACL 语义。例如 ALL：

~~~java
@Bean
public ToolAccessPolicy toolAccessPolicy() {
    return new ToolAccessPolicy() {
        @Override
        public boolean canDiscover(UserContext user, ToolDefinition tool) {
            return canInvoke(user, tool);
        }

        @Override
        public boolean canInvoke(UserContext user, ToolDefinition tool) {
            return user != null
                    && user.getPermissions().containsAll(tool.getPermissions());
        }
    };
}
~~~

Discovery 过滤只减少模型看到的 Tool；每次 <code>/tools/call</code> 仍重新执行 canInvoke。原业务 Service 的事务、方法安全和数据权限仍是最终安全边界。

### 多租户 owner

默认 owner 只是 userId，只适用于 userId 全局唯一的单租户系统。多租户宿主应覆盖：

~~~java
@Bean
public ConversationOwnerResolver conversationOwnerResolver() {
    return user -> user.getTenantId() + ":" + user.getUserId();
}
~~~

实际项目应使用无歧义编码或宿主已有稳定主体 ID，不应简单拼接可含分隔符的原始值。所有会话 SQL 都带 owner key；不存在和跨 owner 访问统一表现为 <code>404 CONVERSATION_NOT_FOUND</code>。

### AdminAccessPolicy

Admin 默认关闭。开启后框架要求宿主映射四种能力：

| 能力 | 用途 |
| --- | --- |
| <code>CONSOLE</code> | 访问 Admin 静态页面 |
| <code>AUDIT_READ</code> | 查询统计和审计 Trace |
| <code>MCP_READ</code> | 查看 MCP Server 和 Tool 状态 |
| <code>MCP_MANAGE</code> | 创建、修改、启停、删除、刷新和测试 MCP |

未声明的 Admin 路由 fail closed。不要只依赖前端菜单隐藏。

---

## 已知限制、安全上线、排障与验证

### 已知限制

- source-only pre-release，API 和模块边界仍可能调整；
- Maven/npm 公共制品未发布，不建议直接用于关键生产系统；
- 当前只有 OpenAI-compatible Chat Provider；
- Widget 是参考实现，不是完整 UI 产品；
- Admin 是基础管理页，不是完整运营平台；
- Global MCP 没有用户/租户/请求级配置、完整 OAuth 或 KMS/Vault 默认 Provider；
- 没有 OpenAPI Adapter、MCP Export、Rate Limit Policy、OpenTelemetry Adapter 或 Boot 3 Adapter；
- Browser Runtime 不支持页面关闭后继续运行或中途恢复；
- Inspector 只读；WebMCP 无 polyfill；Call Trace 只在本地浏览器；
- UI 三个 workspace 的 npm 元数据尚未冻结为公共分发契约；
- MCP missing 映射等当前行为应按第 8、12 节理解。

### 审计的当前失败策略

Audit 默认 enabled，默认 payload-mode=metadata-only。full 模式也先经过 Redactor，默认 Redactor 输出 <code>[REDACTED]</code>。

当前 <code>AuditRecorder</code> 选择可用性优先：审计序列化或 Sink 写入失败会记录服务端错误日志，但不阻断 Model/Tool 业务。高合规环境必须在上线前决定是否接受该行为；如要求 fail closed，需要通过宿主架构或后续明确契约实现，不能假设当前框架已经提供。

### 安全上线检查

- [ ] 模型 base URL、模型名和凭据全部来自外部配置；仓库、日志和 Browser 不包含真实密钥。
- [ ] CurrentUserProvider 只从可信服务端上下文取身份，拒绝 null、空和空白 userId。
- [ ] ToolAccessPolicy 已映射企业 RBAC/ABAC，Discovery 与 Invocation 都有测试。
- [ ] 多租户 owner key 同时包含可信 tenant 和 user，跨租户读取返回 404。
- [ ] Admin 默认关闭；开启时四项 AdminCapability 都有明确策略，未知路径拒绝。
- [ ] Cookie/CSRF/CORS 或 Bearer 请求链复用宿主安全配置。
- [ ] MCP URL 的出站代理、防火墙、DNS 和审批流程已限制 SSRF 风险。
- [ ] MCP 主密钥由 KMS/Vault/环境注入并可轮换，数据库只保存 AES-GCM 密文。
- [ ] Browser Cookie/Authorization 不转发到 MCP；静态 Header 配置经过审批。
- [ ] Audit payload、Redactor、保留期、访问权限和 fail-open 行为符合合规要求。
- [ ] Call Trace localStorage 内容、保留条数和共享浏览器隐私政策已评估。
- [ ] 反向代理未缓冲 SSE，超时覆盖最长模型回答，断开能传播取消。
- [ ] Rate Limit、模型配额、业务 API 限流和告警由宿主/基础设施实现。
- [ ] 已按模型输出、Tool 结果和人工确认时长评估 <code>runtime.limits</code>；理解 Tool 结果超限或运行中 Deadline 不能回滚已发生的业务副作用。

### 常见故障

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 启动提示 model base-url/model 未配置 | 使用默认 Provider 但缺配置 | 注入两个值或提供自定义 ModelProvider |
| 启动提示 context-window-tokens 未配置 | 缺少模型窗口硬配置 | 注入所选模型的真实上下文窗口；框架不按模型名猜测 |
| 启动 WARN <code>PBA-CFG-001</code> | 默认 Provider 未配置 api-key，不会发送 Authorization | 目标网关需要认证时配置 <code>patchbridge-agent.model.api-key</code> |
| 启动 WARN <code>PBA-CFG-002</code> | MCP 开启且默认 properties 源没有任何 Server | 配置 <code>patchbridge-agent.mcp.servers</code> 或显式关闭 MCP |
| 启动 WARN <code>PBA-CFG-003</code> | 仍在使用默认仅认证 ToolAccessPolicy | 注册宿主策略映射 RBAC/ACL，或确认接受默认语义 |
| 启动缺 CurrentUserProvider | 无 Spring Security 默认适配 | 提供宿主 CurrentUserProvider |
| 启动缺 ConversationRepository | 无 DataSource 且无自定义 Repository | 配置 DataSource/schema 或自定义 Repository |
| Admin 开启后启动失败 | 缺 AdminAccessPolicy | 映射宿主权限后再开启 |
| JDBC MCP 启动失败 | 密钥缺失/非标准 Base64/不是 32 字节 | 从密钥系统注入正确 AES-256 key |
| 400 INVALID_ARGUMENT | 未知字段、缺 tools/modelState/arguments 或范围非法 | 按当前严格 DTO 修正请求 |
| 401 AUTH_REQUIRED | 登录态缺失或过期 | 走宿主登录流程，不从 Browser 伪造用户 |
| 403 TOOL_FORBIDDEN | canInvoke 拒绝 | 检查 Tool permissions 与宿主策略 |
| 409 CONVERSATION_CONFLICT | 多 Tab revision 冲突 | 重新加载后由用户决策 |
| 409 MCP_CONFIG_READ_ONLY | properties 模式写配置 | 改部署配置，或明确切换 JDBC |
| MCP test 200 且 ok=false | test 以业务结果返回失败 | 读取 body error，不只看 HTTP 状态 |
| MCP missing 返回 502 | 当前缺独立 404 映射 | 不把它自动当作可重试远端故障 |
| SSE HTTP 200 后出现 error | 流已建立，错误在 data JSON | 消费 type=error，不只看 HTTP 状态 |
| 正常模型响应因 usage 缺失失败 | Provider 未返回上下文窗口计量 | 修复 Provider / 网关返回完整 usage；不能用字符估算替代 |
| 上下文压缩失败 | 摘要、状态投影、取消或会话 revision 冲突 | 保留旧上下文，修复明确错误或重新加载；框架不自动重试或清空状态 |
| MODEL_PROTOCOL_ERROR | 缺 message-stop、Block 未关闭或字段不精确 | 核对 Starter 与 Browser Bundle 版本 |
| AGENT_MAX_MODEL_CALLS / AGENT_MAX_TOOL_CALLS | 本轮将超过模型或 Tool 次数预算 | 检查是否存在无限 Tool Loop；确有业务需要时再调整 runtime.limits |
| AGENT_EXECUTION_TIMEOUT | 模型、Tool 或确认等待超过整轮 Deadline | 定位不合作上游或用户交互时间；不把它当成用户主动取消 |
| MODEL_OUTPUT_LIMIT_EXCEEDED | 单次模型聚合内容超限 | 收紧模型输出或按明确容量调整字符预算；不安排静默截断 |
| TOOL_RESULT_LIMIT_EXCEEDED | Tool 已返回过大文本 | 在业务 Tool 边界就近限制输出；Runtime 不会发布该结果或调用下一次模型，但无法回滚 Tool 已发生的副作用 |
| Bundle 404 | basePath 或资源映射错误 | 使用 <code>{basePath}/assets/...</code>，Admin 仍是 /ai-admin |
| WebMCP unavailable | 浏览器没有 document.modelContext | 隐藏该能力或使用支持的浏览器，不注入 polyfill |
| Duplicate Tool | 同名注册未释放 | dispose 旧 Registration 后再注册 |
| Call Trace persistenceError | localStorage 禁用、配额或数据无效 | 清理当前会话轨迹并检查浏览器策略 |

### 验证命令

Java：

~~~bash
cd java
mvn clean test
mvn clean install
~~~

Browser：

~~~bash
cd web
npm ci
npm run test
npm run build
~~~

四个 Bundle 与 Starter 资源一致性：

~~~bash
cmp web/packages/widget/dist/patchbridge-agent.js   java/patchbridge-agent-spring-boot2-starter/src/main/resources/META-INF/patchbridge-agent/patchbridge-agent.js
cmp web/packages/webmcp-adapter/dist/patchbridge-agent-webmcp-adapter.js   java/patchbridge-agent-spring-boot2-starter/src/main/resources/META-INF/patchbridge-agent/patchbridge-agent-webmcp-adapter.js
cmp web/packages/tool-inspector/dist/patchbridge-agent-tool-inspector.js   java/patchbridge-agent-spring-boot2-starter/src/main/resources/META-INF/patchbridge-agent/patchbridge-agent-tool-inspector.js
cmp web/packages/call-trace/dist/patchbridge-agent-call-trace.js   java/patchbridge-agent-spring-boot2-starter/src/main/resources/META-INF/patchbridge-agent/patchbridge-agent-call-trace.js
~~~

Starter JAR 资源检查：

~~~bash
jar tf java/patchbridge-agent-spring-boot2-starter/target/patchbridge-agent-spring-boot2-starter-0.1.0-SNAPSHOT.jar   | rg 'META-INF/patchbridge-agent(-admin)?/'
~~~

基础 Demo HTTP smoke：

~~~bash
curl --fail --silent --show-error http://127.0.0.1:8080/ >/dev/null
curl --head http://127.0.0.1:8080/ai/assets/patchbridge-agent.js
curl --head http://127.0.0.1:8080/ai-admin/
~~~

这些命令只验证构建、静态资源和基础 HTTP。正式源码发布候选还必须在干净环境完成 Java 8、Browser、四 Bundle、真实模型、真实 MCP、权限、会话、Admin、安全扫描和文档链接验收；不得以历史测试数字代替当前候选复验。
