# v0.1 公开契约与安全默认值审计（R0）

- 文档状态：审计完成，已合并 `main` 并复验通过
- 审计日期：2026-08-21 至 2026-08-22
- 审计历史基准 commit（全仓重命名前）：`3fe277a60a79c0ed92167a3672299f4c6153912d`
- 审计对象：v0.1 源码开源前的公开契约、配置语义与安全默认值

## 0. 文档定位

本文是 R0「公开契约与安全默认值最终审计」的可追踪证据记录，不是实施状态的事实源：

- 实施状态以[《路线图与当前进度》](../../../roadmap.md)为唯一可信来源；
- 架构行为以[《架构设计：模块化单体、六边形架构与设计模式》](../../../architecture/overview.md)和 ADR 为准；
- 本文记录的是基准版本发现、同一分支修复和验证结果，不维护另一份路线图。

## 1. 问题级别定义

| 级别 | 定义 |
| --- | --- |
| P0 | 阻断当前源码发布候选：安全泄漏、数据隔离错误、契约与实现矛盾、双轨协议或隐式 fallback |
| P1 | 必须在 v0.1 收口前修复：配置延迟失败、文档与实现不一致、发布门禁或错误语义不完整 |
| P2 | 不改变当前公开行为的后续改进项；必须记录，不得伪装成已完成能力 |

## 2. 审计原则

1. 每条结论指向代码、测试或可复制命令；没有证据的不写成事实。
2. 路线图明确暂缓或待实现的能力缺席不是 bug；文档把它写成已实现则是发布阻断问题。
3. 公开协议只保留当前字段集合，不增加旧字段别名、兼容解析、双写或 fallback。
4. Browser 不是授权边界；身份、owner、Tool 二次授权、Admin 能力和 MCP 出站凭据都由服务端控制。
5. 修复必须同时包含实现、关键测试、使用说明和路线图记录；状态只在验证通过后更新。

## 3. 审计范围与逐项结论

### 3.1 Java 公共契约

| 编号 | 审计项 | 结论 | 证据 / 备注 |
| --- | --- | --- | --- |
| J-1 | `@AiTool` / `@AiParam` 定义、属性语义与零依赖 | 通过 | `patchbridge-agent-annotations` 仅含注解；扫描、继承与代理行为由 `AnnotatedToolProviderTest` 覆盖 |
| J-2 | `AgentMessage` / `ContentBlock` / `ModelState` / `ConversationContext` 领域形态 | 通过 | Core 使用不可变值；`ConversationContextValues` 精确校验字段和 Block 类型 |
| J-3 | Model、Tool、Conversation、Identity、Admin、Audit、MCP Port | 通过 | Port 位于 Core/MCP；Starter 仅装配 Adapter，宿主身份与业务 ACL 未进入框架领域模型 |
| J-4 | Model / Tool Pipeline 是 Controller 唯一调用链路 | 通过 | `ModelStreamController` 和 `ToolGatewayController` 均调用 InvocationPipeline |
| J-5 | Starter 默认 Bean 可替换 | 通过（F-03） | 默认 Bean 使用 `@ConditionalOnMissingBean`；资源 Configurer 使用稳定 Bean 名替换；MCP 开关覆盖 Client/Store/Registry/Manager/Admin |
| J-6 | Starter 异常处理不污染宿主 | 通过 | `@RestControllerAdvice(assignableTypes=...)` 限定五类 Starter Controller；作用域测试通过 |
| J-7 | 错误与字段缺失显式失败 | 通过（F-01、F-08） | DTO `@JsonAnySetter`、Core 值映射与 scoped binding error handler；无请求体/显式 null 语义有测试 |
| J-8 | Core 与 annotations 无技术框架依赖 | 通过 | Core/annotations 不依赖 Spring、Jackson、OkHttp、WebFlux 或 JDBC |
| J-9 | 无 deprecated API、旧 DTO 或别名 | 通过 | 源码检索未发现 deprecated/legacy 公开路径；厂商 wire 字段只存在于 Provider Adapter |

### 3.2 Browser 公共契约

| 编号 | 审计项 | 结论 | 证据 / 备注 |
| --- | --- | --- | --- |
| B-1 | `@patchbridge-agent/agent` 导出与 Controller 意图方法 | 通过 | `src/index.ts` 只导出当前 Controller、Runtime、Registry、类型与 Client 边界 |
| B-2 | `AgentState` 只经具名事件和 reducer 变化 | 通过 | `stateMachine.ts` 的 `Partial` 仅为 reducer 内部变更类型，不是公共 patch API |
| B-3 | 有界 Execution、取消、迟到事件与半截流 | 通过 | `maxModelCalls`、execution 身份隔离、cancel/HITL 测试覆盖 |
| B-4 | Browser 只解析结构化 Model 事件 | 通过（F-05） | 五类事件及 block/delta/usage/modelState/error 均精确 key 校验；厂商字段明确失败 |
| B-5 | Tool 快照定义、路由与 Inspector 同源 | 通过 | `ToolRegistrySnapshot` 冻结定义与执行器；注册变更只发布新 revision |
| B-6 | Browser-local Tool / WebMCP 边界 | 通过 | WebMCP 独立包、无 polyfill；本地 Tool 不替代服务端授权 |
| B-7 | Model、Tool、Conversation 共用 `HttpTransport` | 通过 | Factory 向三类 Client 注入同一 Transport；认证由宿主 Transport 决定 |
| B-8 | Widget 属性与三层样式边界 | 通过 | Widget 测试覆盖 CSS Variables、`::part()`、`theme="none"` 和宿主 Registry |
| B-9 | Widget / Inspector / Call Trace 不创建第二状态源 | 通过 | 三者只消费 Controller、Registry 或 Runtime Hook 的既有事实；没有独立 Agent Runtime |
| B-10 | 五个 workspace 包与干净测试入口 | 通过（F-04） | 根 `pretest` 先构建 agent dist，再运行 Agent、WebMCP、Widget、Inspector、Call Trace 五包测试；干净 dist 场景已验证 |

### 3.3 HTTP / SSE 契约

| 编号 | 端点 / 能力 | 结论 | 证据 / 备注 |
| --- | --- | --- | --- |
| H-1 | POST `${basePath}/model/stream` | 通过 | 请求信封精确校验；返回结构化 SSE，不透传厂商 chunk |
| H-2 | GET `${basePath}/tools` | 通过 | 只返回当前用户可发现 Tool；标准 `tools` 外层 |
| H-3 | POST `${basePath}/tools/call` | 通过（F-01） | `name` 与显式 `arguments` 必填；未知字段失败；每次调用重新鉴权 |
| H-4 | GET/POST `${basePath}/conversations`、GET `/{id}` | 通过（F-01、F-08） | 创建 DTO 严格；无 body 可创建未命名会话；不存在返回标准 404 错误体 |
| H-5 | PUT/DELETE `${basePath}/conversations/{id}` | 通过 | 完整 Context + revision 原子保存；owner 约束；GET/PUT 不存在统一返回 `CONVERSATION_NOT_FOUND`；标题上限在 HTTP 边界校验 |
| H-6 | GET `${basePath}/admin/stats` | 通过 | Admin 默认关闭且经 `AUDIT_READ` 能力校验 |
| H-7 | GET `${basePath}/admin/traces[/{traceId}]` | 通过（F-09） | 日期、时间范围和分页精确校验；Admin 页面过滤项与后端 AuditInvocationType 一致 |
| H-8 | `${basePath}/admin/mcp/servers/**` | 通过（F-01） | CRUD/启停/测试/刷新分离；写 DTO 场景字段与 nested auth/tools 严格校验 |
| H-9 | GET `${basePath}/assets/**` | 通过（F-07） | Widget、WebMCP、Inspector、Call Trace 四个 IIFE 由 Web 构建复制；CI 同时校验四者 |
| H-10 | SSE 事件、错误、取消与零事件重连 | 通过（F-05） | 首事件前网络错误只重连一次；事件交付后只返回标准网络错误且不重试 |
| H-11 | 请求/响应字段精确且无旧别名 | 通过（F-01、F-05、F-08） | malformed JSON/type mismatch 为 `400 INVALID_ARGUMENT`；会话 404 有标准 code/message |

### 3.4 配置语义

| 编号 | 配置项 | 当前语义 | 结论 |
| --- | --- | --- | --- |
| C-1 | `enabled` | 默认 `true`；`false` 不装配 Starter | 通过 |
| C-2 | `base-path` | 默认 `/ai`；绝对、无尾斜杠、受限字符路径 | 通过 |
| C-3 | `model.base-url` / `model.model` / `model.api-key` | 默认 Provider 要求前两项；URL 必须为绝对 HTTP(S) 且无 user-info/query/fragment；api-key 可空且只在服务端 | 通过（F-02、F-06） |
| C-4 | DataSource | 默认 ConversationRepository 的硬依赖；无内存降级 | 通过 |
| C-5 | `conversations.list-limit` | 默认 50，必须大于 0 | 通过（F-02） |
| C-6 | `audit.*` | enabled 默认 true；payload-mode 仅 `full`/`metadata-only`/`none`；summary 上限必须大于 0 | 通过（F-02） |
| C-7 | `admin.enabled` | 默认 false；开启必须提供 `AdminAccessPolicy` | 通过 |
| C-8 | `mcp.enabled` / `mcp.source` | MCP 默认开启；source 默认 properties，只能选择 properties 或 jdbc，不读取或合并未选中的配置块 | 通过（F-03） |
| C-9 | `mcp.jdbc.encryption-key` | 仅 MCP 开启且 source=jdbc 时必填；标准 Base64 解码后恰好 32 字节 | 通过 |
| C-10 | `mcp.namespace` / `mcp.servers.*` | namespace 为不超过 128 字符的点分标识；Server URL/transport/timeout/auth/tools 统一校验 | 通过（F-01、F-02） |

### 3.5 安全默认值

| 编号 | 审计项 | 结论 | 证据 / 备注 |
| --- | --- | --- | --- |
| S-1 | 身份只来自可信 `CurrentUserProvider` | 通过 | Tool/Model/Conversation/Admin 不接受 userId、tenantId、roles 作为可信请求字段 |
| S-2 | 会话 owner 隔离 | 通过（F-10） | 默认 owner 为全局唯一 userId；多租户宿主必须覆盖 Resolver；全部 JDBC 读写带 ownerKey |
| S-3 | Tool 发现不替代调用鉴权 | 通过 | list 使用 `canDiscover`，call Pipeline 再次执行 `canInvoke` |
| S-4 | HITL 与危险操作 | 通过 | 默认非只读 Browser Tool 要求确认；HITL 是交互安全层，不替代服务端授权 |
| S-5 | Admin / SSRF 高权限边界 | 通过 | Admin 默认关闭；未知路由 fail closed；MCP URL 管理要求独立 MCP 能力与宿主网络策略 |
| S-6 | MCP Credential | 通过 | 响应只含 authType/credentialConfigured；JDBC 仅存 AES-256-GCM 密文；密钥无默认值 |
| S-7 | 审计脱敏 | 通过 | 默认 metadata-only；full 仍经过默认 `[REDACTED]` 或宿主 `AuditRedactor` |
| S-8 | 模型凭据与上游错误不泄漏 | 通过（F-06） | api-key 只写 Authorization；OkHttp/WebFlux Provider 的非 2xx 都只公开状态码，不传播上游正文 |
| S-9 | 重试与失败语义 | 通过 | Tool 默认不重试；Model 仅在零事件网络失败时重连一次；不存在来源优先级或配置 fallback |
| S-10 | Starter 默认不暴露 Admin、不改宿主安全链 | 通过 | `admin.enabled=false`；Interceptor 仅注册明确路径；Advice 仅限定 Controller |

### 3.6 文档 / Demo / 发布面

| 编号 | 审计项 | 结论 | 证据 / 备注 |
| --- | --- | --- | --- |
| D-1 | README / QUICKSTART / 功能备忘 / 架构 / ADR 一致 | 通过（F-10） | Quickstart 已补严格配置与请求失败语义；README 不再把规划能力写成现状 |
| D-2 | Demo 覆盖公开能力入口 | 通过 | Demo 覆盖 Global MCP、前端 Tool、WebMCP、Inspector、Call Trace、主题、HITL、取消和恢复 |
| D-3 | 仓库无密钥、内置 Mock 协议服务或内部环境依赖 | 通过 | 全仓文本扫描无凭据命中；`application-local.*` 未跟踪；测试假实现不进入 Demo 运行产物 |
| D-4 | Starter IIFE 与 Web 构建一致 | 通过（F-07） | 四个 dist 与 Starter 资源逐文件字节一致；提交后及合并 `main` 后执行 CI 同款 diff gate |
| D-5 | 版本说明、已知限制、发布检查清单 | 不属于本次审计 | 路线图 R0 的后续独立子任务，本文不虚报完成 |
| D-6 | 无已删除能力或旧契约残留 | 通过（F-10） | README 已删除未实现能力设计稿；`@AiExpose`、Existing API Adapter、MCP Export 只在路线图待实现项出现 |

## 4. 安全不变量结论

以下不变量逐条通过代码与测试核对：

1. Agent Loop 只在 Browser Runtime 中运行，后端不持有跨请求 Execution；
2. Browser Runtime 与 Java Core 不出现厂商 wire protocol；
3. Message 与 ModelState 分离，并作为同一 ConversationContext 原子持久化；
4. AgentState 只经具名事件和 reducer 修改；
5. Model 定义、Tool 调度与 Inspector 使用同一个 Registry Snapshot；
6. 身份只来自服务端可信 Adapter，发现过滤不替代每次调用授权；
7. 默认 Bean 可被宿主替换，Advice、资源和安全拦截器不污染宿主全局路径；
8. Widget、Inspector 与 Call Trace 不拥有第二份 Agent 领域状态；
9. 取消后不提交迟到事件、稳定消息或保存结果；
10. 不存在旧字段、来源优先级、配置合并、内存降级或静默参数修正。

## 5. 确认问题与闭环

| 编号 | 级别 | 问题 | 修复与关键验证 | 状态 |
| --- | --- | --- | --- | --- |
| F-01 | P0 | Tool、Conversation Create、MCP Admin 与 nested auth/tools 会吞未知字段；binding 错误落入 Spring 默认体 | 严格 DTO/Validator + scoped binding handler；MockMvc/Jackson 用例覆盖未知、缺失、null、类型错误 | 已修复 |
| F-02 | P0 | Spring Binder 会忽略未知配置键；audit 模式、摘要长度、list-limit、timeout、namespace 等错误会静默改变语义或延迟失败 | `ignoreUnknownFields=false` + setter/Provider 构造期校验；顶层/nested 未知键和非法值测试 | 已修复 |
| F-03 | P0 | `mcp.enabled=false` 仍可能装配 JDBC 密钥/Store；properties source 条件与资源替换边界不完整 | MCP Bean 图显式条件、properties/JDBC 互斥表达式、资源 Bean 名 backoff；启用/禁用集成测试 | 已修复 |
| F-04 | P0 | 干净 checkout 直接 `npm test` 因 agent dist 不存在而失败，CI 测试顺序不可复现 | 根 `pretest` 只构建 agent dist，随后运行五包测试；干净 dist 场景通过 | 已修复 |
| F-05 | P0 | Browser SSE 已知事件接受额外字段；首事件前 body 断流不进入约定重连 | 全层精确 key 校验；`reader.read()` 网络错误标准化；新增额外字段与首帧前断流测试 | 已修复 |
| F-06 | P0 | OkHttp/WebFlux Provider 会把上游错误正文送入 Browser SSE、审计或日志；两套 Provider 的 URL 校验未统一拒绝内嵌凭据等危险形状 | 两个 Provider 的非 2xx 都只公开状态码，并在构造期拒绝 user-info/query/fragment 等非法 URL；正文与 URL 测试覆盖 | 已修复 |
| F-07 | P1 | CI 只校验主 Widget，没有覆盖所有独立内置产物 | 一致性 gate 覆盖 Widget、WebMCP、Inspector、Call Trace 四个 IIFE | 已修复 |
| F-08 | P1 | Conversation GET 返回空 404、PUT 不存在返回 400，Browser 无法统一识别资源消失；超长标题到 JDBC 才失败 | Core `ConversationNotFoundException` 统一 GET/PUT 为标准 404；创建/保存共享 256 字符校验 | 已修复 |
| F-09 | P1 | Admin 日期宽松/部分解析、分页静默钳制，静态页还提供后端不存在的 `CONFIRMATION` 过滤项 | 非 lenient 解析、范围/分页校验；删除失效选项并增加资源/枚举契约测试 | 已修复 |
| F-10 | P0 | README 把未实现的 `@AiExpose`/Existing API/MCP Export 写成已支持；路线图 owner 描述与单租户默认不符 | 删除 README 重复设计稿；路线图保留待实现项并说明默认 userId 与多租户覆盖责任 | 已修复 |

## 6. 验证记录

| 日期 | 命令 / 操作 | 结果 | 备注 |
| --- | --- | --- | --- |
| 2026-08-21 | 基准 `mvn --batch-mode clean test` | 通过，105 tests | 8 个 Reactor 模块成功 |
| 2026-08-21 | 干净 dist 的基准 `npm test` | 失败 | 证明 webmcp-adapter 在 agent dist 缺失时无法解析；完整 build 后基准 83 tests 通过 |
| 2026-08-22 | 审计分支（重命名前）`mvn --batch-mode clean test` | 通过，135 tests | Core 26、WebFlux 6、MCP 16、JDBC 14、Starter 73 |
| 2026-08-22 | 审计分支（重命名前）`npm test` | 通过，85 tests | Agent 61、WebMCP 6、Widget 16、Inspector 2 |
| 2026-08-22 | 审计分支（重命名前）`npm run build` + 三 Bundle 一致性 | 通过 | 三个 dist 与 Starter 资源分别执行 `cmp -s`，全部字节一致 |
| 2026-08-22 | Demo 启动与基础 HTTP smoke | 通过 | 临时 H2 + 非秘密测试配置；验证匿名/登录、资产、Tool、Conversation、Admin、MCP 与严格错误，不调用外部模型/MCP |
| 2026-08-22 | PatchBridge 合并结果 `mvn --batch-mode clean test` | 通过，135 tests | 8 个 Reactor 模块成功；Core 26、WebFlux 6、MCP 16、JDBC 14、Starter 73 |
| 2026-08-22 | PatchBridge 合并结果 `npm ci && npm test` | 通过，115 tests | Agent 74、WebMCP 6、Widget 16、Inspector 2、Call Trace 17 |
| 2026-08-22 | PatchBridge 合并结果 `npm run build` + 四 Bundle 一致性 | 通过 | Widget、WebMCP、Inspector、Call Trace 的 dist 与 Starter 资源逐字节一致 |
| 2026-08-22 | PatchBridge 合并结果 Demo HTTP smoke | 通过 | 验证新品牌首页/资产、旧资产缺失、认证、Tool、Conversation GET/PUT 404、严格 DTO、Admin、MCP 与 Admin 页面类型过滤；不调用外部模型/MCP |

## 7. 当前结论与残余边界

确认的 P0/P1 问题均已实现修复并有关键测试。审计提交已与 PatchBridge Agent
重命名及 Browser Call Trace 合并，Java、五包 Web、四个 Bundle 与 Demo smoke 均已复验通过。

不把以下边界伪装成本次已完成能力：真实外部模型/MCP 的连通性需要部署方凭据与网络；
Maven Central/npm 发布、正式版本说明和最终发布检查清单属于路线图 R0 后续任务；
请求体大小、业务 Rate Limit 和多租户 owner 组合规则继续由宿主安全与基础设施负责。

## 8. 与路线图的关系

本文只记录本次审计证据。审计完成状态、最近更新时间和更新记录必须在同一次变更中同步
[《路线图与当前进度》](../../../roadmap.md)；后续能力不得通过修改本文绕过路线图验收。
