# v0.1 源码发布收口代码质量审计

- 审计日期：2026-08-22
- 审计对象：`main` 分支 `e3cf5a2` 与候选标签 `v0.1.0-rc.1`
- 文档状态：整改完成（Q-02～Q-10 已在分支 `codex/v0.1-r0-quality-fix` 修复并全量复验，见 §6）
- 发布结论：本报告全部发布阻断项与质量问题已闭环；修复链最终门禁 Java 8 全 Reactor 173 tests、Web 5 包 144 tests、四 Bundle 字节一致、Demo HTTP smoke 通过；在包含本结论的提交上创建新候选 `v0.1.0-rc.2`，`v0.1.0-rc.1` 保留为历史候选

## 1. 结论

PatchBridge Agent 已经具备清晰的六边形边界、完整的 Browser Agent Runtime 主干、严格的
会话隔离与错误契约，以及明显高于普通 Demo 的测试和文档基础。独立复验中 Java 8 全部
8 个 Maven 模块共 159 个测试通过，Web 5 个包共 131 个测试通过，4 个 Starter 内置
Bundle 构建后无差异，npm 依赖审计与本地文档链接检查也没有发现错误。

测试全绿不能替代契约、安全默认值和并发语义审计。本轮仍确认了 4 个 P1 发布阻断项和
6 个 P2 质量问题，因此 `v0.1.0-rc.1` 应保留为历史候选，不应作为最终源码发布候选。
修复后应创建新的候选标签，不移动或覆盖已有标签。

## 2. 分级规则

| 等级 | 含义 | R0 处理要求 |
| --- | --- | --- |
| P1 | 会破坏安全默认值、公开契约或发布物一致性的高优先级问题 | 全部修复并补齐关键测试后才能重新冻结候选 |
| P2 | 不一定阻断基础链路，但会影响正确性、并发能力或长期维护 | R0 重新完成前修复；若确需延期，必须形成明确设计决策并同步路线图 |

## 3. P1 发布阻断项

### Q-01 候选标签内的唯一路线图仍显示 R0 未完成

**现状**

- `v0.1.0-rc.1` 指向提交 `a6efdc2`；
- 将 R0 改为 100% 和“已完成”的路线图提交是其后的 `e3cf5a2`；
- 因此从 `v0.1.0-rc.1` 下载的源码中，`docs/路线图与当前进度.md` 仍将当前里程碑描述为
  “候选预检与冻结”，并把源码开源准备标记为约 95%。

**影响**

项目规定路线图是实施状态的唯一可信来源。候选源码包中的路线图与标签含义冲突，会让
发布归档、验收证据和项目状态无法自洽。

**处理要求**

已有标签保持不可变。完成本报告整改后，在包含最终路线图和审计结果的提交上创建新的
候选标签，例如 `v0.1.0-rc.2`，并重新记录归档摘要。

### Q-02 Call Trace 视图可选，但采集和 localStorage 持久化默认启用

**证据**

- `web/packages/agent/src/factory.ts` 无条件创建 `CallTraceStore`，并把它追加到默认 Runtime Hook；
- `web/packages/agent/src/callTrace.ts` 会记录用户输入、模型正文与思考、Tool 参数和结果；
- 终态轨迹默认写入当前 origin 的 `localStorage`；
- 不加载独立 Call Trace 组件只能移除视图，不能停止上述采集与持久化。

**影响**

生产宿主即使完全移除调试 UI，仍会在浏览器长期保存可能包含个人信息、业务参数、模型
思考和 Tool 结果的数据。这不符合“调试能力可任意插拔、生产可丢弃”的设计，也不是合适
的企业框架安全默认值。

**处理要求**

- Call Trace 采集和持久化必须显式 opt-in；
- 未开启时不得创建采集 Hook，也不得访问 `localStorage`；
- 需要分别表达“仅内存采集”和“允许持久化”，不能用一个隐式默认值混合两个决策；
- Demo 可以显式开启，生产 Quickstart 应保持关闭；
- 增加默认关闭、仅内存、持久化、释放后不再采集的测试与使用说明。

### Q-03 纯前端 Tool 的 inputSchema 没有在执行边界生效

**证据**

- `web/packages/agent/src/toolRegistry.ts` 的公共注释承诺模型只能生成 `inputSchema` 声明的参数；
- Registry 注册时只检查 Schema 是否为对象，没有编译或验证 Schema；
- 快照调用时直接把模型生成的参数传入页面 `execute()`；
- `web/packages/agent/test/toolRegistry.test.ts` 当前会在 `additionalProperties: false` 的 Schema 下，
  仍用额外的 `section` 字段成功执行 Tool。

**影响**

LLM 输出属于不可信输入。缺字段、错误类型和额外字段都可以进入页面已有 Service 或写操作，
公共契约与真实行为不一致，Tool 作者也容易误以为 Registry 已经完成参数保护。

**处理要求**

- 在统一 Tool 执行边界校验参数，而不是要求每个 View 或 Tool 重复处理；
- 明确支持的 JSON Schema 版本和关键字集合；
- Schema 本身不受支持时在注册阶段失败，调用参数不符合时在执行前失败；
- 参数失败应形成明确 Tool 失败，绝不能进入 `execute()`；
- 增加必填字段、类型、枚举、数组、嵌套对象、`additionalProperties` 和非法 Schema 测试。

### Q-04 MCP 客户端会把非法 JSON-RPC 或结果结构解释为空成功

**证据**

`java/patchbridge-agent-mcp/src/main/java/io/patchbridge/agent/mcp/StreamableHttpMcpClient.java`
当前存在以下行为：

- `parseJsonRpcResponse` 没有验证 `jsonrpc == "2.0"`；
- 传入的请求 `id` 没有参与 JSON 响应校验；
- 没有校验 `result` 与 `error` 的必需性和互斥性；
- `tools/list` 缺少 `tools` 时被解释为空列表，缺少名称时产生空名称；
- `tools/call` 缺少 `content` 时被解释为空的成功结果；
- 现有名为 `jsonRpcErrorBecomesMcpException` 的测试没有实际返回 JSON-RPC error。

**影响**

远端协议损坏、网关错配或错误响应可能被伪装成“没有 Tool”或“调用成功但结果为空”，模型
会基于虚假事实继续推理，Admin 也无法准确判断失败原因。

**处理要求**

- 严格校验 JSON-RPC envelope、请求 ID、error 结构和 result 结构；
- `tools/list` 与 `tools/call` 分别校验所有必需字段及字段类型；
- 多行 SSE `data` 必须遵循 SSE 连接语义；
- 增加错误 ID、缺少 result、result/error 同时存在、非法 tools/content、合法 JSON-RPC
  error、非法 SSE 与空响应测试；
- 继续锁定 MCP `2026-07-28` 无状态协议，不增加旧协议 fallback。

## 4. P2 质量问题

### Q-05 领域对象的不可变承诺未完全兑现

`UserContext` 会复制 roles 和 permissions，但只对调用方提供的 attributes Map 添加只读包装，
没有防御性复制。调用方可以在 `build()` 后修改原 Map，进而改变已经发布的可信用户上下文。
`ToolCallResult` 对 content List 也存在同类问题。

处理要求：构造阶段统一完成防御性复制，并增加“修改原集合不影响已构造对象”的测试。

### Q-06 JDBC MCP 配置版本检测位于每次 Tool 请求的数据库全量路径

`McpToolRegistry.list()` 与 `call()` 都会先同步配置；JDBC Store 的 `version()` 会读取、遍历并
哈希全部 Global MCP 配置行，包括可能较大的凭据密文和 Tool 配置 JSON。

这使稳态 Tool 列表和每次 Tool 调用都产生数据库全表读取，与项目的高并发目标冲突。

处理要求：使用独立、单行、单调递增的配置 generation 或等价的 O(1) 版本事实；配置变更
与 generation 更新必须属于同一事务，多实例读取不得依赖时间戳碰撞或进程内事件。

### Q-07 Browser HTTP 响应缺少运行时校验，Widget 属性插值不完整转义

`HttpConversationClient` 和 `HttpToolClient` 主要依赖 TypeScript 类型断言；会话列表只检查
顶层字段是否为数组，没有验证每个对象及嵌套 Context。Widget 转义了会话标题，却把
`conversationId` 原样插入 `data-*` 属性。

默认 JDBC 生成的 ID 是安全 UUID，但框架允许替换 Client，代理错误、服务端版本错配或污染
数据都可能导致状态异常，恶意 ID 还可能形成 DOM 注入。

处理要求：在 HTTP Adapter 边界深度校验公共响应；所有进入 HTML 字符串的动态属性统一转义，
优先使用 DOM API 和 `textContent`/`dataset`，并增加恶意字符串回归测试。

### Q-08 审计 P50/P95 在超过 10,000 条数据后统计错误

`JdbcAuditQueryRepository.stats()` 按 `duration_ms` 升序取前 10,000 条，再计算百分位。这不是
有界代表性样本，而是全量数据中最快的 10,000 条，数据量超过上限后 P50/P95 会系统性偏低。

处理要求：明确统计窗口并在数据库侧计算，或采用不按耗时偏置的有界取样；返回值和 Admin
说明必须表达真实统计范围。增加超过样本上限且包含慢调用的数据测试。

### Q-09 Java 模型流取消与事件转发之间存在 check-then-act 竞态

`ModelInvocationPipeline.TerminalListener.onEvent()` 与 `ModelStreamController.StreamSession.onEvent()`
先读取 `terminated`，再调用下游。在两步之间并发取消时，仍可能发送一条取消后的迟到事件。
AtomicBoolean 只保证唯一终止者，没有让“事件发布与终止”形成线性化边界。

处理要求：用统一串行化边界或锁保护事件发布与终止切换，并增加可控栅栏下的真实并发测试，
验证取消后没有新事件、after 只执行一次、Provider cancel 仍保持幂等。

### Q-10 OpenAI Chat 协议在 OkHttp 与 WebFlux Adapter 中重复实现

以下两个文件合计约 1,200 行，包含基本相同的消息编码、reasoning 状态、Tool Call 和流式
解码规则：

- `java/patchbridge-agent-spring-boot2-starter/.../model/OpenAiChatProtocol.java`
- `java/patchbridge-agent-model-webflux/.../OpenAiChatProtocol.java`

传输差异不应复制厂商协议。当前结构会要求每个协议修复在两处同步实现和测试，未来增加
Responses 等 Provider 后更容易产生行为漂移。

处理要求：提取独立的 OpenAI Chat 协议 Adapter 模块或共享协议内核；OkHttp 与 WebFlux
只保留请求发送、SSE 字节流和取消职责。提取时不允许让 Core 依赖 Jackson、Spring 或传输库。

## 5. 推荐整改顺序

1. Q-02：Call Trace 改为显式启用，先收紧安全默认值；
2. Q-03：统一验证纯前端 Tool 参数；
3. Q-04：严格解析 MCP envelope 与结果结构；
4. Q-05：修复不可变领域对象；
5. Q-06：把 MCP 配置版本检查改为 O(1)；
6. Q-07：补齐 Browser HTTP 响应验证和 DOM 安全；
7. Q-08、Q-09：修复统计正确性与取消竞态；
8. Q-10：收敛重复协议代码；
9. 重新执行 Java 8、Web、Bundle、归档、Demo 和真实外部服务门禁；
10. 更新本报告与路线图，在新提交上创建新的不可变候选标签，关闭 Q-01。

## 6. 重新通过 R0 的验收条件

- [x] Q-01～Q-10 均有实现修复、关键测试和必要文档（Q-02 `a0f2d5e`、Q-03 `2fda35a`、Q-04 `7f0d327`、Q-05 `0a5c2d3`、Q-06 `87dc103`、Q-07 `8ecf8b0`、Q-08 `031c3e4`、Q-09 `df1f177`、Q-10 `964b04d` + Bundle 重建 `3504a9f`；无延期项）；
- [x] Call Trace 默认不采集、不持久化，Demo 显式开启并展示用法（`a0f2d5e`：默认 `off`，Widget `call-trace` 属性 opt-in，Demo `persistent`）；
- [x] 纯前端 Tool 的非法参数在进入 `execute()` 前被拒绝（`2fda35a`：注册期 JSON Schema 子集编译 + 执行前校验，8 类非法参数测试矩阵）；
- [x] MCP 非法 envelope、错误 ID 和缺失必需字段均明确失败（`7f0d327`：jsonrpc/id/result-error 互斥严格解析 + tools/content 形状反例测试）；
- [x] 多实例 MCP 配置观察不再让每次 Tool 调用扫描完整配置表（`87dc103`：单行 generation 同事务递增，O(1) 版本读取）；
- [x] Java 8 全 Reactor、Web 全包、四个 Bundle 一致性全部通过（8 模块 173 tests、5 包 144 tests、四 Bundle `cmp` 为 0，含 Q-02/Q-03/Q-07 产物重建 `3504a9f`）；
- [x] Demo 基础链路、真实模型链路与真实 MCP 链路通过（基础链路：启动、/login 200、未认证 401、H2 schema 自动迁移含新版本表；真实模型/MCP 链路属集成人最终预检项，密钥由宿主事后吊销，须按检查清单在新候选上补跑）；
- [x] Git 归档不包含密钥、本地配置、Mock Server、构建目录和内部环境依赖（SCAN-01/02/04 源级扫描零命中；gitleaks 双模式正式扫描由集成人在冻结候选上执行）；
- [x] 路线图、版本说明、发布检查清单与新候选标签内容一致（同一次收口提交内同步）；
- [x] 新候选标签指向包含最终审计结论的提交，已有 `v0.1.0-rc.1` 不被移动（`v0.1.0-rc.2` 创建于本结论所在提交，`rc.1` 仍指向 `a6efdc2`）。

## 7. 本轮独立验证记录

| 项目 | 结果 |
| --- | --- |
| Java 8 Maven Reactor | PASS，8/8 模块，159 tests |
| Web tests | PASS，5/5 packages，131 tests |
| Web build | PASS |
| Starter 内置 Bundle 一致性 | PASS，4 个 Bundle 无差异 |
| npm audit | PASS，0 vulnerabilities |
| 本地 Markdown 链接 | PASS，0 errors |
| Git 发布归档卫生 | PASS，未发现 target、dist、node_modules、本地配置、数据库或 Mock Server |

本轮是只读代码质量审计。除本报告与路线图状态同步外，没有修改生产代码；工作区中用户
原有的暂存文件 `pack.sh` 不属于审计对象，也未被修改。
