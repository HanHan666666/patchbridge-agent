# 路线图与当前进度

- 文档状态：项目进度的唯一可信来源（Single Source of Truth）
- 最近更新：2026-08-28
- 当前里程碑：R1.7“上下文压缩”已完成；R2 Anthropic Provider 与 R3 Responses HTTP Provider 继续保持暂缓；R0 rc.2 候选 `f4c7be4` 已预检落档并冻结 tag `v0.1.0-rc.2`
- 当前发布状态：Pre-release 源码候选；尚未发布 Maven Central 或 npm 公共包

本文只回答三个问题：**已经实现了什么、接下来做什么、哪些事情当前不做**。
原始方案用于保存需求背景和设计演进，不再承担实时进度管理。README 只展示版本边界和本
文入口，不复制另一份可独立演进的路线图。

## 状态规则

| 标记 | 状态 | 判定标准 |
| --- | --- | --- |
| ✅ | 已完成 | 稳定契约、实现和关键测试均已落地；面向用户的能力还必须有使用说明与 Demo |
| 🟡 | 部分完成 | 已有可用基础，但该方向仍存在已明确的缺口，不能把整个方向描述为完成 |
| ⏳ | 待实现 | 需求和边界已明确，但尚未进入实现 |
| ⏸ | 暂缓 | 已讨论，但当前明确不排期；开始前必须重新确认范围 |
| 🚫 | 非当前目标 | 与轻量企业 Web 集成定位不一致，除非项目定位改变，否则不进入路线图 |

状态不能因为“已有接口”“能运行一个 happy path”或“测试里有桩实现”而变成已完成。
一项面向用户的新功能只有在以下内容同时完成后才能标记为 ✅：

1. 公共契约和边界已经确定；
2. 生产实现已经完成，不以 Mock 或兼容分支代替；
3. 关键成功、失败、安全和取消路径已有测试；
4. [《文档中心》](README.md)包含设计原因与最小使用示例；
5. Demo 中存在对应的可观察入口；不适合放进 Demo 的底层能力必须记录验证方式。

## 总体进度快照

以下比例是为了帮助判断项目阶段的**规划估算**，不是测试覆盖率，也不是发布承诺：

| 范围 | 当前估算 | 说明 |
| --- | --- | --- |
| 近期确认的能力组合 | 100% | WebMCP、Inspector、样式扩展、纯前端 Tool、Global MCP、Call Trace、Java 单次模型调用、R1.5 Runtime 生产级加固与 R1.7 上下文压缩均已完成 |
| v0.1 核心功能 | 100% | 核心链路及二次代码质量审计整改已完成，rc.2 已冻结；后续里程碑属于新增能力 |
| v0.1 源码开源准备 | 100% | Java/Web/Bundle/归档/安全扫描与真实模型预检已完成并绑定 rc.2 候选 |
| 原始长期愿景 | 约 70% | 模型厂商扩展、既有 API 自动适配、公共包发布和部分生态能力尚未实现 |

当前判断不是“Agent Runtime 还只有一个原型”。Browser Runtime、Java Ports、Provider
边界、持久化上下文和统一 Tool 调度已经形成完整主干；项目已经从架构验证阶段进入
**开源产品化收口阶段**。

## v0.1 能力状态

### 架构与 Agent Runtime

| 状态 | 能力 | 当前实现边界 |
| --- | --- | --- |
| ✅ | 模块化单体 + 六边形架构 | Java 和 Browser 均由稳定 Port 隔离外部技术，默认仍以一个宿主应用部署 |
| ✅ | 后端无 Agent Runtime 状态 | 后端不驻留跨请求 Agent、执行步骤或等待确认对象；持久化数据不等同于节点运行状态 |
| ✅ | `Message + ContentBlock` | 统一表达 text、image、reasoning、tool-call、tool-result，不把模型状态压成字符串 |
| ✅ | `ModelState` 与展示消息分离 | 厂商续推状态按显式 format/data 保存，Browser 不理解厂商字段 |
| ✅ | 完整历史与模型工作上下文分离 | `messages` 永远完整可见；`modelContext` 独立保存检查点、保留边界、Provider 状态和计量 |
| ✅ | 上下文压缩 | 必需模型窗口配置、固定 80% 自动触发、当前模型摘要、默认 `min(20k, 20%)` 近期保留、手动入口与失败原子性已完成，见 [ADR-004](architecture/adr/0004-context-compaction.md) |
| ✅ | 结构化 Model Stream | Browser 只接收 block/message/error 事件；Provider 独占厂商 wire protocol |
| ✅ | 有界 `AgentExecution` | 模型次数、Tool 次数、整轮 Deadline、模型聚合内容和单个 Tool 结果均有明确上限；框架默认模型预算为 16，Demo 显式使用 30；取消/超时共用唯一终态门与迟到隔离 |
| ✅ | Runtime 生产级执行守卫 | R1.5 的停止原因与 Tool 一致性、全批预检、五项资源上限、Outcome、迟到隔离、Tool 错误分类、共享 Provider fixture 与真实模型 `max-tokens` 验收均已完成，详见 [ADR-003](architecture/adr/0003-browser-runtime-guards.md) |
| ✅ | Human-in-the-loop | Tool 可声明调用前确认，Execution 提供明确 respond/cancel 语义 |
| ✅ | Hook / Interceptor | Runtime Hook、Model Interceptor、Tool Interceptor 均有小接口扩展点 |
| ✅ | 显式前端状态机 | Controller 的初始化、导航、执行、确认、保存和错误均通过事件归约 |
| ✅ | 可注入 HTTP 传输 | Model、Tool、Conversation 共用 `HttpTransport`，宿主可接入既有安全链 |

详细约束见[《架构设计：模块化单体、六边形架构与设计模式》](architecture/overview.md)
和[《厂商中立 Agent Runtime 核心契约》](architecture/adr/0001-provider-neutral-runtime.md)。

### Java 后端与持久化

| 状态 | 能力 | 当前实现边界 |
| --- | --- | --- |
| ✅ | Java 8 Core | Core 只定义模型、Tool、会话、权限、审计等契约，不依赖 Spring |
| ✅ | Spring Boot 2 Starter | 提供显式端点和默认装配，不注册会污染宿主的全局异常处理 |
| ✅ | `@AiTool / @AiParam` | 注解零 Spring 依赖；扫描支持继承、bridge method 与 JDK AOP 代理 |
| ✅ | Tool Discovery / Call | 发现与调用都执行权限检查，可信用户/租户上下文不来自模型参数 |
| ✅ | Conversation Context | 完整 `messages + modelContext` 使用同一 revision 和事务原子保存、恢复；当前唯一 Schema 为 `model_context_json` |
| ✅ | 会话 Owner 隔离 | 默认使用全局唯一 `userId`；多租户宿主必须覆盖 `ConversationOwnerResolver` 生成包含租户与用户维度的 owner key，所有 JDBC 读写均以 owner 约束 |
| ✅ | Audit / Trace 基础能力 | Audit SPI、脱敏、持久化和只读调用链查看已经打通 |
| ✅ | OpenAI-compatible Chat Provider | OkHttp 与可选 WebFlux Adapter 均保持异步流和取消语义 |
| ✅ | Java 后端单次模型调用 API | `ModelGateway` 复用 Model Pipeline 提供同 JVM Java 门面；支持文本/图片、异步/阻塞/超时/取消，仅保留调用期内存状态，默认不写 Conversation、Audit 或任何调用历史 |

### Unified Tool 与 MCP

| 状态 | 能力 | 当前实现边界 |
| --- | --- | --- |
| ✅ | Unified Tool Registry | 后端原生、MCP、纯前端和 WebMCP Tool 进入同一目录，重名明确失败 |
| ✅ | 本轮不可变 Tool 快照 | 模型看到的定义、真正执行的路由和 Inspector 展示使用同一 revision |
| ✅ | 纯前端声明 Tool | 注册、快照与 Browser 执行已完成；`inputSchema` 在统一执行边界强制校验（注册期拒绝子集外关键字） |
| ✅ | WebMCP Adapter | `document.modelContext` 通过独立 Adapter 接入，不污染 Agent Core |
| ✅ | Global MCP 反向代理 | Global/Streamable HTTP/Tools 已实现；JSON-RPC 信封（版本/id/互斥）与结果结构（tools/content 必填）严格校验，违约即失败不降级 |
| ✅ | MCP 后台配置 | JDBC CRUD、启停、测试、刷新和多实例观察已实现；配置版本改为单行 generation（O(1) 读取，变更同事务递增，稳态不触碰凭据密文） |
| ✅ | MCP Credential 安全 | 支持 None、Basic、Bearer、API Key Header、Static Headers，并使用 AES-256-GCM 加密 |
| ✅ | MCP Tool Namespace | 远程 Tool 使用稳定命名空间，避免不同 Server 静默覆盖 |

### UI、调试与 Demo

| 状态 | 能力 | 当前实现边界 |
| --- | --- | --- |
| ✅ | Headless Agent | 宿主可以不使用 Widget，直接订阅 Controller state 并实现自己的 View |
| ✅ | 参考 Widget | 覆盖聊天、会话、流式输出、Tool、HITL 和停止执行的完整参考链路 |
| ✅ | 上下文窗口 UI | 顶栏展示 token / 占比 / 来源 / 阈值 / 近期预算 / 检查点，并提供空闲时“立即压缩”和压缩取消 |
| ✅ | Widget 样式扩展 | CSS Variables、稳定 `::part()` 和 `theme="none"` 三层定制边界 |
| ✅ | Tools Inspector | “聊天 / Tools 调试”页签只读展示 Agent 当前真正可调用的完整 Tool 快照 |
| ✅ | Browser Call Trace | 账本、计时、恢复与视图已实现；累计稳定消息按事件整批原子回填并统一校验；采集默认 `off`、非法取值直接抛错、Demo 显式 `persistent`（Q-02，预检 SECURITY-01 源码核对） |
| ✅ | 功能 Demo | Global MCP、纯前端 Tool、WebMCP、Inspector、Call Trace、主题、HITL、取消和恢复均有入口 |
| ✅ | 真实外部服务原则 | 仓库不内置 Mock AI API 或 Mock MCP Server，Demo 使用显式真实 endpoint |
| ✅ | Demo 预置真实 MCP 示例 | 空库首启自动播种麦当劳 MCP（`mcd` / `https://mcp.mcd.cn`）：无凭据、默认停用；用户在 `/ai-admin` 编辑认证为 bearer 并经凭据 REPLACE 填入令牌后启用；库非空绝不播种，删除后空库重启按同一语义复播 |

### 开源工程基础

| 状态 | 能力 | 当前实现边界 |
| --- | --- | --- |
| ✅ | MIT License | 根目录已经包含 MIT `LICENSE` |
| ✅ | CI | Java 8 测试、Web 测试/构建和 Starter 内置前端产物一致性均进入 GitHub Actions |
| ✅ | 使用与设计文档 | README、QUICKSTART、文档中心、Guide/Reference、架构文档和 Runtime ADR 已建立入口 |
| ✅ | v0.1 源码发布收口 | 二次审计 Q-02～Q-10 整改完成；rc.2 候选 `f4c7be4` 全量预检通过（FINAL-01 满足，Global MCP 真实 endpoint 链路为集成人明示接受的记录偏差）并冻结 tag `v0.1.0-rc.2`；预检记录见[《v0.1 源码发布候选预检》](releases/v0.1/evidence/rc2-preflight.md) |
| ✅ | 开源文档信息架构 | R1.6 已完成：中文文档门户、ASCII `kebab-case` 路径、Guide / Reference / Architecture / Research / Release / Archive / Contributing 分层、旧汇总文档拆分删除、长期文档贡献规范和全量链接验收均已完成 |
| ⏸ | Maven Central / npm 发布 | 当前明确暂缓；本地 Maven Reactor 和前端 workspace 可构建，不等同于公共包已发布 |

## 已有基础但仍未完成的方向

| 状态 | 方向 | 已有基础 | 明确缺口 |
| --- | --- | --- | --- |
| 🟡 | 既有 API 低侵入转 Tool | 纯前端 Tool 可直接复用已有 HTTP API；Core 预留 `OPENAPI` source | 后端 `@AiExpose`、OpenAPI/springdoc 显式 allowlist 转换尚未实现 |
| 🟡 | 多模型厂商 | Model Port、ContentBlock、ModelState 和结构化流已完成 | 当前只有 OpenAI-compatible Chat；Anthropic 与 Responses 尚无 Provider |
| 🟡 | Admin Console | MCP 管理、Audit/Trace 查看可用 | 仍是基础管理页，不是完整运营平台 |
| 🟡 | 可观测性 | Audit、traceId/requestId/toolCallId 与 Hook 已具备 | 尚未提供 OpenTelemetry Adapter |
| 🟡 | Tool Policy | 权限、HITL、Interceptor 和默认不重试已具备 | 没有内置 Rate Limit Policy；应通过显式 Adapter/Interceptor 扩展 |
| 🟡 | MCP 生态 | 外部 MCP Tools 导入与 Global 管理已完成 | 尚未把 `@AiTool` 导出为 MCP Server，也没有 Resources/Prompts 等能力 |

## 推荐实施顺序

路线按“先把当前产品做完整，再扩大协议和生态面”的原则推进。后一个里程碑不会因为已有
预留类型而被视为已经开始。

### R0 — v0.1 源码开源收口（已完成，rc.2 已冻结）

目标：让陌生使用者只根据仓库文档，就能安全地完成一次真实接入。

**已完成子任务：公开契约与安全默认值最终审计（2026-08-22）**

审计范围：

- Java Core Port、领域对象、Starter 自动装配与默认 Bean 替换；
- Browser 包导出、Controller、Runtime、Tool Registry 与 Widget；
- Model SSE、Tool、Conversation、Admin-MCP 的 HTTP 契约；
- DataSource、Model、Admin、MCP、Audit 配置项与默认值；
- 身份与二次授权、owner 隔离、Admin 边界、审计脱敏与 MCP 凭据等安全默认值；
- 文档与 Demo 描述和真实实现之间的一致性。

完成条件：

- 每一项审计范围都有逐项审计记录；
- 发现的问题以“实现修复 + 关键测试 + 文档同步”闭环解决；
- 公开面不存在旧字段、隐式 fallback 或双轨协议；
- Java、Web、Starter Bundle 均通过构建与测试；
- Demo 或明确的底层验证方式覆盖每一项公开能力。

首轮审计结果：10 组当时确认的问题均已闭环；合并后的 PatchBridge Agent 通过 Java 135 tests、
Web 115 tests、四个 Starter Bundle 逐字节一致性与 Demo 基础 HTTP smoke。逐项证据见
[《v0.1 公开契约与安全默认值审计》](releases/v0.1/audits/public-contract-and-security.md)。

**首轮候选 `09e598d` 曾通过全部 41 项预检 gate，并在 `a6efdc2` 上冻结 rc.1；随后二次代码质量审计重新打开 R0。下列整改和复验现已全部完成，最终候选为 `f4c7be4`（tag `v0.1.0-rc.2`）。详情见[《v0.1 源码发布收口代码质量审计》](releases/v0.1/audits/source-quality.md)与[《v0.1 源码发布候选预检》](releases/v0.1/evidence/rc2-preflight.md)。**

- [x] 对公开 API、配置项、异常语义和安全默认值做最终审计（2026-08-22 完成）；
- [x] 修复独占 base-path 契约、405/406/415 统一错误信封与空白 userId 拒绝，Widget 动态 title/login-url 只重绘不重建（2026-08-22 完成，Starter 97 tests）；
- [x] 增加启动配置诊断：确定缺失的必需配置保持构造期失败，依赖部署条件的缺口输出编号 WARN `PBA-CFG-001/002/003`（2026-08-22 完成）；
- [x] 检查仓库不包含密钥、Mock 协议服务、失效产物和内部环境依赖（2026-08-22 完成）；
- [x] 补齐版本说明、用户手册、已知限制和发布检查清单（2026-08-22 完成，见[《文档中心》](README.md)、[《v0.1-版本说明》](releases/v0.1/release-notes.md)、[《v0.1-发布检查清单》](releases/v0.1/release-checklist.md)）；
- [x] 从干净环境执行 Java 8、Web、Starter bundle 与 Demo 的可复制验收（2026-08-22 完成：隔离仓库全 reactor 159 tests、Node 22.12.0 Web 131 tests、归档复建、HTTP smoke；真实模型链路 DEMO-03 亦全绿——api 17/17、真实 LLM 全链路 10/10，见[《v0.1-源码发布候选预检》](releases/v0.1/evidence/rc2-preflight.md)）；
- [x] 冻结 v0.1 源码发布候选并记录预检结果（预检记录绑定候选 `09e598d`；FINAL-01 满足后在 `a6efdc2` 打 annotated tag `v0.1.0-rc.1`，main 已快进对齐）。
- [x] 修复二次审计确认的 Call Trace 默认持久化、纯前端 Tool 参数校验、MCP 严格解析与候选标签状态不一致等 P1 问题（2026-08-22 完成，Q-02 a0f2d5e、Q-03 2fda35a、Q-04 7f0d327）；
- [x] 关闭不可变对象、MCP 配置版本扫描、Browser 响应/DOM 边界、审计百分位、取消竞态与协议重复实现等 P2 问题（2026-08-22 完成，Q-05 0a5c2d3、Q-06 87dc103、Q-07 8ecf8b0、Q-08 031c3e4、Q-09 df1f177、Q-10 964b04d）；
- [x] 重新执行 Java 8、Web、Bundle、归档、Demo、真实模型与真实 MCP 验收，在包含最终路线图的提交上创建新候选标签（2026-08-22 完成开发侧验证；2026-08-23 完成 rc.2 正式预检：Java 8 全 Reactor 173、Web 144、四 Bundle 逐字节、归档复建、gitleaks 双模式零 finding、真实模型 e2e 17/17 + 10/10；Global MCP 真实 endpoint 链路经集成人明示接受以配置边界测试与 MCP 单元/集成测试覆盖；预检记录绑定 `f4c7be4`）。

#### 品牌/技术命名迁移（PatchBridge Agent，已完成）

- [x] 完整、无兼容层的品牌与命名迁移：展示名 `PatchBridge Agent`（中文名“破补丁 Agent”）、
  slug `patchbridge-agent`、Java group/package `io.patchbridge.agent`、Maven 模块
  `patchbridge-agent-*`、npm `@patchbridge-agent/*`、Spring 配置 `patchbridge-agent`、
  环境变量 `PATCHBRIDGE_AGENT_*`、Web Component `<patchbridge-agent>`、事件
  `patchbridge-agent-ready`、CSS 变量 `--patchbridge-agent-*`、JS global
  `PatchBridgeAgentWebMcp` 与 bundle `patchbridge-agent*.js`；不保留旧品牌入口；
- 验收条件：对 Git 跟踪文件执行大小写敏感与不敏感的旧品牌残留扫描，结果必须为零；
  文档本地链接与命令路径指向新目录；CI 模块路径与 Starter bundle 校验路径为新路径。
- 品牌迁移分支当时的独立验收：Java 全 reactor 共 99 个测试通过；Web 在 `npm ci` 后共 113 个测试通过，
  五个包构建成功，四个 IIFE bundle 与 Starter 内置资源逐字节一致；Git 跟踪内容和路径的
  旧品牌残留扫描为零，文档、CI 与命名一致性复核完成。R0 中实际 Java 8 CI、真实 Demo
  接入与正式发布检查仍按原计划执行，公共包仍未发布（见 R6）。

完成标准：CI 全绿；Quickstart 可从零复现；Demo 的每项公开能力与文档中心/Guide 一一对应；安全
配置失败时明确报错；仓库不存在需要兼容的旧契约或旧实现。

### R1 — Java 后端单次模型调用 API（已完成，2026-08-24）

目标：让引入 Starter 的宿主 Java Service 可以在同一 JVM 内方便地发起一次厂商中立模型
调用，不引入后端 Agent Loop，也不要求宿主反向请求自己的 Browser SSE Controller。

范围：

- [x] 增加 `ModelGateway`、`ModelInvocation`、`ModelResponse` 与严格结构化事件聚合器；
- [x] 支持文本、图片 URL、Base64 图片、异步结果、阻塞等待、超时和主动取消；
- [x] 复用 `ModelInvocationPipeline`、`ModelProvider` 与显式宿主 Interceptor；
- [x] Starter 自动装配可替换的同 JVM Java Bean，不新增远程 HTTP 端点；
- [x] 默认不读写 Conversation、Audit、数据库、文件、缓存或进程级调用历史；
- [x] 宿主通过返回结果自行决定是否保存；框架不提供默认响应 Repository；
- [x] 第一版不执行 Tool、不启动 Agent Loop，并拒绝非空 Tool 定义和意外 tool-call；
- [x] 增加文本与图片真实模型 Demo、使用说明及不持久化专项验证。

验收条件：

- 每次调用最多触发一次 Provider 请求，终止后释放聚合中间态和上游句柄；
- 取消、超时、完成与异步失败使用唯一线性化终止语义，迟到事件不能修改结果；
- Core 与 Starter 关键测试证明 Java 调用不访问 `ConversationRepository` 或 `AuditSink`；
- 默认 OkHttp 与可选 WebFlux Provider 返回一致的厂商中立 `ModelResponse`；
- 文档中心、对应 Guide/Reference、架构文档、Demo 和真实模型验证全部完成后才能标记为 ✅。

验收结果：Java 8 全 Reactor 共 220 项测试通过；Core 覆盖同步回调、结构化聚合、Tool 拒绝、
只读结果投影、终态竞态和聚合器释放，Starter 证明默认 Bean 可替换且零
Conversation/Audit/HTTP 映射污染；OkHttp 与 WebFlux 均通过 Provider → Pipeline → Gateway
等价响应和超时关闭真实上游测试。已认证 Demo 使用真实网关完成文本 HTTP 200
（`MAX_TOKENS`）与 Base64 图片 HTTP 200（`END_TURN`）验证；图片异步预算根据真实验证固定为
60 秒，不增加重试、降级或备用路径。

详细方案见[《Java 后端单次模型调用 API 设计》](architecture/designs/java-model-gateway.md)。

### R1.5 — Browser Agent Runtime 生产级加固（已完成，2026-08-24）

目标：不扩大 Agent 产品范围，只把已有纯前端 Agent Loop 的协议、资源和终态边界收紧为
可验证的生产契约，然后再增加 Anthropic 和 Responses Provider。

范围：

- [x] 建立 `stopReason` 与 Tool Call 的严格决策表，`max-tokens` 下禁止调度 Tool；
- [x] 校验 Tool Call ID 在会话上下文与本次 Execution 中唯一，并在任何副作用前完成整批 Tool 预检；
- [x] 以 `AgentRunOutcome` 取代 `aborted: boolean`，区分自然完成、`max-tokens` 截断和主动取消；
- [x] 在 `maxModelCalls` 之外增加 Tool 次数、Execution 时长、模型聚合内容和 Tool 结果的明确上限；
- [x] 增加 Execution 终态门闩，使忽略 `AbortSignal` 的 Model/Tool 不能让结果永久挂起或回写迟到事件；
- [x] 保留 `ToolCallResult.isError` 作为模型可见业务失败，未预期 throw/rejection 改为终止 Execution；
- [x] 固化 Java Provider 与 Browser Runtime 的统一契约验收矩阵，后续 Provider 必须通过同一套边界验证。

验收结果：上述七项代码契约已落地，Java OpenAI 协议内核与 Browser `HttpModel`
共用 `test-fixtures/model-provider-contract-v1.json` 中的标准事件序列；OkHttp/WebFlux
保留各自的传输、取消与清理边界测试。真实 OpenAI-compatible 模型以 8-token 输出预算
触发 `max-tokens`：Widget 在保存期间及保存完成后均保留截断提示，历史会话写入成功，
Call Trace 同步记录“输出已截断”，该轮实际执行 Model 1 次、Tool 0 次。
最终验证为 Corretto Java 8 全 Reactor 235 项、Web 五包 215 项测试全部通过，五包构建成功，
四份 Starter 内嵌 bundle 与源码构建产物逐字节一致，tracked Markdown 离线链接检查零错误。

验收条件：

- 停止原因、Tool Call ID、Tool 名称、参数形状或批次次数等预检违约时，测试证明当前批次 Tool 调用数为零；Tool 结果超限或运行中 Deadline 只承诺零结果发布、零下一次模型调用和迟到隔离，不承诺回滚已发生的 Tool 副作用；
- Model、Tool 和人工确认即使不响应取消，主动取消也能立即收敛，未主动取消的挂起调用按 Deadline 失败，且全程只发布一个终态；
- 超限不静默截断、不重试、不降级，所有错误使用稳定 `AgentError` 代码；
- `max-tokens` 结果在 Widget 中可见，Controller、Hook、Call Trace 与会话保存使用同一终态语义；
- Java/Web 关键契约测试和全量回归通过，真实模型覆盖一次 `max-tokens` 路径；
- [《文档中心》](README.md)与 [《Runtime 契约参考》](reference/runtime-contracts.md) 说明限额配置、截断提示和 Tool 业务错误契约；
- 底层契约测试与现有 Widget 终态提示是本能力的明确验证方式，不新增与业务无关的 Demo 页。

详细决策见 [ADR-003：Browser Agent Runtime 生产级执行守卫](architecture/adr/0003-browser-runtime-guards.md)。

### R1.6 — 开源文档信息架构重构（已完成，2026-08-24）

目标：在不修改产品行为的前提下，把当前散落、重复和职责混杂的 Markdown 文档整理为适合
开源维护的中文文档体系，使第一次接触项目的使用者能够按任务找到准确入口，并让后续执行型
AI 能够判断一项变更应同步哪一类文档。

范围：

- [x] 建立 `docs/README.md` 中文文档门户，并按 Guide、Reference、Architecture、Research、
  Release、Archive 和 Contributing 分层；
- [x] 文档标题、导航和正文保持中文优先，`docs/` 内路径统一为简短 ASCII `kebab-case`；
- [x] 拆分“用户手册”和“功能设计与使用备忘”两份旧汇总文档，把教程、契约、架构解释和
  历史证据迁入各自唯一权威位置，迁移完成后删除旧汇总文件；
- [x] 将路线图迁移为 `docs/roadmap.md`，同步 `AGENTS.md` 的强制路径和全部仓库引用；
- [x] 更新根 README 与 Quick Start 的文档入口和当前状态，迁移架构、ADR、研究、Release 和早期设计文档；
- [x] 建立长期文档贡献规范，并完成全量 Markdown 链接、旧路径和文件命名检查。

验收条件：

- `docs/` 根目录只保留文档门户、路线图和职责明确的分类目录，不存在空模板或旧路径兼容页；
- 同一个当前事实只有一个权威来源，Guide、Reference、Architecture、Roadmap 与 Release
  不再维护可独立演进的重复内容；
- Spring Boot、Browser、Tool、MCP、Widget、Inspector、Call Trace 和 Java 模型调用均有
  中文任务入口、使用步骤及验证方式；
- 历史设计、研究和发布证据与当前使用文档物理隔离，且历史结论、候选 Commit 和验收数字
  不被改写；
- `AGENTS.md`、README、Quick Start、包 README、CI、发布脚本及全部文档引用同步完成；
- 文件名、旧路径和离线链接检查全部通过，且工作区中的无关文件未被纳入。

详细的目标目录、逐文件与逐章节迁移表、中文术语规范、执行阶段和完整完成定义见
[《开源文档信息架构与中文写作体系重构方案》](architecture/designs/documentation-system.md)。本阶段
不搭建文档网站、不创建英文副本、不修改产品源码，也不保留旧文档路径的兼容文件。

验收结果：`docs/README.md` 中文门户、13 份 Guide、5 份 Reference、Architecture/ADR、Research、
Release/审计/证据、Archive 与 Contributing 规范均已落地；“用户手册”与“功能设计与使用备忘”两份旧汇总文档已按章节拆分并删除，路线图迁移为 `docs/roadmap.md`；
`AGENTS.md`、README、QUICKSTART、包 README、发布检查清单中的旧路径引用已同步；
`docs/` 路径已全部 ASCII 化，Markdown 本地链接检查与旧路径扫描无残留。

### R1.7 — 上下文压缩（已完成，2026-08-27）

目标：长会话达到模型窗口前，完整保留用户可见聊天历史，只把下一次 Provider 请求使用的
工作上下文压缩为“当前模型摘要 + 近期原始消息”，并让自动与手动路径共享同一套边界。

范围：

- [x] 把唯一 `ConversationContext` 替换为完整 `messages + modelContext`，其中检查点、第一条
  保留消息、Provider 私有状态和 token 计量同一 revision 原子保存；
- [x] 要求宿主显式配置模型上下文窗口，固定在 80% 自动触发，默认近期预算为
  `min(20_000, floor(window × 20%))`，允许单一显式近期预算覆盖；
- [x] Browser `ContextManager` 集中负责用量判断、system 固定、Tool Call / Result 原子切分、
  重复摘要合并、模型输入投影和压缩后保守估算；
- [x] 服务端使用当前 `ModelGateway` 生成摘要，不提供 Tool 或替代摘要模型；Provider 通过
  `ModelStateProjector` 独占私有状态投影；摘要模型读取保留尾部进行任务状态对账，避免把
  已在近期消息完成的事项继续写成剩余工作；
- [x] 正常 Provider usage 改为必需契约；成功压缩后仅在下一次正常响应前标记为 estimated；
- [x] 默认 Widget 增加 token / 窗口占比、配置、检查点和“立即压缩”，Headless Controller
  增加 `compactContext()`；
- [x] 压缩、投影、取消或手动 revision 保存失败时保持完整历史和旧 `modelContext`，不重试、
  不换模型、不清空状态、不继续发送可能溢出的请求；
- [x] JDBC 当前 Schema 直接使用 `model_context_json`，不保留未发布旧结构的读取、双写或迁移 fallback。

验收条件：

- 自动阈值、默认/覆盖近期预算、非法窗口配置、首次/重复压缩、Tool 原子边界、system 固定和
  无安全前缀均有单元测试；
- 摘要成功、意外 Tool / `max-tokens`、缺 usage、Provider 状态投影、取消、手动保存冲突和失败
  不变均有 Core、Provider、Starter 或 Controller 契约测试；
- Widget 提供可观察的自动阶段、计量来源、检查点和手动入口，完整历史在压缩前后数量不变；
- 配置、HTTP、Browser、Runtime、错误码、架构、ADR、使用指南、Quick Start 与 Demo 验收同步；
- Java/Web 全量测试、五个 Browser workspace 构建和四个 Starter Bundle 字节一致性通过。

验收结果：Java 全 Reactor 248 tests 通过；Web 五个 workspace 共 230 tests 通过；五包构建
成功，四个生成 Bundle 与 Starter classpath 资源逐字节一致。Demo 首页的默认 Widget 已直接
提供上下文面板和手动入口；`PatchBridgeAgentStarterIntegrationTest` 覆盖配置与压缩 HTTP
端点，Demo API E2E 校验模型窗口与 80% 派生值，ContextManager / Provider 测试覆盖自动路径
和失败原子性，因此无需增加独立 Demo 页。

设计原因见[ADR-004](architecture/adr/0004-context-compaction.md)，接入步骤见
[《使用上下文压缩》](guides/context-compaction.md)。

### R2 — Anthropic Provider 与会话连续状态重置（暂缓）

- [ ] 实现 Anthropic Messages 请求/流式事件 Adapter；
- [ ] 使用独立 `ModelState.format` 保留带签名 thinking block；
- [ ] 固化 Provider 拥有的 ModelState 完整替换语义：`message-stop` 返回非空下一状态时
  替换旧值，返回 `null` 时清空；Runtime 不推测生命周期，公共 `ModelState`
  不增加 `scope`（见[ADR-002](architecture/adr/0002-model-state-lifecycle.md)）；
- [ ] 实现显式会话连续状态重置：在 owner 隔离和 `expectedRevision` 下原子保留消息、
  清空 `modelState`、推进 revision 并返回完整快照；普通续跑的 format 不匹配或过期
  仍明确失败，不自动无状态重试（见[ADR-002](architecture/adr/0002-model-state-lifecycle.md)）；
- [ ] 验证工具交错调用、取消、错误映射和会话恢复；
- [ ] 增加真实配置说明与 Demo 选择入口；文档中心、对应 Guide/Reference 和 Demo 同步说明
  显式状态重置的用法、不可恢复代价与冲突处理。

### R3 — OpenAI Responses HTTP Provider（暂缓）

- [ ] 将 Responses item/event 映射为现有 ContentBlock 与结构化流；
- [ ] 明确 `previous_response_id`、加密 reasoning item 与无状态后端的关系；由 Provider
  返回完整下一状态或 `null`，并将上游续推失效明确映射为 `MODEL_STATE_EXPIRED`；
- [ ] 验证 Tool Calling、续推状态、取消和错误语义；
- [ ] 不把 Responses 专有字段泄漏进 Core 或 Browser Runtime。

### R4 — Responses WebSocket 可行性与 Adapter（先研究，暂缓实现）

- [ ] 用真实 API 测量握手复用、带宽、延迟和连接生命周期收益；
- [ ] 研究 Browser Runtime 与后端无状态目标下的连接归属、重连和取消；
- [ ] 只有收益与复杂度得到验证后，才决定是否实现独立传输 Adapter；
- [ ] 不为了 WebSocket 修改稳定的 Model Port 与 Runtime 事件契约。

### R5 — 既有 Java API 显式暴露（待实现）

- [ ] 设计并实现 `@AiExpose`，复用现有 Controller/Service 与 DTO；
- [ ] 增加 OpenAPI/springdoc Adapter，但只允许显式 allowlist；
- [ ] 继续复用宿主认证、CSRF、校验、RBAC 和业务权限；
- [ ] 禁止默认扫描并暴露全部 Controller 或 Swagger Operation。

### R6 — 公共依赖发布（暂缓）

- [ ] 确定 Maven coordinates、npm package names 与版本策略；
- [ ] 完成 Maven Central 与 npm 发布流水线；
- [ ] 补齐源码包、签名、SBOM、变更日志和发布回滚说明；
- [ ] 在首个公开版本之前继续允许直接删除不合理契约，不保留兼容层。

### R7 — 根据真实采用反馈扩展

以下项目不提前承诺顺序，必须由真实宿主需求驱动：

- Spring Boot 3 Adapter；
- MCP Server Export（把本地 Tool 暴露给外部 MCP Client）；
- OpenTelemetry Adapter；
- 外部 Secret Manager Adapter；
- 更完整但仍保持可替换的 Admin 能力。

## 原始 Phase 0～4 与当前实现的对应关系

原始设计中的阶段保留为演进背景，不再直接用于排期。当前对应关系如下：

| 原始阶段 | 当前状态 | 说明 |
| --- | --- | --- |
| Phase 0：Strands 技术验证 | ✅ 目标已完成、实现路线已替换 | 已证明 Browser Agent 可行；最终采用更小的自研 Runtime，不保留 Strands 兼容层 |
| Phase 1：最小可发布版本 | ✅ 核心能力完成 | Java Core、Boot 2 Starter、Tool、Model、Conversation 与 Web UI 已落地；旧 session 命名不保留 |
| Phase 2：企业可用性 | 🟡 部分完成 | RBAC、HITL、Audit、乐观 revision 与上下文压缩已完成；Boot 3、内置限流未完成 |
| Phase 3：减少改造成本 | 🟡 部分完成 | 纯前端 Tool 已完成；`@AiExpose` 与 OpenAPI 自动适配未完成 |
| Phase 4：生态兼容 | 🟡 部分完成 | MCP Tools 导入与管理已完成；MCP Export 与 OpenTelemetry 未完成 |

任何早期文档若与本文状态冲突，以本文为准；架构行为则以架构文档和 ADR 为准。

## 当前明确不做

以下能力不是当前轻量企业 Web Agent 框架的目标：

- RAG Platform / Vector Database；
- Multi-Agent Runtime；
- 通用 Workflow Engine；
- Durable / Long-running Background Agent；
- Coding Agent；
- Browser Automation Agent；
- Scheduled Agent；
- Agent Marketplace；
- 恢复执行到一半的 Browser Agent Runtime。

MCP 用户级/租户级配置、完整 OAuth、Resources、Prompts、Tasks、Sampling、Vault/KMS 默认
实现也不在当前版本范围；未来只有真实需求确认后才进入待实现状态。

## 路线图同步约定

从本文件建立后，每次实现或修改需求必须在同一次变更中同步路线图：

1. 开始开发时，把对应项从“待实现/暂缓”调整为明确的当前里程碑，写清范围和验收条件；
2. 完成实现时，只有满足第 1 节完成标准才能标记 ✅；
3. 范围改变时，直接修改当前契约和实现，不添加未被要求的旧版兼容、fallback 或双轨路径；
4. 新增公共能力时，同步文档中心、对应 Guide/Reference 和 Demo；改变架构不变量时，同步架构文档或新增 ADR；
5. 每次修改本文件都更新顶部“最近更新”，并在下面的记录中写一行可追溯摘要；
6. README 不维护第二份详细进度表，只链接本文并显示当前里程碑。

### 更新记录

| 日期 | 变更 |
| --- | --- |
| 2026-08-28 | 修复重复压缩传播陈旧任务状态：保留现有 Browser/HTTP 契约，由服务端摘要入口把 `retainedMessages` 作为只读对账上下文交给当前模型，检查点只摘要淘汰前缀，但不得把已在保留尾部完成、取消或替代的事项继续列为剩余工作；摘要调用的 Provider 私有状态同步投影到全部真实输入；新增“前缀完成 83 次、尾部完成至 100 次”回归测试，并同步 ADR、Guide、HTTP Reference；Java 248 tests、Web 230 tests、五包构建与四 Bundle 字节一致通过 |
| 2026-08-28 | 将 Demo 单次 Execution 的 `maxModelCalls` 从局部覆盖值 8 调整为 30，框架公共默认值 16 与其他宿主契约保持不变；Demo 指南明确已完成会话使用文件型 H2 持久化、未完成 Execution 不恢复；新增静态装配契约锁定 30 次预算；Java 247 tests、Web 230 tests、五包构建与四 Bundle 字节一致通过 |
| 2026-08-28 | 修复 Call Trace 在连续 `tool-use` 中误报 Tool 引用损坏：保留 Runtime 累计消息契约，改为一次稳定消息事件先生成全部候选轨迹、统一校验后再单次提交；非法批次不留下半更新状态；新增“模型 Tool Call → Tool Result → 模型再次 Tool Call → 最终回答”完整回归测试，并同步 Call Trace 采集契约；Java 247 tests、Web 229 tests、五包构建与四 Bundle 字节一致通过 |
| 2026-08-27 | 完成 R1.7“上下文压缩”：完整聊天历史与模型工作上下文分离；模型窗口改为必需配置，固定 80% 自动触发并默认保留 `min(20k, 20%)` 近期消息；Browser ContextManager 统一 system/Tool 安全切分、重复摘要、当前模型输入与 Provider usage，Starter 增加模型配置/压缩端点和状态投影，Widget 增加用量/检查点/手动入口；JDBC 唯一 Schema 替换为 `model_context_json` 且无兼容双轨；ADR-004、Guide/Reference/Architecture/Demo/README 同步；Java 247 tests、Web 228 tests、五包构建与四 Bundle 字节一致通过 |
| 2026-08-25 | 重写根 README（1180 行 → 约 520 行）：按"简介 → 解决的问题 → 三个设计目标（服务端无 Agent 状态 / 低侵入集成 / 安全边界留在服务端）→ 场景与边界 → 架构总览 → 快速开始 → 安全模型 → 相关项目 → 状态与文档导航"重排；摘要前置三个核心设计（纯客户端 Agent、前端逻辑注册为 Agent Tool、注解式后端接入）；删除"为什么开源""Project Philosophy"等章节，Model/MCP/Conversation/Admin 细节收敛为结论加文档中心链接；保留双语 tagline 与状态声明，全文改用直接陈述句式；同日按评审意见修订：能力速览 Model Gateway 标注浏览器请求复用当前页面登录态、MCP Gateway 改为反向代理定位（凭据加密细节留在安全模型清单）、痛点新增"新领域的学习成本"并点名 Spring AI 的 JDK 17 / Boot 3 门槛、不覆盖场景说明改为"重型 Agent 平台领域 vs 本框架轻量定位、执行能力持续增强"；不改变任何产品行为与契约 |
| 2026-08-24 | 完成 R1.6“开源文档信息架构重构”：建立中文文档门户、Guide/Reference/Architecture/ADR/Research/Release/Archive/Contributing 分层，拆分并删除用户手册与功能备忘，路线图迁移为 `docs/roadmap.md`，更新 AGENTS/README/QUICKSTART/包 README/发布检查清单引用，完成文档贡献规范与全量本地链接/路径验收 |
| 2026-08-24 | 接受 R1.6“开源文档信息架构重构”设计并登记为待实施：确定中文标题、导航、正文与 ASCII 稳定路径分层，规划 Guide、Reference、Architecture、Research、Release、Archive、Contributing 信息架构；给出当前文件与两份混合长文档的迁移表、唯一权威来源矩阵、执行阶段和验收 Gate；明确本阶段不搭建文档网站、不创建英文副本、不修改产品源码、不保留旧路径兼容页 |
| 2026-08-24 | R1.5 提交前交叉审计闭环：OpenAI Chat Tool Result 出站严格收敛为官方 `role/content/tool_call_id` 字段；两份共享 Provider fixture 真实经过 `ModelStreamController → SseEmitter` 并逐帧精确比较；内部 Call Trace Hook 固定先于宿主 Hook 建账，宿主启动 Hook 失败仍保留原错误并封闭可恢复的失败轨迹；ADR-001 状态同步为 R1.5 已完成；Corretto Java 8 全 Reactor 235 tests、Web 215 tests、五包 build、四 bundle 逐字节一致与 lychee 离线检查全部通过 |
| 2026-08-24 | 完成 R1.5“Browser Agent Runtime 生产级加固”：严格 `stopReason`/Tool 决策、整批预检、`AgentRunOutcome`、五项执行限额、Deadline/迟到隔离和 Tool 错误分类已实现；最终审计补齐终态 Hook 独立 diagnostics、同步回调后的 Deadline 门、取消后零额外流读取、可注销活动 waiter 及 Tool 结果精确运行时校验；增加 Java/Browser 共享 Provider fixture 与 Adapter 传输边界契约测试；真实 OpenAI-compatible 模型以 8-token 预算完成 `max-tokens` 验收，Widget、会话保存与 Call Trace 终态一致；同步 ADR、架构文档、功能备忘、用户手册和包 README |
| 2026-08-24 | 完成 R1“Java 后端单次模型调用 API”：落地 `ModelGateway`/`ModelInvocation`/`ModelResponse`、严格事件聚合、只读异步结果投影、线性化取消/超时及中间态释放；Starter 提供框架前缀且可替换的同 JVM Bean，无新增框架端点或持久化；Demo 显式透传可信用户/租户上下文并将图片 Servlet 生命周期绑定上游取消；OkHttp/WebFlux 完整 Gateway 契约与真实关闭测试通过；Corretto Java 8 全 Reactor 220 tests，真实文本与 Base64 图片端点均 HTTP 200；架构、功能备忘、用户手册和专项设计同步完成 |
| 2026-08-24 | 按推荐实施顺序启动 R1“Java 后端单次模型调用 API”：实现 Core 契约与聚合生命周期、Starter 可替换同 JVM Bean、真实文本/图片 Demo、使用说明和零持久化验证；第一版不执行 Tool、不新增远程端点、不记录调用历史 |
| 2026-08-24 | 对照当前 Browser Runtime、Pi Agent 与 Codex Rust Agent Loop 新增 ADR-003 及 R1.5：只纳入停止原因/Tool 一致性、Tool 批次预检、执行资源上限、迟到结果隔离、Tool 错误分类与 Provider 契约测试；明确不引入 Steering、并行 Tool、Durable Runtime、Sandbox、通用重试或 Workflow；R1.5 状态为待实现，作为 R2/R3 Provider 扩展前置 |
| 2026-08-23 | Demo 播种机制按用户反馈重构为初始化数据脚本：删除 `DemoMcpSeeder` 代码路径，改为 `demo-mcp-seed.sql`（经 `spring.sql.init` data-locations 执行，`WHERE NOT EXISTS` 守卫维持"仅空库播种、库非空不写、删空复播"语义），行为与此前验证完全一致；新增 `DemoMcpSeedSqlTest` 4 项语义测试（字段与 `store.create` 逐列一致、过校验器、幂等、非空不触碰 generation） |
| 2026-08-23 | 新增 Demo 空库首启播种麦当劳 MCP 示例（`DemoMcpSeeder`：`mcd`/`https://mcp.mcd.cn`，无凭据、默认停用，token 由用户在 `/ai-admin` 经 REPLACE 填入后启用；库非空不播种，空库重启复播）；同时以此真实 endpoint 补跑 Global MCP 全链路验证：JDBC CRUD、REPLACE 写入 Bearer Token、连通测试 265ms、29 个 `mcp.mcd.*` 工具命名空间导入、真实只读调用 `mcp.mcd.query-meals`、刷新、KEEP 凭据保护（拒改 auth.type）、乐观锁 409、启停边界、匿名 401、删除、重启不重复播种且 Token 加密存活——预检记录中的该项偏差就此闭环；全 Reactor 177 tests（含播种器 4 项） |
| 2026-08-23 | 补齐上一提交的状态同步：§3 能力表三处过期标记翻绿——有界 `AgentExecution` 线性化竞态（Q-09）、Browser Call Trace 默认关闭（Q-02）、v0.1 源码发布收口（rc.2 预检落档）；§4 未完成方向的 🟡 为刻意保留，不属于过期状态 |
| 2026-08-23 | 候选 `f4c7be4`（tag `v0.1.0-rc.2`）在隔离环境从头执行检查清单——Java 8（Corretto 1.8.0_462）全 Reactor 9 模块 173 tests、Node npm ci + 144 tests + 五包构建、四 Bundle 逐字节一致、隔离 install 与归档复建、gitleaks 8.30.1 双模式零 finding、lychee 0 错误、真实模型链路 e2e-api 17/17 + e2e-real-llm 10/10（自主工具调用、流式中止恢复、多模态识图、持久化往返）；秘密卫生零泄漏；Global MCP 真实 endpoint 链路未执行（集成人明示接受替代覆盖并记录偏差）；预检记录见[《v0.1 源码发布候选预检》](releases/v0.1/evidence/rc2-preflight.md) |
| 2026-08-22 | 评审后修订 ADR-002：撤销 `ModelState.scope` 方案，明确每个 `message-stop` 由 Provider 返回完整下一状态或 `null`，Runtime 只完整替换；把尚无 Provider 路由契约的“迁移”收窄为显式会话连续状态重置，增加 owner 隔离、revision 并发屏障、完整快照回传、`MODEL_STATE_EXPIRED` 明确错误与 Browser 状态同步验收要求；仍排期 R2 |
| 2026-08-22 | 新增 R1“Java 后端单次模型调用 API”及专项设计：同 JVM 单次 Provider 调用，只保存调用期内存状态；默认不写 Conversation、Audit、数据库或调用历史，宿主自行决定是否保存；原 R1～R6 顺延为 R2～R7 |
| 2026-08-22 | 初次采纳 ADR-002 的动机：为 Provider 状态生命周期和供应商切换建立显式契约；原 `scope` 与“迁移到目标 Provider”方案已被同日后续评审修订，不再是当前决策 |
| 2026-08-22 | 二次审计 Q-02～Q-10 全部修复并复验：Call Trace 显式 opt-in、Tool 参数 Schema 校验、MCP JSON-RPC 严格解析、不可变对象防御复制、MCP 配置 O(1) generation 版本、Browser 响应校验与 Widget 转义、审计百分位最近窗口取样、模型流取消/转发串行化、OpenAI Chat 协议内核提取（新模块 `patchbridge-agent-model-openai`）；全 Reactor 173 tests、Web 144 tests、四 Bundle 字节一致（含产物重建 3504a9f）、Demo HTTP smoke、SCAN 源级零命中；审计报告 §6 十项验收勾选，在包含本结论的提交上创建 `v0.1.0-rc.2` |
| 2026-08-22 | 二次代码质量审计确认 4 个 P1 与 6 个 P2，新增《v0.1 源码发布收口代码质量审计》；R0 从已完成恢复为整改中，`v0.1.0-rc.1` 保留为历史候选，待问题闭环与全量复验后创建新候选 |
| 2026-08-22 | R0 全部子任务完成并冻结候选：在 `a6efdc2` 打 annotated tag `v0.1.0-rc.1`（绑定发布归档 SHA-256），main 快进至该提交；本次将开源准备快照同步为 100%、§3.5 收口项转 ✅，并清掉正文残留的“进行中”表述；真实模型密钥由宿主事后吊销 |
| 2026-08-22 | deepin 容器异地复验补跑通过（候选 `09e598d`：Node 容器 131/131 + 四 bundle CMP_OK，Maven 容器 159 tests BUILD SUCCESS）；过程澄清 Web 测试须在完整仓库检出内运行（装配契约测试跨树读 java 资源） |
| 2026-08-22 | DEMO-03 补跑通过并冻结候选：宿主批准真实网关配置经环境变量注入（值不入证据，事后吊销）；e2e-api 17/17、真实 LLM 全链路 10/10（自主工具调用、流式中止恢复、多模态识图、持久化往返）；首次 400 定位为预检脚本 yml 跨行取值问题，产品无缺陷；FINAL-01 满足 |
| 2026-08-22 | 完成 v0.1 源码发布候选预检并落档：候选 `09e598d`，GIT/JAVA/WEB/BUNDLE/JAR/ARCHIVE/LICENSE/META/SCAN/DOC/ARCH/SECURITY 全过（Java 8 隔离仓库 159 tests、Node 22.12.0 Web 131 tests、归档复建、Demo HTTP smoke、gitleaks 双模式零 finding）；DEMO-03 真实模型链路待批准配置注入，补跑前不创建 tag；deepin 容器复验因 SSH 密钥代理锁定暂缓 |
| 2026-08-22 | 预检执行：修订清单 SCAN-01（.gitleaksignore 历史指纹例外）与 ARCH-04（浏览器 bundle 资源例外）的扫描范围并写明理由；诊断测试哨兵的 gitleaks:allow 注释移至同一行以同时覆盖 git/dir 两种扫描模式；Java 8 隔离仓库全 reactor 159 tests、Node 22.12.0 干净克隆 Web 131 tests 与四 Bundle 一致性、JAR/LICENSE/META/SCAN gate 通过 |
| 2026-08-22 | R0 收口合并三支修复：独占 base-path 契约（启动期映射所有权校验 + 405/406/415 统一信封 + 空白 userId 401 + Widget 动态属性重绘）、启动配置诊断（PBA-CFG-001/002/003 集中编号 WARN，自定义 Bean 让位，配置值不入日志）、用户手册（14 节，仅当前源码事实）；合并后 Java 159 tests、Web 131 tests、四 Bundle 字节一致 |
| 2026-08-22 | 修复 Call Trace R0 审查问题：以 message-stop 封闭 Runtime/HttpModel 并非阻塞释放 SSE reader，迟到取消/错误不推翻稳定结果；移除跨时钟容差比较，补齐实时/恢复 Execution 记录唯一性与 Tool 引用校验；Agent 86 tests、Call Trace 19 tests、Agent/Widget/Call Trace 构建、两个 Bundle 字节/语法 gate 与 diff check 通过 |
| 2026-08-22 | Call Trace 模型行新增首 token 延迟与首 token 后平均输出 tok/s：Runtime 内部采样首个非空内容增量，完成事件只发布最终计时；本地 schema v2、深度校验、展示/详情、单测与桌面/移动端 Demo 验收同步更新 |
| 2026-08-22 | 将 R0 审计提交与 PatchBridge Agent 重命名、Browser Call Trace 合并并复验：Java 135 tests、Web 115 tests、五包构建、四 Bundle 字节一致性与 Demo HTTP smoke 全部通过，旧品牌入口保持删除 |
| 2026-08-22 | 完成 R0 公开契约与安全默认值最终审计：修复严格请求、配置早失败、MCP 开关、模型错误脱敏、SSE 重连、CI/Bundle 和文档事实问题；审计分支 Java、Web、Bundle 与 Demo smoke 通过 |
| 2026-08-22 | 完成 Browser Call Trace：生命周期/usage 采集、稳定消息内容补全、每会话 localStorage 保留与深度校验、账本/详情/存储说明组件、Demo 页签、主题适配、关键测试和使用文档 |
| 2026-08-22 | 完成 PatchBridge Agent（破补丁 Agent）全仓品牌/技术命名迁移（无兼容层）：Java/Web 代码与文档、CI 路径、配置与环境变量、组件/事件/CSS 命名全部切换，并无损整合 Browser Call Trace；Java 全 reactor 99 个测试、Web 113 个测试与五包构建、四个 bundle 比对、旧品牌残留和路径复核全部通过；实际 Java 8 CI 与真实 Demo 发布验收继续由 R0 收口 |
| 2026-08-21 | 启动 R0 公开契约与安全默认值最终审计，明确范围与完成条件 |
| 2026-08-21 | 建立统一路线图；按当前代码和已确认决策整理 v0.1、后续顺序、暂缓项与非目标 |

## 相关文档

- [开源文档信息架构与中文写作体系重构方案](architecture/designs/documentation-system.md)
- [ADR-003：Browser Agent Runtime 生产级执行守卫](architecture/adr/0003-browser-runtime-guards.md)
- [ADR-004：完整聊天历史与模型工作上下文分离的压缩机制](architecture/adr/0004-context-compaction.md)
- [使用上下文压缩](guides/context-compaction.md)
- [Java 后端单次模型调用 API 设计](architecture/designs/java-model-gateway.md)
- [v0.1 源码发布收口代码质量审计](releases/v0.1/audits/source-quality.md)
- [v0.1 公开契约与安全默认值审计](releases/v0.1/audits/public-contract-and-security.md)
- [v0.1 源码发布候选预检](releases/v0.1/evidence/rc2-preflight.md)
- [文档中心](README.md)
- [v0.1 版本说明](releases/v0.1/release-notes.md)
- [v0.1 发布检查清单](releases/v0.1/release-checklist.md)
- [架构设计：模块化单体、六边形架构与设计模式](architecture/overview.md)
- [ADR-001：厂商中立 Agent Runtime 核心契约](architecture/adr/0001-provider-neutral-runtime.md)
- [ADR-002：ModelState 生命周期所有权与显式会话连续状态重置](architecture/adr/0002-model-state-lifecycle.md)
- [reasoning_content 回传规则调研报告](research/reasoning-content.md)
- [原始完整设计方案](archive/original-design.md)
