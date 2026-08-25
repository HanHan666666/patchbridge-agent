# PatchBridge Agent v0.1 版本说明

- 版本状态：Pre-release 源码候选准备阶段
- Java 版本：`0.1.0-SNAPSHOT`
- Web workspace 版本：`0.1.0`
- 发布方式：源码构建与本地依赖，不发布 Maven Central 或 npm 公共包
- 适用范围：Java 8、Spring Boot 2.7、已有认证/RBAC/业务 Service/数据库的企业 Web 应用

## 1. 版本定位

PatchBridge Agent v0.1 用于给存量 Java/Spring Boot 企业 Web 应用增加轻量 Agent 能力。它不是通用 Agent 平台，也不接管宿主系统的身份、租户、业务权限、数据治理或部署安全。

稳定分工是：

- Browser 运行有界 Agent Loop、状态机、Tool 调度和 Human-in-the-loop；
- Server 运行身份解析、权限复核、业务 Service、Conversation、Audit、Model Gateway 和 MCP Gateway；
- LLM 负责推理，但不能成为授权边界；
- 后端不保存跨请求的 Agent Execution、等待确认对象或运行到一半的 Runtime 状态。

完整架构边界见[《架构设计：模块化单体、六边形架构与设计模式》](../../architecture/overview.md)与[《ADR-001：厂商中立 Agent Runtime 核心契约》](../../architecture/adr/0001-provider-neutral-runtime.md)。

## 2. 交付形态与环境

### 2.1 Java

- Maven groupId：`io.patchbridge.agent`；
- 父版本：`0.1.0-SNAPSHOT`；
- 编译目标：Java 8；
- 默认宿主框架：Spring Boot 2.7.18；
- 默认模型出站 Adapter：OkHttp 3.14.9 OpenAI-compatible Chat；
- 可选模型 Adapter：WebFlux OpenAI-compatible Chat；
- OpenAI Chat 协议内核：`patchbridge-agent-model-openai`（请求编码、SSE 解码与 reasoning 状态的唯一实现，OkHttp / WebFlux Adapter 共用，不含 HTTP 传输）；
- 数据库 schema：H2 与 MySQL；
- 默认交付：在源码仓库的 `java/` Reactor 中构建并执行本地 `mvn install`。

### 2.2 Web

- npm root：`patchbridge-agent-web`；
- workspace：`@patchbridge-agent/agent`、`@patchbridge-agent/widget`、`@patchbridge-agent/webmcp-adapter`、`@patchbridge-agent/tool-inspector`、`@patchbridge-agent/call-trace`；
- CI Node.js：22，源码构建至少需要 22.12；
- 默认交付：从源码构建，并由 Starter 通过配置后的 basePath 下的 `assets/**` 提供四个 IIFE Bundle；
- 本版本不承诺 npm registry 安装路径。

## 3. 已实现能力

### 3.1 Browser Agent Runtime

- `AgentController` 是 View 的唯一应用服务入口；
- `AgentState` 是 UI 可见状态的唯一事实源，只通过具名事件和 reducer 变化；
- `AgentExecution` 提供有界模型调用、取消、迟到事件隔离和 Tool 确认；
- 模型定义和实际 Tool 路由使用同一个不可变 `ToolRegistrySnapshot`；
- Model、Tool、Conversation 共用可注入 `HttpTransport`；
- 支持 Hook、Model Interceptor 和 Tool Interceptor，但不提供万能 Plugin Context；
- 支持默认 `<patchbridge-agent>` Widget 和 Headless Controller 两种接入方式。

### 3.2 厂商中立模型契约

- 稳定消息采用 `AgentMessage + ContentBlock`；
- ContentBlock 支持 text、image、reasoning、tool-call、tool-result；
- 展示消息与 Provider 续推所需的 `ModelState { format, data }` 分离；
- Browser 只消费 `block-start`、`block-delta`、`block-stop`、`message-stop` 和标准 `error`；
- 厂商 wire protocol 只存在于服务端 ModelProvider Adapter；
- `ConversationContext { messages, modelState }` 使用同一 revision 和事务原子保存。

### 3.3 Tool 与安全边界

- Java `@AiTool` / `@AiParam` 原生 Tool；
- 后端 Tool discovery 与 call；
- Browser-local JavaScript Tool；
- WebMCP Adapter；
- Global MCP 远程 Tool；
- Tools Inspector；
- Tool 调用前确认；
- 默认 Tool 调用不自动重试。

Browser-local Tool 和 WebMCP Tool 可以复用页面状态或宿主 HTTP Client，但 Browser 从来不是授权边界。涉及后端业务的操作仍必须经过宿主认证、CSRF/请求安全链、RBAC 与业务 ACL。

### 3.4 Conversation、Audit 与 Admin

- Conversation 列表、创建、读取、原子保存和幂等删除；
- owner 范围隔离与 optimistic revision 冲突；
- Audit Sink、查询、脱敏和 JDBC 默认实现；
- Admin stats、trace 列表与详情；
- Global MCP properties 只读配置源或 JDBC 动态配置源；
- Admin Console 位于 `/ai-admin/`。

### 3.5 调试与 UI

- 默认 Widget 支持聊天、会话、流式输出、Tool、确认和取消；
- CSS Variables、稳定 `::part()` 与 `theme="none"`；
- Tools Inspector 只读展示当前 Registry/Execution 快照；
- Call Trace 按 Execution 展示模型、Tool、确认、耗时、token、首 token 延迟和首 token 后平均输出速度；
- 采集默认关闭：Widget 显式 call-trace="memory|persistent" 属性（或工厂 callTrace 选项）才采集；persistent 模式按会话保存在当前浏览器 localStorage，每个会话最近 30 次。

## 4. 当前公开命名

| 范围 | v0.1 契约 |
| --- | --- |
| Java group/package | `io.patchbridge.agent` |
| Maven artifacts | `patchbridge-agent-*` |
| npm scope | `@patchbridge-agent/*` |
| Spring 配置前缀 | `patchbridge-agent` |
| 环境变量前缀 | `PATCHBRIDGE_AGENT_*` |
| 默认 API 根路径 | `/ai` |
| Widget | `<patchbridge-agent>` |
| Call Trace | `<patchbridge-agent-call-trace>` |
| Ready event | `patchbridge-agent-ready` |
| Starter 资源根 | `META-INF/patchbridge-agent/` |
| Admin 资源根 | `META-INF/patchbridge-agent-admin/` |

迁移前的旧名称、包、配置、资源、组件和事件不属于 v0.1 契约，也不提供 alias 或兼容路径。

## 5. 安全默认值

- Admin 默认关闭；开启时宿主必须提供 `AdminAccessPolicy`，不存在 allow-all 默认值；
- 身份只从服务端 `CurrentUserProvider` 获取，Browser 参数不能覆盖可信身份；
- Tool discovery 过滤不能替代每次 `canInvoke`；默认策略只要求已登录，生产 RBAC/ACL 必须由宿主覆盖 `ToolAccessPolicy`；
- 多租户宿主必须用 `ConversationOwnerResolver` 生成包含租户和用户维度的稳定 owner key；
- 未知配置键、未知 DTO/SSE 字段、缺失必填字段、非法类型和非法范围明确失败；
- Global MCP 的 properties 与 JDBC 配置源互斥，不合并、不自动探测、不 fallback；
- JDBC MCP credential 使用 AES-256-GCM，32-byte Base64 主密钥必须由部署环境注入；
- MCP credential 只写不回显；Browser Cookie/Authorization 不透传给远程 MCP Server；
- 模型上游非 2xx 正文不会进入 Browser SSE、审计或日志；
- 默认审计 payload 模式为 `metadata-only`；选择 `full` 时宿主仍应提供符合自身治理要求的 `AuditRedactor`；
- `patchbridge-agent.base-path` 是 Starter 独占 MVC 命名空间，宿主 Controller 映射进入该空间会在启动期失败；空间内 405/406/415 返回统一错误信封，空间外交回宿主异常链；
- 依赖部署条件的配置缺口（默认模型凭据为空、默认 properties MCP 源为空、默认仅认证 ToolAccessPolicy）在启动时输出编号 WARN `PBA-CFG-001/002/003`，确定缺失的必需配置仍保持构造期失败。

## 6. 宿主责任

生产接入必须由宿主完成：

- 认证、当前用户解析、租户上下文和账号生命周期；
- Tool、Admin 和业务 Service 权限；
- 多租户 owner key 规则；
- 数据源、数据库迁移、备份和保留策略；
- 模型与 MCP endpoint、凭据和网络连通性；
- MCP 出站 allowlist、代理、防火墙和 SSRF 防护；
- 请求体大小限制、业务 Rate Limit、并发和配额；
- 审计脱敏、数据保留、合规和外部审计平台；
- Cookie、Bearer、CSRF、刷新 token 等 Browser HTTP 安全链；
- 生产 Secret Manager/KMS 集成。

Demo 中的账号、角色、H2 和页面安全配置只用于展示宿主如何接管这些责任，不能直接作为生产安全方案。

## 7. 已知限制

### 7.1 模型

- 只提供 OpenAI-compatible Chat Provider；
- Anthropic Messages、OpenAI Responses HTTP/WS 尚未实现；
- 不在 Browser Runtime 中增加跨厂商 `reasoning_content` 分支；新增模型必须实现独立 Provider 与 ModelState format；
- 真实模型兼容性、额度、限流与网络可用性依赖部署环境。

### 7.2 Tool 与 MCP

- `@AiExpose`、Existing Java API 自动转 Tool、OpenAPI/springdoc allowlist 尚未实现；
- MCP Server Export 尚未实现；
- MCP v0.1 只支持 Global、Streamable HTTP、Tools；
- 用户级/租户级 MCP、OAuth、Resources、Prompts、Tasks、Sampling、stdio 不在当前范围；
- WebMCP Adapter 不提供 polyfill 或 Compatibility Layer；浏览器不支持时保持明确不可用状态。

### 7.3 Runtime 与持久化

- 不恢复执行到一半的 Browser Agent Runtime；刷新后从最后一个完整 ConversationContext 继续；
- Call Trace 采集默认关闭；开启 persistent 后只存在于当前站点的当前浏览器，不跨设备同步，也不写入服务端 Conversation。localStorage 按 origin 共享，共享设备应由宿主使用用户级 storage key 或在退出登录时清理；
- Call Trace 每个文本字段最多保留 4000 字符；清除站点数据会删除轨迹但不影响服务端会话；
- 完全替换 `AgentEngine` 的宿主必须自行把轨迹存储挂进自己的生命周期事件，否则数据源为空；
- 多 Tab 同时保存同一会话通过 revision 冲突明确失败，不自动合并。

### 7.4 平台与生态

- 仅提供 Spring Boot 2 Adapter；Boot 3 尚未实现；
- 未提供 OpenTelemetry Adapter；
- 未内置 Vault/KMS/Secret Manager Adapter；
- Admin Console 是基础管理与审计界面，不是完整运营平台；
- 不提供 RAG Platform、Vector DB、Multi-Agent Runtime、Workflow Engine、Coding Agent、Browser Automation 或 Scheduler。

## 8. 兼容与升级策略

v0.1 处于首次公开发布前阶段，当前源码和 schema 是唯一契约：

- 不解析旧 DTO 或旧字段；
- 不保留 deprecated API；
- 不提供数据库双写、自动迁移或旧 Call Trace schema 兼容；
- 不在新实现失败时切换到备用实现；
- 一次契约变更必须同步 TypeScript、Java、HTTP DTO、schema、Widget、Demo、测试和文档。

首次公共版本发布后的兼容策略将单独决策，不因未来可能需要而在当前代码中提前增加兼容分支。

## 9. v0.1.0-rc.2 整改内容

本节记录相对 `v0.1.0-rc.1` 的源码变更，整改依据《v0.1 源码发布收口代码质量审计》：

- Q-02 Call Trace 改为显式 opt-in：默认不采集、不持久化，Widget 以 `call-trace="off|memory|persistent"` 显式开启，Demo 展示 `persistent` 用法；
- Q-03 纯前端 Tool 参数在进入 `execute()` 前按注册期编译的 JSON Schema 子集校验，非法参数返回 isError 且 Tool 不执行；
- Q-04 MCP Streamable HTTP Client 严格解析 JSON-RPC envelope（版本、数值 id、result/error 互斥）与 tools/content 形状，畸形响应明确失败；
- Q-05 `UserContext` attributes 与 `ToolCallResult` content 防御性复制并冻结；
- Q-06 MCP 配置版本改为单行 generation 同事务递增（schema 新增 `agent_mcp_config_generation` 表，升级部署需执行最新 schema）；
- Q-07 Conversation HTTP 响应在客户端边界做结构校验，Widget 会话属性插值统一 HTML 转义；
- Q-08 Admin Trace 百分位改为“最近一万次调用”时间窗取样，页面标签如实标注统计范围；
- Q-09 模型流事件转发与终止切换统一串行化（管线锁 + 会话锁单向锁序），取消后不再发布迟到事件，after 恰执行一次；
- Q-10 OpenAI Chat 协议提取为共享内核模块 `patchbridge-agent-model-openai`，OkHttp 与 WebFlux Adapter 只保留传输与取消，净删约 500 行重复实现。

整改链门禁：Java 8 全 Reactor 8 模块 173 tests、Web 5 包 144 tests、四个 Starter IIFE Bundle 与源码构建字节一致、Demo HTTP smoke（启动 / /login / 未认证 401 / H2 schema 自动迁移）、品牌与敏感路径源级扫描零命中。

## 10. 验收与后续

源码候选的命令、判定标准和签署项见[《v0.1 发布检查清单》](release-checklist.md)，实际候选提交与证据见[《v0.1 源码发布候选预检》](evidence/rc2-preflight.md)。

完整接入说明见[《文档中心》](../../README.md)，当前实施状态与后续里程碑只以[《路线图与当前进度》](../../roadmap.md)为准。

---

## 附录：v0.1 版本状态与适用边界（用户手册原文）

## 1. 版本状态与适用边界

### 1.1 当前可以使用什么

v0.1 当前提供：

- Java 8、Spring Boot 2.7.x 方向的嵌入式 Starter；
- OpenAI-compatible Chat 模型代理和结构化 SSE；
- Java Service 使用同 JVM <code>ModelGateway</code> 发起文本或图片单次模型调用；
- Java <code>@AiTool</code>、Global MCP Tool、Browser Local Tool 和 WebMCP Tool 的统一 Registry；
- 基于可信服务端身份的 Tool 发现、调用二次授权、会话 owner 隔离和 Admin 权限端口；
- JDBC Conversation、Audit 和 MCP 配置存储；
- Headless Browser Controller、参考 Widget、Tools Inspector 和 Browser Call Trace；
- Browser Agent 的严格 Tool 批次预检、五项执行限额、唯一 Outcome 与取消/Deadline 迟到隔离；
- H2/MySQL schema、Demo 和静态 Bundle。

这表示源码具备完整主链，不表示公共制品已经发布，也不表示 API 已进入长期兼容期。

### 1.2 当前明确不提供什么

v0.1 不提供：

- Anthropic Provider、OpenAI Responses Provider 或其他厂商 Provider；
- <code>@AiExpose</code>、OpenAPI/springdoc allowlist 自动转 Tool；
- 把 <code>@AiTool</code> 导出为 MCP Server；
- MCP stdio、legacy SSE、Resources、Prompts、Tasks、Sampling、MCP Apps 或完整 OAuth；
- 用户级、租户级、请求级 MCP 配置；
- WebMCP polyfill；
- Spring Boot 3、Plain Servlet 或 Redis Adapter；
- 内置 Rate Limit Policy、OpenTelemetry Adapter 或完整运营平台；
- RAG 平台、多 Agent、Workflow Engine、Coding Agent、Browser Automation Agent、定时或长时间后台 Agent；
- 页面关闭后继续运行，或恢复执行到一半的 Agent Execution；
- Maven Central/npm 公共安装路径和跨版本兼容层。

### 1.3 三类责任必须分开

| 类别 | 含义 | 示例 |
| --- | --- | --- |
| 框架能力 | 当前源码已经实现并有静态或测试证据 | Tool Registry、结构化 SSE、JDBC Conversation |
| 宿主责任 | 框架只提供 Port，企业应用决定规则 | 身份、RBAC、租户 owner、CSRF、出站网络策略 |
| Demo 决策 | 只为展示真实接入路径，不是生产默认 | H2 文件库、内存账号、对 <code>/ai/**</code> 的 CSRF 例外 |
