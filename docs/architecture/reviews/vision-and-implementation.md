# 项目愿景、实现一致性与方向修正审查

- 文档类型：Architecture Review（架构审查）
- 审查日期：2026-09-05
- 维护者决策确认日期：2026-09-05
- 代码基线：`d2a787b0abffc686bd771dee01f29da5fb872ed9`
- 文档基线：审查时工作区中的现行文档，包含尚未提交的 R1.8 技术方案与 Proposed ADR-005
- 结论属性：记录代码事实、边界复现与修正建议；第 7、9、10、11 节注明维护者已确认的行为和方向；不替代 Accepted ADR，不代表已经完成修复或批准全部实现细节
- 实施状态：只在[路线图](../../roadmap.md)维护；本文保留上述基线的审查事实
- 变更边界：本次只记录审查并校正文档状态，没有修改运行时代码、HTTP 契约或数据库 Schema

## 1. 审查结论

项目的主要架构方向成立，现有主干应当保留。Browser Runtime、Java Core、模型协议
Adapter、统一 Tool 目录和 Headless Controller 都有真实实现，不能把当前项目判断为仅有
接口或聊天 Demo 的原型。

需要修正的地方集中在两个层面：

1. **部分架构承诺没有贯穿完整调用链。** 单个模块具备快照、事务、取消或错误分类，不等于
   多个模块连接后仍然保持相同语义。本次已复现五处缺口，涉及 MCP 路由、首轮会话保存、
   取消后的下一轮上下文、Java Tool 异常分类和压缩后的窗口检查。
2. **后续建设有偏重管理平台的风险。** R1.8 中统一模型目标与 Router 的动机合理，但将其与
   完整 JDBC/Admin 管理、凭据生命周期和多种 revision 一起作为后续协议验证的前置条件，
   会增加复杂性和接入负担。需要重新检验这些依赖是否服务于“存量系统低侵入集成”。

维护者已确认：下一阶段先修复五项跨层缺陷，再验证真实宿主接入，并优先用最小目标路由与
第二种真实模型协议检验扩展边界；完整模型管理后台按实际需求另行决定，不作为协议验证的
前置条件。真实宿主项目暂未选定，以后再讨论；详细决策见第 11 节。

现有证据不支持推倒重写，也不支持为了“生产级”引入后端 Agent Loop、通用 Workflow 或
分布式执行恢复。

本报告没有把尚未实现的 Anthropic、Responses、OpenAPI Adapter 或公共包发布直接判为
架构错误。它们在路线图中已有明确状态；未完成、已批准的取舍、实现缺陷和待讨论的方向
建议必须分别判断。

## 2. 判断依据与审查范围

### 2.1 用什么愿景判断代码

判断基准来自[项目简介](../../../README.md)、[原始设计](../../archive/original-design.md)的
定位与边界、[架构总览](../overview.md)、Accepted ADR 和[路线图](../../roadmap.md)：

> Browser 运行 Agent，Server 运行安全边界与业务，LLM 负责推理。

这句话在本项目中对应五项实际要求：

| 要求 | 应当观察到的事实 |
| --- | --- |
| 后端无 Agent Runtime 状态 | Agent Loop、人工确认等待与执行进度在 Browser；Server 只保存业务数据和请求期资源 |
| 低侵入接入 | 宿主能够复用现有 Service、页面函数、认证、权限、HTTP 请求链和部署方式 |
| 可替换边界 | 模型协议、存储、权限和 UI 的替换通过窄 Port 完成，领域流程不跟随外部技术扩散 |
| 统一语义 | 模型看到的能力、实际执行、消息状态、持久化与调试视图能够相互对应 |
| 可验证执行 | 取消、失败、配置变化和并发交错具有明确结果，不能只验证正常完成路径 |

“后端无 Agent Runtime 状态”不表示不需要数据库，也不表示模型 SSE 请求不占连接、线程或
内存。本次没有容量压测，不能从“没有长期 Agent 实例”推导出“任意在线用户规模均已验证”。

### 2.2 本次实际阅读与核对的内容

- 定位与演进：README、原始设计中的目标与边界、现行路线图。
- 架构与决策：架构总览、ADR-001 至 ADR-005、Java ModelGateway 设计、R1.8 目标管理方案
  中的组件、配置归属、依赖顺序与待确认决策。
- 使用契约：Spring Boot、Browser、Java/前端 Tool、Global MCP、上下文压缩、Java 模型调用、
  Call Trace、生产接入指南及相关 Runtime/HTTP/配置参考。
- Browser 主链路：工厂装配、Controller、状态机、Runtime、ContextManager、Tool Registry、
  Model/Tool/Conversation Client 与只读视图接线。
- Java 主链路：Starter 自动装配、Model/Tool Controller 与 Pipeline、注解 Tool Adapter、
  OpenAI 协议内核、MCP Registry、Conversation JDBC Repository。
- 验证：现有 Java/Web 测试与针对五处边界的额外最小复现。

这是一轮围绕产品方向和核心不变量的审查，不是对每个资源、每个 UI 分支或所有第三方协议
的逐行认证。没有执行真实外部模型/MCP 联调、真实存量宿主接入或容量测试。

### 2.3 事实、推论与建议的区分

| 类型 | 本报告如何使用 |
| --- | --- |
| 源码事实 | 给出文件、方法和基线行号，可以直接检查当前调用关系 |
| 复现事实 | 使用真实框架实现，在外部依赖处注入可控桩，记录实际结果 |
| 影响判断 | 解释该行为如何破坏既定边界；未实际触发的损失只描述为风险 |
| 修正建议 | 给出职责归属和验收目标，不把尚未讨论的字段、策略或新模块写成已接受契约 |
| 未验证事项 | 明示真实协议、性能或宿主环境仍需验证，不以桩结果代替产品验收 |

后文源码链接指向仓库文件，行号是上述 commit 的定位辅助；代码修复后应通过方法名和 Git
基线回溯，不应改写本次复现的历史结果。

## 3. 已经实现的正确方向

### 3.1 Browser 与 Server 的主职责没有颠倒

[DefaultAgentRuntime](../../../web/packages/agent/src/runtime.ts)负责模型与 Tool 循环、独立
Execution、确认、Deadline 和取消。服务端的
[ModelInvocationPipeline](../../../java/patchbridge-agent-core/src/main/java/io/patchbridge/agent/core/invocation/ModelInvocationPipeline.java)
与 Tool Pipeline 处理单次调用，没有接管跨请求的 Agent 步骤。

[DefaultModelGateway](../../../java/patchbridge-agent-core/src/main/java/io/patchbridge/agent/core/invocation/DefaultModelGateway.java)
为 Java Service 提供单次模型调用。它复用 Model Pipeline，不执行 Tool Loop，也没有默认
Conversation 持久化。因此 Java 门面的增加本身没有违反浏览器 Agent 的定位。

### 3.2 Core 与外部技术的依赖方向基本正确

[Java Core POM](../../../java/patchbridge-agent-core/pom.xml)的生产依赖保持在内部 annotations
模块；Spring、HTTP、JDBC 和模型协议位于 Adapter 侧。
[OpenAiChatProtocol](../../../java/patchbridge-agent-model-openai/src/main/java/io/patchbridge/agent/model/openai/OpenAiChatProtocol.java)
集中处理 OpenAI Chat 编码、流解码和 reasoning 状态，OkHttp 与 WebFlux 复用同一协议内核。

这已经解决了“Browser Runtime 到处出现厂商字段”的原始问题。但两个传输 Adapter 共享一种
协议，不等于第二种模型协议已经验证。当前能确认的是协议隔离的结构基础成立。

### 3.3 Tool 和 UI 扩展有实际接入路径

[Browser Tool Registry](../../../web/packages/agent/src/toolRegistry.ts)能够接入页面函数和
后端工具；[WebMCP Adapter](../../../web/packages/webmcp-adapter/src/index.ts)位于独立包。
Headless Controller 暴露用户意图与状态订阅，Widget、Inspector 和 Call Trace 使用相应端口。

因此，“不用默认 Widget”和“页面现有函数直接成为 Tool”均有代码支撑。需要修正的是某些
跨边界承诺，尤其是后端动态工具的整轮一致性，而不是删除统一 Registry。

### 3.4 安全策略保留给宿主的总体分工合理

身份、Tool 权限、会话 owner 和 Admin 权限都具备宿主 Port。后端工具调用重新鉴权，JDBC
会话操作携带 owner 约束，符合企业原有业务安全边界应继续生效的原则。

“提供 Port”与“自动复用所有企业权限规则”仍有区别。例如默认 ToolAccessPolicy 只要求
认证，多租户 owner 与业务 ACL 仍需宿主明确实现。当前
[生产接入指南](../../guides/production-readiness.md)已说明这一点，不能把它误报成隐藏缺陷，
也不能在对外接入承诺中省略这些工作。

### 3.5 已接受的范围收敛应保留

自研 Runtime 取代早期 Strands 路线已在路线图中明确记录。当前也明确不支持页面关闭后继续
执行、恢复执行到一半的 Runtime、通用 Workflow 或 Multi-Agent Runtime。

这些取舍符合轻量企业 Web 集成定位。本次发现的问题可以在现有职责边界内解决，没有证明
项目需要转成服务端 Agent 平台。

## 4. 已确认问题总览

以下编号标识本次固定审查事实；修复进度由路线图中的同名条目维护。

| 编号 | 问题 | 被破坏或尚未闭合的边界 | 复现方式 |
| --- | --- | --- | --- |
| VA-01 | 后端动态 Tool 的定义与执行目标可能跨版本 | 整轮 Tool 快照一致性 | 实际 Browser Adapter 与 Java MCP Registry，远端 Client 为桩 |
| VA-02 | 首轮保存可能组合 A 消息与 B 模型状态 | 完整 ConversationContext 快照 | 实际 Controller，可控会话创建 Promise |
| VA-03 | 取消工具确认后，下一轮带入未配对 Tool Call | Execution 终态到可继续会话的转换 | 实际 Controller、Runtime、Registry，可控结构化模型流 |
| VA-04 | Java Tool 将未知方法异常统一转为业务失败 | 业务失败与未预期失败的分类 | 实际注解 Adapter，Tool 抛 NullPointerException |
| VA-05 | 压缩后的输入仍超过自身估算窗口却被放行 | 模型输入的最终预算检查 | 实际 ContextManager，固定窗口与摘要 Gateway 桩 |

建议先处理 VA-01、VA-02、VA-04 的目标一致性、数据组合与错误分类，并在扩大 Provider
能力前完成 VA-03、VA-05。这个顺序依据影响与依赖关系，不代表后两项可以不修。

## 5. VA-01：Tool 快照没有贯穿后端动态路由

### 5.1 原有承诺

[ADR-001](../adr/0001-provider-neutral-runtime.md)和 Runtime Reference 要求模型定义、实际
调用路由与 Inspector 使用同一个执行快照。执行期间发生的变化不能悄悄改变本轮工具含义。

### 5.2 代码事实

| 位置 | 基线行为 |
| --- | --- |
| [toolRegistry.ts](../../../web/packages/agent/src/toolRegistry.ts)，`BackendToolProvider.load()`，145–152 行 | 复制 ToolDefinition；执行闭包只用 `definition.name` 调用 ToolClient |
| [toolClient.ts](../../../web/packages/agent/src/clients/toolClient.ts)，`HttpToolClient.call()` | 请求带工具名、参数和链路字段，没有服务端定义/路由版本 |
| [DefaultToolRegistry.java](../../../java/patchbridge-agent-core/src/main/java/io/patchbridge/agent/core/tool/DefaultToolRegistry.java)，`call()` | 调用时查找当前定义、重新鉴权并路由 Provider |
| [McpToolRegistry.java](../../../java/patchbridge-agent-mcp/src/main/java/io/patchbridge/agent/mcp/McpToolRegistry.java)，`call()`，145–160 行 | 同步配置后读取当前 ServerState，再调用其 endpoint 与凭据 |

Browser 冻结的是本地闭包；闭包没有冻结它所代表的服务端能力版本。MCP 的不可变
ServerState 能保证一次已开始的服务端调用内部配置完整，但不能固定未来另一个 HTTP 请求
使用哪份 ServerState。这是两个不同的生命周期。

### 5.3 最小复现与观察结果

1. 为同一 MCP server key 配置 A 地址，用远端 Client 桩返回名为 `action` 的工具。
2. 经实际 Java `DefaultToolRegistry.list()` 取得 `mcp.global.action` 的旧定义。
3. 调用实际 `McpToolRegistry.replaceAll()`，将同一 key 更新为 B 地址。
4. 使用旧定义的名称，经实际聚合 Registry 发起调用；Client 桩返回它实际收到的配置地址。

```text
discovered=https://server-a.example/mcp
called=https://server-b.example/mcp
```

Browser 侧也验证了旧快照仍显示 A 描述，但 BackendToolProvider 的执行闭包调用到已变化的
后端目标。示例地址均为占位符，复现没有请求真实外部服务。

### 5.4 影响与修正方向

模型依据旧描述与 Schema 作出的调用，可能落到新目标；用户确认时看到的含义也可能已经
过期。重新鉴权仍然必要，但它不能替代版本一致性检查。

建议为后端动态 Tool 建立服务端认可的定义/路由版本引用，并贯穿发现与调用。Server 在
调用时同时确认版本与当前授权；版本发生语义变化时明确失败。具体字段、版本粒度及错误码
应在实施设计中确定，不在本审查中创造另一套协议。

不要求 Server 持有整个 Browser Execution，也不能因为版本被冻结而忽略工具停用或权限
撤销。远端 MCP 自身是否支持固定工具实现版本，是额外边界，不能由本地配置版本冒充保证。

### 5.5 建议验收条件

- 定义取得后更新同名 MCP 的 endpoint、Schema 或影响执行语义的配置，旧调用不能执行新目标。
- 无配置变化时，模型、Inspector 和实际执行引用能够关联到同一版本。
- 调用时权限撤销、工具停用仍然生效；版本引用不能成为免检凭证。
- 在两个应用实例之间完成“发现、配置更新、调用”的测试，不依赖单节点内存快照。
- HTTP、Browser Adapter 与 Java Registry 的组合测试验证上述行为，不能只测试本地函数闭包。

## 6. VA-02：首轮保存存在跨会话状态组合

### 6.1 原有承诺与代码事实

ConversationContext 要求完整消息与 ModelContext 同属一个快照、revision 和持久化事务。
JDBC 的原子写入只能保证收到的数据一起保存，不能证明 Browser 组装的数据原本属于同一轮。

[controller.ts](../../../web/packages/agent/src/controller.ts)的 `persistRound()`，604–628 行：

1. 先复制 `this.state.messages`，并固定当前 conversation。
2. 首轮没有 conversation 时，等待 `conversations.create()`。
3. 创建返回后，用之前复制的 messages 和此时的 `this.state.modelContext` 组装保存体。
4. `isCurrentRun()` 检查位于 `save()` 之后。

因此，等待创建期间的会话导航能改变第 3 步所读的 ModelContext，却不能改变先前复制的消息。

### 6.2 最小复现与观察结果

1. 实际 Controller 在空白会话 A 中发送消息，可控 Engine 返回 A 的回答和 `state-A`。
2. 让 ConversationClient 的 `create()` 保持挂起，确认流程已进入首次持久化。
3. 调用 Controller 的 `loadConversation('B')`，加载 B 的消息和 `state-B`。
4. 释放 A 的 `create()`，检查随后提交给 `save()` 的完整请求体。

```json
{
  "savedTo": "A",
  "savedMessages": ["A question", "A answer"],
  "savedModelState": {"format": "state-B", "data": {"origin": "state-B"}},
  "visibleConversation": "B"
}
```

这次复现截获的是实际 Controller 发出的错误保存体，没有把损坏数据写进真实数据库。
若 B 的 usage 或 checkpoint 引用了 A 消息中不存在的 ID，下游校验还可能拒绝请求；它不会
自动恢复 A 应有的 ModelContext。

### 6.3 影响与修正方向

界面仍保持 B，不代表旧保存没有副作用。当前 generation 屏障守住了部分 UI 提交，却没有
覆盖首次创建之后的写请求。这是应用层快照所有权问题，单纯增加数据库事务无法解决。

应在首次异步操作前固定整个保存命令，包括 ConversationContext、目标和 revision；之后
不再读取可被导航修改的 Controller 状态。新的写请求开始前也应检查所属执行是否仍有效。

首轮“创建空会话再保存内容”还应单独检查失败留下空记录的处理边界。R1.8 已提出首次完整
创建，但本问题不应等待整个模型管理里程碑才得到修正。是否合并创建协议须通过明确设计决定。

### 6.4 建议验收条件

- A 首轮创建挂起期间加载 B，任何后续保存体都不能混用 A/B 的消息、模型状态或计量。
- 创建期间新建空会话、释放 Controller、开始其他会话操作，均覆盖相同归属检查。
- 同时检查网络写请求和最终 UI 状态，不能只断言“当前页面没有被迟到结果覆盖”。
- 保存失败、revision 冲突和首轮创建失败均有明确结果，不能留下被误标为成功的状态。
- JDBC 测试继续证明 owner/revision 隔离；Browser 测试证明进入 Repository 前的数据已经一致。

## 7. VA-03：取消后缺少可继续工作上下文的语义

### 7.1 两个不同的完成边界

模型的 `message-stop` 表示一条 Assistant 消息完整结束，其中可以包含要求执行的 Tool Call。
它不表示相应 Tool 已执行，也不表示整个工具交互已经闭合。

[runtime.ts](../../../web/packages/agent/src/runtime.ts)在 538 行保存 Assistant 消息，在
564 行发布稳定消息，之后才逐个确认和执行 Tool。等待确认时取消会停止 Execution，但已经
发布的 Assistant 消息仍留在 Controller 中。

下一次发送从当前消息构造上下文；`initializeHistoricalToolCallIds()`，843 行起，检查历史
调用 ID，不检查所有未完成工具交互如何进入下一次模型请求。

### 7.2 最小复现与观察结果

1. 使用实际 Controller、DefaultAgentRuntime 与 Registry 注册需要确认的前端 Tool。
2. 模型桩返回完整的 `tool-use` 消息，含一个 Tool Call。
3. 等待 Controller 出现 pendingConfirmation 后调用 `abort()`。
4. 第一轮结束后发送另一条用户消息，记录第二次 Model 端口收到的 messages。

```json
{
  "toolExecutions": 0,
  "secondRequest": [
    {"role": "user", "blocks": ["text"]},
    {"role": "assistant", "blocks": ["tool-call"]},
    {"role": "user", "blocks": ["text"]}
  ],
  "unansweredToolCalls": 1,
  "toolResults": 0
}
```

复现证明未配对调用被送入下一轮。没有连接真实 Provider，因此本报告不声称所有厂商必然
返回相同错误。框架缺少明确的继续语义这一事实不依赖厂商是否恰好接受该输入。

### 7.3 影响与修正方向

取消时机可能影响后续对话的可继续性。问题也需要扩展检查到工具执行失败、多个 Tool 只完成
部分，以及请求已发出但结果未知的情形。

应明确“执行历史”如何转换为“下一次模型可消费的工作上下文”。展示历史保留真实事实，
ContextManager 与 Runtime 使用同一套中止语义，不让 Widget 或 Provider 各自猜测和修补。

维护者于 2026-09-05 确认，取消后按工具的真实执行情况处理：

| 取消时的事实 | 已确认的处理规则 |
| --- | --- |
| 工具尚未执行，包括正在等待确认 | 明确记为取消，不执行原调用；后续对话可以继续 |
| 工具已执行且结果已知 | 保留真实结果，不因整轮取消而抹除已经发生的事实 |
| 请求已经发出，但实际结果未知 | 明确表达结果未知，先要求用户核实，再决定如何继续；不自动重复执行 |

部分完成的 Tool 批次按每个调用的事实分别处理，不能统一写成“没有执行”，也不能为了补齐
协议而编造成功 Tool Result。继续对话不等于恢复被取消的 Execution 或自动补执行剩余工具。

上述行为已经确认。具体消息字段、工作上下文投影和用户核实入口由实现设计确定，必须让展示
历史与下一次模型输入遵守同一规则，不要求恢复执行到一半的 Browser Runtime。

### 7.4 已确认行为的验收要求

- 确认前取消后再次发送，输入符合已确定的中止与继续契约，原 Tool 调用次数仍为零。
- 多 Tool 批次部分完成后停止，已知结果和未执行部分都得到准确表达。
- 执行中断开且结果未知时，明确要求用户核实，不伪造“未执行”或“成功”，不自动再次调用
  该副作用；不能在用户核实前把该调用当成已解决的工具交互。
- 当前页继续、切换后加载最后保存快照、失败后再次发送分别验证，不能把三者混为同一恢复路径。
- 对最终 Provider 出站消息进行契约校验；只断言 result 为 cancelled 或 UI 清除了确认框不够。

## 8. VA-04：Java Tool 的异常分类与 Runtime 契约不一致

### 8.1 原有承诺与代码事实

[ADR-003 第 6 节](../adr/0003-browser-runtime-guards.md)区分已知业务失败与未预期的协议、
基础设施或编程失败：前者显式返回 `isError=true`，后者终止 Execution。

[AnnotatedToolProvider.java](../../../java/patchbridge-agent-spring-boot2-starter/src/main/java/io/patchbridge/agent/starter/tool/AnnotatedToolProvider.java)
的 `call()`，129–132 行，对 `InvocationTargetException` 统一记录日志并返回
`ToolCallResult.ofError(SAFE_EXECUTION_ERROR)`，没有识别被包装异常是否属于已知业务失败。

### 8.2 最小复现与观察结果

1. 在实际 Spring BeanFactory 注册一个公开的 `@AiTool` 方法。
2. 方法主动抛出 `NullPointerException`，表示非预期编程错误。
3. 使用实际 AnnotatedToolProvider 扫描并调用该方法。

```text
NullPointerException returned isError=true
no exception propagated
```

这不是 Runtime 把错误吞掉，而是上游 Adapter 已经把未知异常转换成可继续的业务结果。
Browser Runtime 对 `isError=true` 的处理仍符合自己的契约。

### 8.3 影响与修正方向

相似的错误在页面 Tool、Java Tool 与 MCP Tool 中可能产生不同的执行结果。对于已经产生
部分副作用后才报错的 Java 方法，模型可能根据普通失败结果再次提出调用；本次未复现真实
业务重复操作，因此这里只记录该风险，不将其写成已发生的损失。

应统一分类规则：宿主明确识别的业务失败可以返回 ToolCallResult；未识别的方法异常应转换
成脱敏的执行异常并终止本轮。脱敏与控制流是两项独立要求，不能因为不向模型暴露异常详情，
就把所有异常当成普通业务结果。

优先复用已有结果、异常与 Interceptor 边界，不因这项修复引入通用异常框架或多层分类体系。

### 8.4 建议验收条件

- 显式 `ToolCallResult.ofError()` 仍可进入模型继续处理。
- NullPointerException 等未识别方法异常使本轮终止，终止后没有下一次模型请求。
- 三种 Tool 来源遵守相同的业务/未知异常分类原则，且错误信息保持脱敏。
- AOP、事务和方法安全仍经宿主代理执行，不为了异常转换绕开已有业务切面。
- 审计、HTTP 响应、Browser Outcome 与 Call Trace 对失败的解释一致。

## 9. VA-05：上下文压缩缺少最终窗口预算检查

### 9.1 已实现的部分与未闭合的部分

完整历史与工作上下文分离、固定 80% 触发、Tool 安全切分、当前模型摘要和 Provider 状态投影
都有实现。本问题不否定这些机制。

[contextManager.ts](../../../web/packages/agent/src/contextManager.ts)中：

- `prepareForModelCall()`，130–142 行，在一次压缩后直接返回工作消息。
- `compact()`，183–207 行，计算并保存压缩后的 estimatedTokensAfter，没有据此拒绝超窗结果。
- `createCompactionPlan()`，332–341 行，为保留最新安全段，可以保留超过近期预算的单个消息段。
- 首次没有 usage 时不会触发自动压缩；计量与准备接口没有拿到本次完整 Tool 定义和输出预算。

保留 Tool 交互原子性是正确要求，但“不能再切这个段”之后还需要决定输入无法装入窗口时
如何明确失败，不能把它当成预算检查已通过。

### 9.2 最小复现与观察结果

1. 配置窗口为 1,000、自动阈值为 800、近期预算为 200。
2. 上下文包含旧消息及一个 2,000 字符的最新文本段；旧 usage 基线为 900。
3. 摘要 Gateway 桩返回短摘要、有效 usage 与空 ModelState。
4. 调用实际 ContextManager 的 `prepareForModelCall()`，检查返回结果与后续阈值判断。

```json
{
  "window": 1000,
  "estimatedTokens": 2209,
  "returnedAsReady": true,
  "stillRequiresCompaction": true,
  "retainedTailLength": 2000
}
```

2,209 是本次复现中框架自己的估算值，不是真实 Provider tokenizer 计数，也不代表该样本
已经使真实模型溢出。已确认的问题是：按照框架当前预算判据仍超窗的结果被作为准备成功返回。

### 9.3 影响与修正方向

一次压缩完成不能证明下一次请求装得下。近期消息过大、system/Tool 定义占用较多或摘要本身
过长时，现有检查缺少统一的最终判断。

摘要请求本身也要考虑容量：
[DefaultContextCompactionProvider](../../../java/patchbridge-agent-core/src/main/java/io/patchbridge/agent/core/compaction/DefaultContextCompactionProvider.java)
把旧摘要、淘汰前缀、保留尾部和压缩指令组合后交给同一模型。正常请求接近窗口时，不能默认
认为这个摘要请求天然更小。这里是源码结构揭示的预算缺口，本次没有对真实模型验证其溢出阈值。

维护者于 2026-09-05 确认以下处理：压缩后的请求仍装不进模型窗口，或摘要请求本身超限时，
停止本次请求，明确说明超限原因，保留完整历史，由用户调整输入后重新发起。不能把压缩成功
当作可以继续调用模型的依据，也不能通过自动重复压缩、静默删除历史或更换模型继续执行。

输入预算必须覆盖工作消息、系统指令、本轮 Tool 定义，并为输出预留容量。实现上建议由统一
模型输入准备边界取得这些信息，在压缩完成后执行最终容量检查；摘要请求也遵守明确的预算。
具体计量方式与配置归属留给实现设计确定，本次确认的是超限处理行为和预算覆盖范围。

估算的适用范围需要说明。URL 图片的 URL 字符长度不等于图片的模型 token 成本，协议开销也
不完全等同于框架 JSON 长度。不能把目前 UTF-8 字节估算描述为覆盖所有 Provider/模态的数学
保证；新增协议时应验证相应计量或明确保守限制。

### 9.4 已确认行为的验收要求

- 压缩后仍超过约定输入预算时明确失败，不再调用普通模型，也不提交被误认为可用的检查点。
- 覆盖超大最新安全段、过长摘要、较大 system、较大 Tool 目录和首次大输入。
- 输出预留与输入预算来自明确目标配置，不由多个层次各自推导。
- 摘要请求本身超限时有明确行为；失败不重试、不换模型、不删除真实历史。
- 有真实模型或模型专用计量验证估算边界；文本样本的桩测试不能证明图片和所有协议正确。

## 10. 对后续方向的判断

### 10.1 低侵入已有基础，但尚缺真实宿主成本证据

前端函数注册、Java 注解、可替换 Port 和统一 HttpTransport 都服务于低侵入目标。不过，
[Starter 装配](../../../java/patchbridge-agent-spring-boot2-starter/src/main/java/io/patchbridge/agent/starter/PatchBridgeAgentAutoConfiguration.java)
明确要求 ConversationRepository、上下文窗口和压缩相关能力；自定义 ModelProvider 也需满足
状态投影契约。这在[接入指南](../../guides/spring-boot-integration.md)中已有说明。

这些是现行明确取舍，不应直接当成 bug。但完整默认装配提供的功能越多，陌生宿主需要理解和
接入的依赖就越多。应通过真实存量应用判断成本，而不是从 Demo 能启动就推导出“低侵入已证明”。

建议记录一次实际接入的业务代码修改、适配层代码、配置/表结构变化、原权限链复用情况及
必要的 UI 接线。首先选定真实宿主与任务，再约定可接受的成本；本报告不虚构工时、文件数或
成功阈值，也不假设所有企业都希望关闭持久化或压缩。

### 10.2 ModelTarget 与统一 Router 有必要的语义价值

当前模型名覆盖不能同时选择 endpoint、协议、凭据、窗口和状态解释器。恢复会话时也没有
独立的当前目标引用。R1.8 对这些事实的识别是正确的。

统一目标解析有助于使 Browser Model Stream、Java Gateway 与压缩使用同一目标语义。目标
引用与 ModelState 分离，也能保持 Provider 私有数据不被通用运行时解释。

维护者已确认保留最小目标路由的职责方向；多个入口仍应共享目标解析，不各自读取模型配置
或按名称猜测 Provider。

### 10.3 已确认：完整管理后台不作为协议验证的前置条件

当前[ADR-005](../adr/0005-model-target-routing-and-switching.md)和
[R1.8 技术方案](../designs/model-target-registry-and-switching.md)提出以 JDBC 为默认唯一模型
配置源、删除旧配置路径，并包含 Admin、凭据更新、双 Target revision、settingsRevision、
多实例 generation 和会话 handoff。R2/R3 又依赖 R1.8。

这些机制各有作用，但其成立前提包含“必须由本框架提供在线模型管理”这一产品选择。统一
Target 解析本身并不要求先完成整个管理后台。维护者于 2026-09-05 确认采纳这一方向：优先
验证最小目标路由与第二种真实协议，完整管理后台的范围和排期根据实际需求另行决定。

| 能力 | 与运行时目标一致性的关系 | 已确认的方向 |
| --- | --- | --- |
| 统一目标身份、协议选择、窗口和状态解释器 | 直接相关 | 优先明确最小契约 |
| 每次调用的目标授权与配置一致性 | 直接相关 | 与目标解析同时验证 |
| 第二种真实协议的调用与状态往返 | 检验抽象是否成立 | 尽早验证，不仅增加同协议 endpoint |
| Admin CRUD、在线凭据维护与全局默认设置 | 服务于在线管理用例 | 根据实际管理需求决定范围和排期 |
| 多种管理 revision 与 generation | 服务于配置并发和多实例管理 | 随真实管理边界设计，不提前当成所有扩展的共同前提 |

单一事实源是“来源与生效规则唯一”，不自动等于“只能由框架自己的 JDBC 系统管理”。宿主
已有配置服务也可能承担这一职责。具体保留何种配置入口仍需评审；本报告没有批准多源合并、
失败切换、双读兼容或额外配置路径。

### 10.4 第二种真实协议比第二个同协议目标更能检验架构

多个 OpenAI-compatible endpoint 可以验证目标选择，却不足以验证另一种消息协议、私有
状态生命周期、Tool 交互和压缩投影。新增 Provider 时，应检验以下问题：

- 能否只新增 Adapter 与装配，而不在 Runtime、Controller 或 View 中增加厂商分支？
- Tool 调用、取消后的继续、ModelState 完整替换与持久化恢复是否都成立？
- 压缩是否使用相同目标，摘要与状态投影是否适合该协议？
- 不支持的内容与状态能否明确失败，而非由公共层静默转换？

维护者已确认优先安排这个验证，不把完整模型管理后台作为其前置条件。本次只记录建设方向，
不把它描述为已经启动的 R2/R3，也不代替对具体 Provider 的选择。

## 11. 已确认的修正顺序与决策边界

维护者于 2026-09-05 确认以下建设顺序。这里只记录本报告的方向决策；实际是否进入实施、
完成状态及里程碑调整只在路线图登记，不把本次确认当作已经启动或完成实现。

| 顺序 | 工作 | 应交付的证据 | 完成前不能声称什么 |
| --- | --- | --- | --- |
| 1 | 修正 VA-01 至 VA-05 | 实现、组合回归、契约说明与可观察验证方式 | 不能仅凭现有全量测试通过认定问题已修复 |
| 2 | 验证真实存量宿主的低侵入接入 | 宿主任务、必要改动、原权限/请求链复用与接入成本记录 | 不能用项目自己的 Demo 代替所有存量系统 |
| 3 | 最小目标解析与第二种协议验证 | 统一目标事实、真实协议调用、状态/压缩/取消验证 | 不能把第二个同协议 endpoint 当成跨协议验收 |
| 4 | 按实际需求另行决定完整模型管理后台的范围与排期 | 在线管理用例、配置归属、权限、凭据和并发设计 | 不能把完整管理后台作为协议验证前置，也不能把 Proposed 的 JDBC/Admin 选择写成已接受实现 |

本次报告涉及的产品与契约决策：

- VA-03：维护者于 2026-09-05 确认，已完成工具保留真实结果，未执行工具明确记为取消，
  结果未知时先要求用户核实且不自动重复执行；具体消息表达与核实入口由实现设计确定，
  详见第 7.3–7.4 节。
- VA-05：维护者于 2026-09-05 确认，压缩后请求或摘要请求超限时停止、明确报错并保留完整
  历史，由用户调整输入后重新发起；预算包含系统指令、Tool 定义与输出预留。具体计量方式、
  配置归属和不同模态的可靠边界仍由实现设计明确，详见第 9.3–9.4 节。
- 后续方向：已确认完整模型管理后台不作为第二种真实协议验证的前置条件；是否需要框架自带
  在线模型管理、具体配置归属和管理范围按实际需求另行决定。本次未对其他方案文档中的
  具体配置或迁移设计作出决策。
- 宿主验证：维护者于 2026-09-05 确认，真实项目与具体任务暂未选定，以后再讨论；接入成本的
  验收指标随项目和任务一并确定，本次不指定宿主或启动接入验证。

上述确认限定于本报告中的取消行为、超限处理和建设顺序；宿主验证及明确列出的设计细节仍待
后续确定。本次只更新本报告，不修改其他文档的决策状态，不构成对新产品范围的自动授权。
改动公共契约时仍应一次同步 Browser、Java、HTTP、Schema、Guide、Demo 和相关 ADR，遵守项目当前
pre-release 的单一契约规则。

## 12. 文档一致性与进度判断

### 12.1 当前架构说明不能把待实现决策写成运行行为

审查基线的架构总览在 ConversationContext 部分以现在时描述显式 ModelState 重置；但
[ADR-002](../adr/0002-model-state-lifecycle.md)和路线图仍把该操作列为 R2 待实现。

这项差异在本次文档整理中校正为待实施契约。它不是 Runtime 新增能力，也不能因为设计
写得完整就被标为完成。

### 12.2 架构不变量要与已知缺口相邻说明

整轮 Tool 快照、统一错误分类和安全压缩仍是应当满足的目标。本报告不通过降低目标来解释
实现缺口。架构总览与 Runtime Reference 应提示已知限制，当前进度则由路线图关联 VA 编号。

### 12.3 历史验收与当前整改分别维护

rc.2 的 commit、tag、测试数量和发布证据保留原样。本次审查针对的是后续源码基线，不能
倒写历史记录，也不能把历史通过的结果当作当前未发现问题的证明。

同样，当前测试数量不能直接换算成“长期愿景完成百分比”。本次不重新估算一个比例，而是
记录哪些能力已经有实现、哪些承诺存在缺口、哪些扩展尚未通过真实验证。

## 13. 验证记录与可复查方式

### 13.1 现有测试

审查当日使用 Amazon Corretto `1.8.0_462`、Maven `3.9.9` 和 Node.js `v24.19.0` 执行：

```bash
# 在仓库根目录执行；JAVA_HOME 应明确指向已安装的 JDK 8。
mvn -f java/pom.xml test
npm --prefix web test
```

| 验证 | 实际结果 | 证明范围 |
| --- | --- | --- |
| Java 全 Reactor | 49 个测试套件，248 tests，0 failures/errors/skipped | 当时已有 Java 测试均通过 |
| Agent workspace | 180 tests 通过 | 已有 Browser 核心测试 |
| WebMCP Adapter workspace | 6 tests 通过 | 已有 Adapter 测试 |
| Widget workspace | 21 tests 通过 | 已有 Widget 测试 |
| Tools Inspector workspace | 2 tests 通过 | 已有只读视图测试 |
| Call Trace workspace | 21 tests 通过 | 已有轨迹视图测试 |
| Web 合计 | 230 tests 通过 | `npm test` 同时执行其 pretest 中的 Agent TypeScript 构建 |

本轮没有重跑五包完整构建、四份 Starter Bundle 一致性、真实外部服务 E2E 或正式候选预检。
不能把路线图中的历史构建数字合并成这次的新验证结果。

### 13.2 额外边界复现

Browser 复现直接用本地 esbuild 在内存中打包当前 Agent 源码，再注入可控的 Engine、Model、
ConversationClient 或摘要 Gateway。Java 复现把临时入口编译到仓库外，使用本次测试的实际
框架类与依赖 classpath。没有启动假模型 HTTP 服务或向真实 MCP 地址发送请求。

| 问题 | 保持真实的框架层 | 被控制的外部边界 | 应检查的输出 |
| --- | --- | --- | --- |
| VA-01 | BackendToolProvider、DefaultToolRegistry、McpToolRegistry | ToolClient/RemoteMcpClient 返回值 | 旧描述/配置与实际调用目标是否一致 |
| VA-02 | DefaultAgentController、状态机 | Engine 结果、会话 create/get/save | 完整保存体的 messages 与 modelContext 来源 |
| VA-03 | 工厂、Controller、Runtime、Registry | Model 结构化事件、会话 Client | 第二次 ModelRequest 的 Tool Call/Result 配对与执行次数 |
| VA-04 | AnnotatedToolProvider、Spring BeanFactory | 一个主动抛未知异常的注解方法 | throw/rejection 或 isError 结果的分类 |
| VA-05 | DefaultContextManager | 窗口配置与摘要结果 | estimatedTokensAfter、返回成功与阈值判断 |

这些临时入口没有加入项目测试目录。第 5–9 节记录了必要输入、交错顺序和实际输出，供实施
时转换为持久的回归用例；它们是问题存在的证据，不是已完成修复的证据。

现有测试可以作为扩展起点：

- [Controller 测试](../../../web/packages/agent/test/controller.test.ts)
- [Runtime 测试](../../../web/packages/agent/test/runtime.test.ts)
- [ContextManager 测试](../../../web/packages/agent/test/contextManager.test.ts)
- [Browser Tool Registry 测试](../../../web/packages/agent/test/toolRegistry.test.ts)
- [MCP 并发测试](../../../java/patchbridge-agent-mcp/src/test/java/io/patchbridge/agent/mcp/McpToolRegistryConcurrencyTest.java)
- [注解 Tool Adapter 测试](../../../java/patchbridge-agent-spring-boot2-starter/src/test/java/io/patchbridge/agent/starter/tool/AnnotatedToolProviderTest.java)

修复验收应检查真实职责链的最终输出。只检查单个 Promise 已取消、某个对象不可变、SQL 有
事务或总测试数增加，均不足以关闭本次发现。

## 14. 相关文档

- [路线图与当前进度](../../roadmap.md)
- [文档中心](../../README.md)
- [当前架构总览](../overview.md)
- [Runtime 契约参考](../../reference/runtime-contracts.md)
- [ADR-001：厂商中立 Runtime](../adr/0001-provider-neutral-runtime.md)
- [ADR-002：ModelState 生命周期与显式重置](../adr/0002-model-state-lifecycle.md)
- [ADR-003：Browser Runtime 执行守卫](../adr/0003-browser-runtime-guards.md)
- [ADR-004：上下文压缩](../adr/0004-context-compaction.md)
- [ADR-005（Proposed）：模型目标与切换](../adr/0005-model-target-routing-and-switching.md)
- [R1.8 模型目标管理与切换方案](../designs/model-target-registry-and-switching.md)
- [文档写作与维护规范](../../contributing/documentation.md)
