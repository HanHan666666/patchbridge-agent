# 可管理模型目标、按会话路由与显式切换技术方案

- 文档类型：实施设计
- 状态：历史候选设计，未按此版本实施；当前边界以 ADR-005 和路线图为准
- 关联里程碑：R1.8
- 最近更新：2026-09-24（仅更新状态说明）
- 实施基线：`d2a787b`
- 适用范围：Java 8 Core、Spring Boot 2 Starter、JDBC、Model Provider、Conversation、Browser Runtime、默认 Widget、Admin Console 与 Demo
- 关联决策：[ADR-001](../adr/0001-provider-neutral-runtime.md)、[ADR-002](../adr/0002-model-state-lifecycle.md)、[ADR-004](../adr/0004-context-compaction.md)、[ADR-005（Proposed）](../adr/0005-model-target-routing-and-switching.md)
- 权威边界：本文保留 2026-08-29 完整 JDBC/Admin 方案供将来评审，不是当前实现说明；实际配置来源和路由决策以[ADR-005](../adr/0005-model-target-routing-and-switching.md)、[配置参考](../../reference/configuration.md)及[路线图](../../roadmap.md)为准

> 2026-09-24 决策变更：维护者选择 `application.yml` 多目标配置与 Anthropic Messages 第二协议，完整模型管理后台暂缓。下文保留原提案，不作为当前代码的事实描述。

## 1. 结论先行

本方案不把“模型列表”实现成对现有 `patchbridge-agent.model.*` 配置的 CRUD 外壳，而是先建立
真正的模型选择边界：

```text
Admin 管理 ModelTarget
        ↓
ModelTargetStore（唯一配置源）
        ↓ 原子配置快照
ModelTargetCatalog ──→ 普通用户可见的脱敏目录
        ↓
ModelProviderRouter
        ↓ protocol
ModelProtocolAdapter ──→ 目标厂商 API
```

核心决策如下：

1. 后台管理的对象叫 `ModelTarget`，表示一个可执行的“协议 + endpoint + 上游模型 + 能力 +
   窗口 + 服务端凭据”组合，不把模型名、接口地址和 Provider 当成可以任意拼接的三份状态。
2. “启用”“默认”“当前”是三种不同状态：多个 Target 可以同时启用；系统至多有一个默认
   Target；每个可发送草稿或持久化会话只有一个当前 `ModelTargetRef`。
3. `ModelTargetRef { targetId, routingRevision }` 进入 `ConversationContext`，但不进入
   `AgentMessage` 或 `ModelState`。它表示下一次调用使用哪个目标，不是对旧消息的永久绑定。
4. `ModelRequest.model` 和 Browser 任意覆盖上游模型名的入口删除。Browser 与 Java 调用只能
   选择服务端目录中可用、可授权、修订一致的 `ModelTargetRef`。
5. `AgentMessage + ContentBlock` 保持厂商中立，不增加 PI 风格的 `provider/api/model` 和签名
   字段。Provider 私有数据继续只存在于 `ModelState { format, data }`。
6. 显式切换完整保留聊天历史和已有文本检查点，清除旧 Target 的 `ModelState`，按新 Target
   重新估算工作上下文；不翻译私有状态，不静默删除图片、reasoning 或 Tool 交互。
7. 模型配置的默认事实源改为 JDBC，Admin API 是唯一在线变更入口；部署级加密主密钥仍必须
   来自环境变量、Secret Manager 或宿主 Bean，它不是模型配置，不能与密文存入同一数据库。
8. R1.8 先让多个 OpenAI-compatible Chat Target 可管理、可路由、可切换；Anthropic 与
   Responses 继续由 R2、R3 增加协议 Adapter。没有注册 Adapter 的 API 不能声称“任意接入”。

## 2. 背景与当前代码事实

当前架构已经完成了多 Provider 的下半部分，但没有完成模型选择的上半部分：

- Core 已有稳定 `AgentMessage + ContentBlock`、`ModelRequest`、`ModelProvider`、结构化流和
  `ModelState`；
- Browser Runtime 不解析 `choices`、`tool_calls`、`reasoning_content` 等厂商 wire 字段；
- OpenAI-compatible Chat 的编码、SSE 解码和 reasoning 状态集中在协议 Adapter；
- `ConversationContext` 已把完整 `messages` 与可压缩 `modelContext` 原子保存；
- `ContextManager` 已统一上下文投影、80% 自动压缩和检查点；
- Admin 已有可信身份、能力授权、JDBC CRUD、乐观锁、AES-GCM 凭据和多实例 generation 的
  MCP 参考实现。

尚未形成模型目录的直接证据是：

1. Starter 只装配一个 `ModelProvider` Bean，`ModelInvocationPipeline` 构造时永久绑定它；
2. endpoint、API Key、默认模型和上下文窗口来自单份 `patchbridge-agent.model.*`；
3. `ModelRequest.model` 仍允许 Browser 或 Java 调用者提供上游模型名，但该字符串不选择
   endpoint、协议 Adapter、上下文窗口或状态格式；
4. `GET /model/config` 只能返回全局唯一模型的窗口；
5. Conversation 没有当前模型目标，恢复会话时只能继续使用宿主此刻的全局 Provider；
6. 上下文压缩也固定使用全局 `ModelGateway` 与全局 `ModelStateProjector`。

因此，当前数据结构虽然隔离了厂商协议，却还不能证明“任意模型换任意接口”。真正成立的
范围是：宿主可以整体替换一个 `ModelProvider`，或在 OpenAI-compatible Provider 内覆盖
模型名。按会话选择 endpoint、协议、能力、窗口和私有状态的路由契约尚不存在。

## 3. 外部实现参考与本项目取舍

PI 当前也有统一消息模型和目标模型转换层，但采用了不同的状态组织方式：

- PI 的 `AssistantMessage` 直接保存 `api/provider/model`，thinking、text 和 Tool Call 内容块
  可以携带 `thinkingSignature`、`textSignature` 与 `thoughtSignature`；见
  [PI 统一消息类型](https://github.com/earendil-works/pi/blob/853a80d26c90a14c1886f0ebb8ffaae133ca2185/packages/ai/src/types.ts#L350-L467)。
- 切换目标时，PI 的 `transformMessages` 对每条旧 Assistant 消息判断是否为同一
  provider/api/model；跨模型时删除不兼容签名、把可见 thinking 转成普通文本、规范化 Tool
  Call ID，并会为孤立 Tool Call 合成结果；见
  [PI 跨模型消息转换](https://github.com/earendil-works/pi/blob/853a80d26c90a14c1886f0ebb8ffaae133ca2185/packages/ai/src/api/transform-messages.ts#L35-L190)。
- PI 切换模型时保留消息并在会话日志追加 `model_change`；恢复会话时沿当前分支重建活动模型；见
  [PI 模型切换](https://github.com/earendil-works/pi/blob/853a80d26c90a14c1886f0ebb8ffaae133ca2185/packages/coding-agent/src/core/agent-session.ts#L1651-L1677)与
  [PI 会话上下文恢复](https://github.com/earendil-works/pi/blob/853a80d26c90a14c1886f0ebb8ffaae133ca2185/packages/coding-agent/src/core/session-manager.ts#L362-L469)。

PI 优先保证单进程 CLI 中的切换便利，因此接受受控降级。PatchBridge 同时跨 Java、HTTP、
Browser、JDBC 和宿主自定义 View，并已经通过 ADR-001/002 决定把展示消息与私有状态分开。
本方案只借鉴 PI 的“目标目录、目标路由、会话记录当前目标和跨 Provider 契约测试”，不借鉴：

- 把厂商签名提升为公共 `ContentBlock`；
- 不支持图片时替换占位文字；
- 跨模型时自动把 reasoning 改成普通正文；
- 为不完整 Tool Call 合成虚假 Tool Result；
- 发生不兼容时自动换模型或无状态重试。

## 4. 目标

### 4.1 产品目标

- 管理员可以在 `/ai-admin` 新增、编辑、测试、启停和删除多个模型 Target；
- 凭据只写不回显，数据库只保存 AES-GCM 密文；
- 管理员可以从已启用 Target 中指定新会话默认值；
- 普通用户可以在默认 Widget 或 Headless API 中查看自己有权使用的模型，并为新会话选择；
- 已有会话恢复最后一次显式选择的 Target，并允许空闲时切换；
- 切换后完整聊天历史不丢失，下一次模型调用使用新 Target；
- 多个 OpenAI-compatible endpoint/model 可以先共享一个协议 Adapter；新增 Anthropic、
  Responses 等协议时只新增 Adapter，不修改 Browser Agent Loop。

### 4.2 架构目标

- 路由信息、稳定消息和 Provider 私有状态各自只有一个职责；
- 所有运行时模型调用入口共享同一个 Router，包括 Browser SSE、上下文压缩和 Java
  `ModelGateway`；Admin 连通测试复用相同协议 Adapter，但属于受 `MODEL_MANAGE` 保护的配置诊断，
  不能让 disabled Target 进入普通路由；
- 选择目录与实际执行路由使用同一份不可变 Target 快照；
- Admin 变更、多 Tab 会话写入和多实例配置刷新都有明确 revision/generation；
- 未找到、已停用、无权限、配置修订变化和能力不兼容都显式失败；
- 后端不增加跨请求 Agent Execution、会话内 Agent 实例或长生命周期模型连接状态。

## 5. 非目标

R1.8 明确不做：

- 不实现用户个人 API Key、租户级模型目录或按用户保存 endpoint；
- 不实现按成本、延迟或故障自动选路；
- 不实现 Provider fallback、重试到备用模型或负载均衡；
- 不实现模型网关聚合服务、配额计费平台或动态价格目录；
- 不实现无法识别 API 的运行时脚本 Adapter；
- 不把模型协议 Adapter 做成通用 Plugin Container；
- 不把每条 Assistant 消息扩展为 PI 风格的 Provider 私有记录；
- 不记录逐条 Assistant 消息的历史模型归属，也不增加会话内模型切换时间线；R1.8 只保存下一次
  调用所需的 current Target，未来如确有归因需求应增加独立只读事件投影，而不是污染消息；
- 不实现一轮 AgentExecution 中途主动换模型；
- 不恢复执行到一半的 Browser Agent；
- 不在未注册 Anthropic/Responses Adapter 时把同名模型显示为可用协议。

## 6. 统一术语与三个状态层次

| 术语 | 含义 | 不是 |
| --- | --- | --- |
| `ModelTarget` | 服务端管理的完整可执行模型目标 | 单独一个模型名称 |
| `ModelTargetRef` | `targetId + routingRevision` 的不可变引用 | endpoint 或凭据 |
| enabled | 该 Target 是否允许进入目录并被路由 | 当前会话正在使用 |
| default | 新会话没有显式选择时的初始 Target | 全局唯一正在运行的模型 |
| current | 一个可发送草稿或持久化会话下一次调用使用的 Target | 旧 Assistant 消息的所有权 |
| `ModelProtocolAdapter` | 把稳定请求转换为一种明确 API 协议 | 厂商账户或配置记录 |
| `ModelProviderRouter` | 解析 Target 并选择 Adapter 的统一出站入口 | 第二套 Agent Runtime |
| handoff | 用户显式把一个会话的当前 Target 切换为另一个 | 私有状态翻译或自动降级 |

多个 Target 可以同时 enabled，才能让用户切换。default 在存在 enabled Target 时恰好一个；
每个持久化会话和可发送草稿 current 恰好一个。目录为空或当前用户没有可用 Target 时，空白
草稿可以暂时没有 current，但发送必须禁用。UI 和 API 不使用“启用一个模型”同时表达这三件事。

## 7. 必须保持的架构不变量

1. Agent Loop 仍只运行在 Browser。
2. Server 只在单次请求内解析 Target、调用 Provider 和持有取消句柄。
3. `AgentMessage` 不出现 endpoint、模型厂商、API Key、签名或路由修订。
4. `ModelState` 仍只有 `format/data`，公共层不解释其内容。
5. `ModelTargetRef` 与 `ModelContext` 作为同一 `ConversationContext` revision 原子保存。
6. 同一次 `AgentExecution` 从开始到结束只使用一个冻结的 `ModelTargetRef`。
7. Browser 请求中的 targetId 不是授权事实；服务端每次调用重新解析可信用户、目录和权限。
8. 模型列表过滤不替代模型调用、压缩和 handoff 时的再次授权。
9. 配置源只有一个，不合并 properties 与 JDBC，不在 JDBC 失败时退回启动配置。
10. Target 修订不匹配时不使用“最新版本”继续调用，必须先完成显式 handoff。
11. 切换不删除或改写完整消息，不把不支持的块静默转成文本。
12. 任何 Target 不可用时都不自动使用 default 或其他 enabled Target。

## 8. 目标领域模型

### 8.1 ModelTargetRef

```text
ModelTargetRef
├── targetId: String
└── routingRevision: long
```

- `targetId` 是管理员创建后不可修改的稳定 ID，建议使用 1～64 位 ASCII
  字母、数字、点、下划线或连字符；
- `routingRevision` 只在影响模型语义或上下文解释的字段变化时递增；
- Browser 不能只发送 `targetId` 后由 Server 补当前修订，否则配置变更会静默改变旧会话；
- 引用不携带 endpoint、上游模型名、凭据或任意 Provider 配置。

### 8.2 ModelTargetConfiguration

```text
ModelTargetConfiguration
├── targetId
├── displayName
├── protocol
├── baseUrl
├── upstreamModel
├── capabilities
│   ├── inputModalities: [text, image?]
│   ├── toolCalling: boolean
│   └── reasoningOutput: boolean
├── contextWindowTokens
├── maxOutputTokens
├── keepRecentTokens: Integer | null
├── connectTimeoutMs
├── readTimeoutMs
├── enabled
├── auth
├── revision
├── routingRevision
├── createdAt
└── updatedAt
```

约束：

- `protocol` 是稳定 Adapter ID，例如 `openai-chat-completions`，不是 Java 类名；
- `baseUrl` 只保存协议根地址，必须是无 userInfo、query 和 fragment 的绝对 HTTP(S) URI；具体
  路径和协议必需查询参数由 Adapter 唯一追加；
- `contextWindowTokens`、`maxOutputTokens` 必填且后者不得大于前者，框架不按模型名猜测；
- `keepRecentTokens` 为空时仍使用 ADR-004 的 `min(20_000, floor(window × 20%))`；
- `capabilities` 必须同时通过通用校验和目标 Adapter 校验，不能声明 Adapter 无法实现的能力；
- 第一版不增加任意 `options: Map<String,Object>`。新协议确实需要公共字段时再扩展明确类型，
  不用无约束配置袋提前制造第二套 wire protocol。

### 8.3 管理 revision 与 routingRevision

两个修订解决不同问题：

| 字段 | 作用 | 何时递增 |
| --- | --- | --- |
| `revision` | 单条 Target 的 Admin 乐观锁，防止两个管理页覆盖 | 该 Target 的任何持久化修改 |
| `routingRevision` | 会话与 Provider 状态的语义边界 | protocol、baseUrl、upstreamModel、auth 类型、能力、窗口、输出上限或近期预算变化 |

显示名、同一上游账户内的凭据轮换、enabled 和超时变化只推进 Target `revision`。认证类型
变化同时推进两个修订；切换上游账户或租户必须创建新的 Target，不能把“凭据轮换”用作账户
迁移，否则框架无法证明旧 `ModelState` 仍属于同一安全与状态边界。其他路由字段变化也同时推进
两个修订；旧会话随后得到 `MODEL_TARGET_REVISION_MISMATCH`，由用户确认 handoff 到新修订。

default 不属于任一 Target 记录，`agent_model_settings` 另有 `settingsRevision` 做全局设置乐观锁。
更改 default 只推进 settingsRevision 与 generation，不伪造某条 Target revision。涉及启停或删除
当前 default 的事务必须同时校验 Target revision 与 settingsRevision。

### 8.4 对普通用户公开的 ModelTargetDescriptor

普通目录只返回：

```text
ModelTargetDescriptor
├── ref
├── displayName
├── protocol
├── capabilities
├── contextWindowTokens
├── automaticThresholdTokens
├── keepRecentTokens
├── maxOutputTokens
└── default
```

不返回 `baseUrl`、auth 类型、credentialConfigured、管理 revision、创建人或诊断错误。Admin
视图可以看到 endpoint、auth 类型和是否已配置凭据，但同样不能读取凭据值。

### 8.5 ConversationContext

目标结构一次性替换为：

```text
ConversationContext
├── messages: AgentMessage[]        完整、可展示历史
├── modelTarget: ModelTargetRef      下一次调用目标
└── modelContext
    ├── checkpoint
    ├── firstRetainedMessageId
    ├── modelState
    └── usage
```

`modelTarget` 是 `modelContext` 的兄弟字段，而不是塞入 `ModelState`：

- 路由属于会话选择，Provider 私有状态属于当前工作上下文；
- 压缩更新 `modelContext` 时不应顺便改变目标；
- handoff 必须在一个 revision 内同时改变 target 和工作上下文；
- 完整历史消息不需要知道当前或历史 Provider。

持久化会话会记住最后一次选择，但这不是永久绑定。用户可以显式切换；保存引用的目的，是让
刷新、换设备和 Server 多实例都知道下一次调用应该使用哪个 Target，并阻止旧状态误送给新模型。

## 9. 组件与责任边界

### 9.1 ModelTargetStore

Core Port，负责配置事实：

- `version()`：O(1) generation；
- `findAll()/find(id)`：完整服务端记录；
- `create/update/setEnabled/delete` 使用 Target revision，`setDefault` 使用 settingsRevision；
- 数据变更、default 不变量和 generation 递增在同一数据库事务提交；
- Store 不创建 Provider、不做用户授权、不返回 Browser DTO。

默认 `JdbcModelTargetStore` 是唯一配置源。宿主可以完整替换 Store 接入中央配置服务，但不能与
JDBC 合并，也不能在中央服务失败时使用旧 properties。

### 9.2 ModelTargetConfigurationManager

Admin 变更的唯一应用服务：

1. 强制可变配置源；
2. 执行公共字段与目标协议 Adapter 校验；
3. 应用凭据 `KEEP / REPLACE / CLEAR`；
4. 计算 routingRevision 是否应推进；
5. 调用 Store 的乐观锁写入；
6. 让本实例 Catalog 读取新 generation 并发布完整快照；
7. 不在发布失败时继续使用旧快照；Catalog 不可用时仍允许 Admin 通过 Manager 读取脱敏 Store
   记录并修复或删除故障配置，避免运行时 fail closed 后失去修复入口。

### 9.3 ModelProtocolAdapter

每种 API 协议一个小接口实现，职责包括：

- 声明唯一 `protocol`；
- 校验 Target 的协议相关配置和能力；
- 把稳定 `ModelRequest` 编码成目标 wire request；
- 把上游流解码成结构化 `ModelStreamEvent`；
- 校验和生成该协议的 `ModelState`；
- 投影上下文压缩后的 Provider 状态；
- 暴露一次无 Tool、低输出预算的协议诊断调用，供 Admin 测试服务复用。

Adapter 不读取数据库、不选择 default、不判断当前用户能否使用模型。相同 `protocol` 只能有一个
活动实现；默认 OkHttp 和可选 WebFlux 属于同一协议的替代 Adapter，不能同时注册后按顺序猜测。

现有 `ModelProvider` 继续作为 `ModelInvocationPipeline` 的单一出站 Port，由 Router 实现；Pipeline
不感知 Target Store。宿主原先直接替换一个 `ModelProvider` Bean 的扩展方式在本次 pre-release
替换后改为注册一个有稳定 protocol ID 的 `ModelProtocolAdapter`，不能再绕过 Catalog/Policy。
全局 `ModelStateProjector` 同样收敛为 Adapter 的 target-aware 投影能力。

### 9.4 ModelTargetCatalog

Catalog 持有从 Store generation 构建的不可变配置快照：

- Admin 读取完整脱敏视图；
- 普通用户目录读取 enabled Descriptor；
- Router 按精确 `ModelTargetRef` 解析 `ResolvedModelTarget`；
- generation 变化时先完整读取、解密、校验并构建候选快照，再一次性替换；
- 候选存在非法配置、未知协议或解密失败时，当前 generation 标记失败，模型调用明确失败；
  不继续使用上一 generation 冒充最新配置。
- Catalog 故障不封锁 Admin 修复面：Admin 读取由 ConfigurationManager 直接投影 Store 的脱敏
  记录和明确诊断，仍可凭 record revision 修复或删除；该路径不能执行普通模型调用。

### 9.5 ModelAccessPolicy

模型是费用和数据出境边界。新增与 Tool Policy 类似的小 Port：

```text
canUse(accessContext, targetDescriptor) -> boolean
```

`ModelAccessContext` 由可信入站边界构造，明确区分 `AUTHENTICATED_USER` 与 `TRUSTED_JAVA`，
并可携带现有 `AiRequestContext`，但不得从 Browser JSON 反序列化 caller 类型。HTTP 目录、
stream、compact 与 handoff 必须先解析已认证用户，再构造前者；嵌套的压缩和 Router 调用继续
透传该 origin，不能进入 Core 后重新分类。直接调用 `ModelGateway` 的两个重载属于宿主进程内
入口，构造后者；显式 `AiRequestContext` 中的用户仍提供给宿主策略判断，但不会把调用伪装成
Starter HTTP。默认实现允许有稳定 userId 的已认证用户和可信 JVM 调用，多租户宿主可以按组织、
套餐、数据区域、系统任务身份或权限覆盖。匿名 Starter HTTP 即使直接到达 Router 也必须拒绝，
不能借空 user 冒充可信 JVM。目录过滤与每次真实调用的授权检查必须同时存在。

### 9.6 ModelTargetTester

Admin 连通测试是独立、包内应用服务，用于解决“新 Target 默认 disabled，但启用前必须验证”的
顺序问题：

- 只接受已经通过 `MODEL_MANAGE` 的管理调用；
- 通过 ConfigurationManager/Store 按精确 record revision 读取并解密该记录，因此允许测试
  disabled Target，也能在运行时 Catalog 当前 generation 失败时帮助修复；
- 执行与 Catalog 相同的字段和 Adapter 校验，再调用目标 `ModelProtocolAdapter` 的诊断原语；
- 不经过普通 Router 的 enabled 检查，也不调用普通 `ModelAccessPolicy`；这条豁免只存在于
  Admin 测试服务，Browser、Java Gateway 和 Context Compaction 均无法到达；
- 结果只返回本次 ok、耗时、停止原因和安全错误摘要，不持久化“最近测试状态”。

它复用同一 Adapter、传输和协议解析，不复制第二套 Provider；Admin 操作审计仍独立记录。

### 9.7 ModelProviderRouter

Router 取代 `ModelInvocationPipeline` 当前持有的单一 Provider：

1. 从请求取得必填 `ModelTargetRef`；
2. 通过 Catalog 精确解析 enabled 且修订一致的 Target；
3. 重新执行 `ModelAccessPolicy`；
4. 按 Target.protocol 找到唯一 Adapter；
5. 用同一份 `ResolvedModelTarget` 发起调用或状态投影；
6. 把 targetId@routingRevision 放入审计和 Interceptor 可见上下文。

Router 不捕获异常后选择其他 Target。单次调用取得的 Resolved Target 是不可变值；Admin 在流式
调用中修改配置只影响下一次 HTTP 模型调用，不改写已经开始的上游请求。

### 9.8 ModelHandoffService

handoff 是独立应用服务，不放在 Controller、Repository、Provider 或 Widget：

- 读取完整 ConversationContext；
- 解析和授权目标 Target；
- source Target 可以已停用、修订过期或不再对用户开放；handoff 只读取稳定 Context、清除其
  私有状态并授权目标 Target，不能要求用户先恢复 source 权限才能离开；
- 用与正常模型输入相同的工作上下文投影规则，对具有稳定输入语义的块执行能力预检；
- 保留 messages、checkpoint 和 firstRetainedMessageId；
- 把 modelState 明确设为 null；
- 按目标上下文窗口重新生成 `estimated` usage；
- 返回完整新 Context；持久化场景再以 expectedRevision 原子保存；
- 写入一条不含凭据和正文的 MODEL_SWITCH 审计事实。

持久化会话和本地草稿分别使用同一个 Service：前者由 Server 读取并保存，后者通过无持久化的
handoff 校验端点提交严格 `ConversationContext` 并取得候选结果。两条入口不各自实现转换规则。

handoff 的估算由服务端唯一 `ContextUsageEstimator` 执行，算法与 ADR-004 现有 Browser 估算
契约逐字段一致：每条消息固定 32 字节开销、每个 Block 固定 16 字节开销，再计入 role、id 与
各 Block 实际序列化字段的 UTF-8 字节，并按一字节一 token 取保守上界。仓库级共享 fixture
锁定 Java 与 TypeScript 结果；Browser 不自行实现另一套 handoff 变换或估算。

### 9.9 模块落位与依赖方向

R1.8 不为“模型列表”单独增加 Maven 模块；当前边界已经足够表达职责：

| 模块 | 新增责任 |
| --- | --- |
| `patchbridge-agent-core` | Target/Ref/Capabilities、Store/AccessPolicy/Adapter Port、ConfigurationManager、Tester、Router、Handoff、估算器和错误语义 |
| `patchbridge-agent-model-openai` | 继续独占 OpenAI Chat wire 编解码与状态格式，不读取 Target Store |
| `patchbridge-agent-model-webflux` | 提供同一 OpenAI Chat protocol 的可替换 WebFlux 出站 Adapter |
| `patchbridge-agent-storage-jdbc` | JDBC Store、Schema 与 `ModelCredentialCipher` 实现 |
| `patchbridge-agent-spring-boot2-starter` | 默认 OkHttp Adapter、Core 服务装配、HTTP/Admin DTO 与内置管理页 |
| Browser `agent` / `widget` | 目录与 handoff Client、状态事件、选择器和确认交互 |
| Demo | 只提供真实权限映射、配置说明和验收入口 |

依赖只能指向 Core Port：JDBC 和协议 Adapter 不互相依赖，Core 不依赖 Spring、JDBC 或具体模型
模块，Demo 不承载可复用业务逻辑。未来只有出现独立发布与依赖边界时才评估拆出 registry 模块，
不为目录 CRUD 预先增加工程层级。

## 10. 配置持久化与凭据安全

### 10.1 JDBC 结构

建议新增：

```text
agent_model_target
├── target_id                 PK
├── display_name
├── protocol
├── base_url
├── upstream_model
├── capabilities_json
├── context_window_tokens
├── max_output_tokens
├── keep_recent_tokens        nullable
├── connect_timeout_ms
├── read_timeout_ms
├── enabled
├── auth_type
├── encrypted_credentials     nullable
├── revision
├── routing_revision
├── created_at
└── updated_at

agent_model_settings
├── id = 1                    PK
├── default_target_id         nullable FK
└── settings_revision

agent_model_config_generation
├── id = 1                    PK
└── generation
```

`agent_conversation` 增加 `model_target_id` 与 `model_target_revision`，作为会话聚合的一部分与
`model_context_json`、消息和 revision 同事务更新。Target ID 使用外键限制删除；Target 已被
任何会话引用时返回明确 `MODEL_TARGET_IN_USE`，管理员应停用它，而不是破坏旧会话恢复。

不在 `model_context_json` 再保存一份 Target 引用，避免列和 JSON 双真相。Repository 负责把
关系列与 JSON 中的工作上下文组装为唯一 `ConversationContext`。

### 10.2 default 不变量

- 没有 enabled Target 时，default 必须为 null；
- 存在 enabled Target 时，必须恰好一个 default；
- 设置 default、启用第一个 Target、停用或删除当前 default 都必须在 Store 事务内保持该规则；
- 启用第一个 Target 的请求必须显式声明同时设为 default；启用后续 Target 不改变 default；
- 停用或删除当前 default 且仍有其他 enabled Target 时，请求必须显式指定替代 Target；如果该
  操作使 enabled 数量变为零，则同一事务把 default 设为 null；任何场景都不能按列表顺序选择；
- 单独设置 default 校验 settingsRevision；同时启停/删除当前 default 的操作原子校验 Target
  revision 与 settingsRevision，任一冲突都不提交；
- default 改变只影响新草稿，不改变已有会话。

### 10.3 凭据

模型认证第一版支持明确集合：`none`、`bearer`、`api-key-header`、`static-headers`。通用 Validator
拒绝换行以及 `Host`、`Content-Length`、`Authorization`、`Cookie`、代理认证、连接控制和其他
协议/传输保留 Header；`Authorization` 只能由 bearer 类型生成，协议 Adapter 可以进一步收窄
支持类型。

- Admin 创建时凭据只写；
- 更新必须显式声明 `KEEP / REPLACE / CLEAR`；
- 现有密文无法解密时 `KEEP` 明确失败，管理员只能用 `REPLACE` 提交新凭据，或在同一更新中把
  auth 改为 `none` 并 `CLEAR`；需要凭据的 auth 类型不能留下空密文；
- 响应只返回 `authType` 和 `credentialConfigured`；
- 密文使用 AES-256-GCM，并把 targetId 作为 AAD，防止密文跨记录替换；
- 日志、异常、审计、测试结果和 Browser Catalog 均不得包含密钥或上游错误正文；
- 加密主密钥通过部署环境或宿主 `CredentialCipher` Bean 提供，不能由 Admin API 写入数据库。
- 默认 JDBC 模型目录启用时加密主密钥是启动硬要求，即使空库或首个 Target 使用 `none` 也不
  生成临时密钥；否则后续 Admin 写入会产生不可跨重启恢复的密文事实。

模型模块拥有领域专用 `ModelCredentialCipher`，不依赖 MCP 模块。若实施时确认两者的 AES-GCM
封装完全相同，可以把纯密码学原语下沉为内部基础设施实现，再分别由 `ModelCredentialCipher`
与 `McpCredentialCipher` 委托；不能暴露通用凭据 Map，也不能让两个配置 Store 互相依赖。

普通 REPLACE 只表示同一上游账户内轮换密钥。管理员要切换账户、组织或数据区域时必须创建新
Target 并走 handoff；框架不从不可读凭据推断账户是否改变。

### 10.4 删除旧 properties 模型源

正式实施时一次性删除：

- `patchbridge-agent.model.base-url`；
- `patchbridge-agent.model.api-key`；
- `patchbridge-agent.model.model`；
- `patchbridge-agent.model.context-window-tokens`；
- `patchbridge-agent.model.keep-recent-tokens`；
- 由单份 properties 构造单一 `OpenAiCompatibleModelProvider` 的自动装配路径。

超时也进入每个 Target。部署配置只保留模型目录开关、凭据加密主密钥等基础设施参数。
项目仍是 pre-release，不增加 properties 导入、首启复制、双读或 JDBC 为空时 fallback。

## 11. 正常调用路由

### 11.1 Browser AgentExecution

```text
Controller.start run
  → 冻结 current ModelTargetRef + ToolRegistrySnapshot
  → ContextManager 使用该 Target 的窗口配置检查压缩
  → HttpModel 发送 targetRef + 稳定 ModelRequest
  → Server 校验 target、revision、enabled、权限和 conversation current target
  → ModelProviderRouter 解析同一快照
  → ModelProtocolAdapter 调用上游
```

AgentExecution 中的每一次 Tool 循环模型调用复用同一个 TargetRef。Widget 在 streaming、
compacting、calling-tool、waiting-confirmation 或 saving 时禁用切换；Headless
`selectModelTarget()` 在非 idle 状态明确抛错，不自动取消当前 Execution。

### 11.2 ModelRequest

目标形状：

```text
ModelRequest
├── target: ModelTargetRef        必填
├── responseMessageId
├── messages
├── tools
├── modelState
├── temperature
└── maxTokens
```

删除可选 `model: String`。Adapter 必须使用 Resolved Target 的 `upstreamModel`；请求不能绕过
Admin allowlist 指向未配置模型。`maxTokens` 如存在必须小于等于 Target.maxOutputTokens，超出
明确失败，不静默钳制。

### 11.3 持久化会话一致性

Browser 提供 conversationId 时，Server 还必须验证请求 TargetRef 等于该 owner 下会话当前
TargetRef。否则返回 `MODEL_TARGET_CONVERSATION_MISMATCH`，禁止通过直接调用 stream 端点绕过
handoff。草稿 conversationId 为 null 时仍执行 Target 目录和用户权限校验。

### 11.4 新草稿与首次持久化

模型目录初始化与现有“首轮结束后才建会话”的生命周期按以下顺序组合：

1. Browser 初始化模型目录；当前用户可使用全局 default 时，把其精确 Ref 固定为新草稿
   current，否则 current 为 null、发送禁用，由用户显式选择可用 Target；
2. 只有固定 system 消息、且没有非 system 消息/checkpoint/modelState/usage 的空草稿，切换时
   只替换本地 Ref；出现任一工作上下文事实后统一调用草稿 handoff 端点，不能在 reducer 中
   直接改 Ref；
3. 首轮模型请求携带精确 TargetRef，conversationId 仍为 null，Server 照常执行目录、权限、
   revision 和能力检查；
4. `completed` 或 `max-tokens` 终态需要保存时，Browser 才调用创建端点，以单个事务写入 title、
   ModelTargetRef、完整 messages 和 ModelContext 并返回完整快照；不再先创建空行再 PUT；
5. 取消、失败或保存前离开不会创建 Server 会话，已生成的本地草稿消息仍留在当前 Browser
   状态；其中切换 Target 继续走无持久化 handoff；
6. ordinary save 只能保存与 Server current TargetRef 相同的 Context，只有专用持久化 handoff
   端点可以改变 Target，防止用 PUT 绕过切换语义。

新草稿创建后即捕获当时的 default；管理员后来改变 default 不修改这个草稿。会话列表与详情
返回同一关系列投影出的当前 TargetRef，以及可安全展示的 displayName；不返回 endpoint、认证
类型或凭据。Target 已停用、修订过期或权限被收回时，历史仍可列出并标记不可用。

## 12. 显式模型切换

### 12.1 为什么会话要保存当前 Target

保存 current Target 不是把旧对话锁死在原模型，而是保证：

- 刷新后 UI 知道下一次调用使用哪个模型；
- `ModelState` 不会被送给另一个 endpoint/model；
- 上下文窗口与压缩阈值和真正模型一致；
- 管理员修改配置后可以检测修订变化；
- 多 Tab 和多实例使用同一 revision 判断，而不是依赖本地选择框。

旧消息仍然只是厂商中立 `AgentMessage`。显式 handoff 成功后，同一会话的 current Target 立即
变成新值，后续调用不再使用旧值。

### 12.2 切换前置条件

- Controller 空闲；
- Target enabled、修订一致且当前用户有权使用；
- 目标协议 Adapter 已注册；
- 当前模型工作上下文中具有稳定输入语义的 text、image、Tool Call/Result 都能被目标 Adapter
  原样编码；展示用 `ReasoningBlock` 不属于跨 Provider 输入承诺；
- 新 Target 的窗口和近期预算配置合法；
- 持久化会话的 expectedRevision 与服务端一致。

### 12.3 原子转移

```text
before
ConversationContext(messages, target=A, modelContext(checkpoint, state=A-state, usage=A-usage))

after
ConversationContext(messages, target=B, modelContext(checkpoint, state=null, usage=B-estimated))
```

严格行为：

1. `messages` 的顺序、ID、role、blocks 与全部字段逐值不变，不要求跨 HTTP 保持对象引用身份；
2. `checkpoint` 与 `firstRetainedMessageId` 保持不变，因为检查点是稳定文本，不是 Provider 私有状态；
3. 旧 `modelState` 不翻译、不备份、不同时保留多份，直接变为 null；
4. 通过服务端 `ContextUsageEstimator` 对 system、检查点和近期真实消息重新计算 ADR-004 的
   UTF-8 保守上界，写为 `estimated` usage；
5. 若估算达到新 Target 的 80% 但仍在目标窗口内，下一次正常调用前由现有 ContextManager
   使用新 Target 自动压缩；若保守估算已经超过目标窗口，则 handoff 明确拒绝，要求先用原
   Target 压缩或选择更大窗口，并返回 `MODEL_TARGET_CONTEXT_TOO_LARGE`，不能提交一个必然无法
   调用的 current Target；
6. 正常 Provider 响应到达后用新 Target 的真实 usage 替换估算；
7. 任何校验或保存失败都不提交候选，不改变本地 current Target；
8. 持久化成功推进会话 revision，并返回完整 ConversationSnapshot；Browser 只用返回快照替换状态。

ADR-004 需要在实施时扩展 `estimated` 的合法来源：除了成功压缩后的过渡快照，还包括成功
handoff 后到目标 Provider 第一份正常 usage 之间。估算仍不能替代正常响应 usage。

`MODEL_SWITCH` 审计继续遵守现有 AuditRecorder 的可用性契约，只记录 source/target Ref、owner、
trace 与结果，不记录消息、摘要或凭据；它不是授权事实，也不擅自把当前 fail-open 审计改成
handoff 的事务回滚条件。

### 12.4 能力不兼容决策表

| 工作上下文内容 | Target 能力 | 行为 |
| --- | --- | --- |
| text | 所有协议必须支持 | 允许 |
| image | `inputModalities` 包含 image | 允许 |
| image | 不支持 image | 拒绝 `MODEL_TARGET_UNSUPPORTED_CONTEXT` |
| tool-call / tool-result | `toolCalling=true` 且 Adapter 支持历史 Tool 编码 | 允许 |
| tool-call / tool-result | 不支持 Tool | 拒绝，不转换为文本 |
| reasoning | `ReasoningBlock` 是展示内容 | 始终保留在完整历史；不发送、不删除、不转成正文，续接私有状态随 handoff 清除 |
| Provider 私有 ModelState | 任意不同 TargetRef | 清除；由确认界面明示不可恢复代价 |

只检查实际会送入新模型的工作上下文。已经被文本 checkpoint 替代的旧图片或 Tool 块仍保留在
完整聊天历史，但不会因为不在当前投影内阻止切换。

### 12.5 配置被管理员修改或停用

- routingRevision 变化：历史仍可查看，发送和压缩返回 revision mismatch；UI 提供“切换到当前
  修订”，走同一 handoff，不自动接受；
- Target disabled：历史仍可查看，当前模型显示“已停用”；用户必须选择另一个有权 Target；
- Target 删除：被 Conversation FK 引用时禁止删除；无引用且非 default 时才允许；
- default 改变：只影响新会话，旧会话不变；
- 凭据轮换：不改变 TargetRef，下一次请求使用新密文；认证失败明确返回模型错误，不换 Target。

## 13. 上下文压缩的路由改造

当前全局 `GET /model/config` 与全局 `ContextCompactionSettings` 必须收敛到 Target：

- 普通 Model Catalog Descriptor 直接包含该 Target 的窗口、80% 阈值和近期预算；
- Browser `ContextManager` 根据 Conversation.current Target 选择配置；
- `POST /model/compact` 必须携带同一个 `ModelTargetRef`；
- Server 通过 Router 选择对应 Adapter 和 `ModelStateProjector`；
- persisted conversation 同样校验 target 与会话 current 一致；
- 生成摘要时使用 current Target，不增加另一个摘要模型配置；
- handoff 后达到阈值时第一次压缩已经使用新 Target。

这样 Model Stream、Java Gateway 和 Context Compaction 不会各自维护一份 Target 解析逻辑。

## 14. HTTP 契约草案

这些端点只有实现、测试、Guide/Reference 和 Demo 全部完成后才进入公共 Reference。

### 14.1 普通用户目录

```text
GET {basePath}/models
→ { items: ModelTargetDescriptor[], defaultTarget: ModelTargetRef | null }
```

只返回 enabled 且 `ModelAccessPolicy` 允许的 Target。若全局 default 对当前用户不可用，
`defaultTarget` 返回 null，不选择 items[0] 作为替代。

### 14.2 模型调用与压缩

```text
POST {basePath}/model/stream
POST {basePath}/model/compact
```

两者的请求都显式包含 `modelTarget`，且字段必须精确为 `targetId/routingRevision`。

### 14.3 会话创建与普通保存

```text
POST {basePath}/conversations
body: { title, context }
→ ConversationDetail

PUT {basePath}/conversations/{conversationId}
body: { title, revision, context }
→ ConversationDetail
```

创建只在首轮稳定终态后发生，并把 TargetRef、messages 和 ModelContext 一次写入；普通保存要求
请求 Ref 与服务端 current Ref 相同。会话列表的安全目标摘要与详情 Context 都投影自同一组
`agent_conversation` 关系列，不产生第二份路由事实。

### 14.4 草稿 handoff

```text
POST {basePath}/model/handoff
body: { target, context }
→ { context }
```

只做严格校验和纯 Context 转移，不创建会话、不写审计正文、不保存历史。服务端返回的 Context
是 handoff 唯一候选；Browser 不本地复刻状态清除、能力判断或 usage 估算。

### 14.5 持久化会话 handoff

```text
PUT {basePath}/conversations/{conversationId}/model-target
body: { target, expectedRevision }
→ ConversationDetail
```

Server 使用 owner 范围内的当前完整快照，不接受 Browser 另传 messages/modelContext 覆盖。

### 14.6 Admin

```text
GET    {basePath}/admin/models
POST   {basePath}/admin/models
PUT    {basePath}/admin/models/{targetId}
POST   {basePath}/admin/models/{targetId}/enabled
       body: { enabled, revision, settingsRevision?, makeDefault?, replacementDefaultTargetId? }
POST   {basePath}/admin/models/{targetId}/default
       body: { settingsRevision }
POST   {basePath}/admin/models/{targetId}/test
       body: { revision }
DELETE {basePath}/admin/models/{targetId}
       ?revision=...[&settingsRevision=...&replacementDefaultTargetId=...]
```

Admin 列表响应以 `{ items, defaultTargetId, settingsRevision }` 同时返回脱敏 Target 记录与全局设置
乐观锁，不能让管理页从 items 的 `default` 标记反推并发版本。

Admin 的 Target 写请求使用 record `revision` 和凭据 `KEEP/REPLACE/CLEAR`；default 写请求使用
`settingsRevision`。连通测试发起一次无 Tool、低输出预算的真实模型调用，返回 ok、耗时、
停止原因和安全错误摘要，不返回生成正文或上游响应体。
测试端点受 `MODEL_MANAGE` 保护，可以测试 disabled Target，但不会改变 enabled/default，也不会
让该 Target 被普通 Router 解析；结果只随本次响应返回，不落库。

启停请求必须携带 record revision；启用第一个 Target 时还携带 settingsRevision 并显式声明
`makeDefault`，停用当前 default 且仍有其他 enabled Target 时携带 settingsRevision 与显式
replacement。删除遵守同一规则，不允许服务端从列表位置推断新 default。

创建端点始终生成 disabled Target；创建/更新 DTO 不接受 default，更新 DTO 也不接受 enabled
或 targetId。启停和 default 只能走上面的专用意图端点，未知字段继续按现有严格 DTO 规则拒绝。

## 15. Browser、Widget 与 Admin UI

### 15.1 AgentState 与 Headless API

新增只读状态：

- `availableModelTargets`；
- `modelTarget`；
- 当前 Target 对应的 `contextConfiguration`；
- Target 不可用或修订过期的明确错误状态。

新增用户意图：

```text
selectModelTarget(ref): Promise<void>
refreshModelTargets(): Promise<void>
```

所有变更仍通过具名 reducer event。Controller 不直接 patch state；Handoff Gateway 是唯一切换
入口。Engine.start 时冻结 target，Runtime 不读取动态目录。

### 15.2 默认 Widget

- Header 增加当前模型选择器，显示 `displayName`；
- 只有固定 system 消息、没有其他工作上下文事实的空草稿切换无需确认；
- 已有非 system 消息、checkpoint、modelState 或 usage 时显示确认框，明确“聊天历史和摘要保留，
  旧模型私有续接状态将被清除”；
- busy 状态禁用选择器；
- 当前 Target 被停用或修订过期时仍展示全部消息，同时禁用发送并引导选择新 Target；
- 没有可用 Target 时显示“当前账号没有可用模型”，不猜 default、不隐藏错误；
- 上下文窗口面板随 current Target 立即更新，handoff 后显示 estimated 来源。

### 15.3 Admin Console

在现有 `/ai-admin` 增加“模型”区，而不是创建第二个后台：

- 列表显示名称、协议、上游模型、endpoint、能力、窗口、enabled/default、凭据是否配置和
  revision；
- 表单新增/编辑 Target，凭据字段永不回填；
- enabled、default、测试和删除使用独立操作，避免完整 PUT 意外覆盖；
- 创建建议默认 disabled，管理员保存、真实测试、启用、设 default 的步骤均可观察；
- endpoint 文案明确这是 Server 出站能力，生产应配合出口代理、防火墙或宿主校验策略。

新增 Admin 能力：

- `MODEL_READ`：查看脱敏配置与配置诊断；
- `MODEL_MANAGE`：写配置、凭据、启停、default、测试和删除。

`CONSOLE` 只允许读取静态页面，不等同于上述两项 API 权限。

### 15.4 Demo

能力必须属于 Starter/Widget，Demo 只做真实装配与观察入口：

- `/ai-admin` 使用 Demo 的 `AdminAccessPolicy` 映射 MODEL_READ/MODEL_MANAGE；
- 首页默认 Widget 展示 Target 选择器；
- 空数据库不播种 Mock 模型、假 endpoint 或明文密钥；
- Quick Start 引导管理员先在后台创建真实 Target，再回首页选择；
- Demo 验收至少配置两个真实 OpenAI-compatible Target，验证 default、新会话选择、旧会话
  handoff、上下文窗口变化、状态清除和完整历史数量不变；
- R2/R3 再增加真实跨协议切换验收，不在 R1.8 用同协议 Target 冒充跨厂商完成。

## 16. Java ModelGateway

Java 单次调用同样不能绕过目录：

- `ModelRequest` 必须携带 `ModelTargetRef`；
- Builder 删除 `.model(String)`，新增必填 `.modelTarget(ModelTargetRef)`；
- 宿主可以通过 Catalog 的安全 Java 读入口显式取得 `listAvailable(accessContext)` 与
  `defaultFor(accessContext)`，它们只返回 Descriptor/Ref，不返回 Resolved Target 或凭据；
- `ModelGateway`、Browser SSE 与 Context Compaction 继续共享同一 Pipeline/Router；
- Java 调用默认仍不读写 Conversation、Audit 或调用历史；
- 无 target 不使用系统 default 兜底，构建请求时明确失败；调用者可以先显式查询 default，但
  选择动作不能隐藏在 Provider 或 Gateway 内。

## 17. 并发、多实例与生命周期

### 17.1 Admin 并发

- 每条 Target 使用 `revision` 乐观锁；
- default 与 Target 启停在同一事务维持；
- 全局 generation 与写操作同事务递增；
- 同一 JVM 的 ConfigurationManager 串行化“落库—发布”；
- 其他 JVM 在下一次目录读取或模型调用前发现 generation 并重建快照。

### 17.2 会话并发

- handoff 使用 Conversation.expectedRevision；
- 多 Tab 中只有一个切换或保存可以成功；
- 冲突返回最新 revision，但不合并 Target 或 ModelContext；
- Browser 必须重新加载完整快照后让用户重新决定。

### 17.3 流式调用

- Router 在 stream 开始前解析一次不可变 Resolved Target；
- 配置刷新不改变进行中的请求；
- 下一次 Agent Loop HTTP 调用仍使用冻结 TargetRef；若 routingRevision 已变化则明确终止本轮；
- 不为维持旧配置在 Server 保存跨请求 execution snapshot，这个少见的管理竞态以明确失败换取
  后端无状态和可解释性。

## 18. 稳定错误语义

建议增加：

| 错误码 | 语义 | 重试 |
| --- | --- | --- |
| `MODEL_TARGET_REQUIRED` | 请求没有精确 TargetRef | 否 |
| `MODEL_TARGET_NOT_FOUND` | Target ID 不存在 | 否 |
| `MODEL_TARGET_DISABLED` | Target 已停用 | 否 |
| `MODEL_TARGET_FORBIDDEN` | 当前用户无权使用 | 否 |
| `MODEL_TARGET_REVISION_MISMATCH` | 会话引用与当前 routingRevision 不同 | 否，需 handoff |
| `MODEL_TARGET_CONVERSATION_MISMATCH` | 调用 Target 与会话 current 不同 | 否 |
| `MODEL_TARGET_UNSUPPORTED_CONTEXT` | 目标无法原样编码当前工作上下文 | 否 |
| `MODEL_TARGET_CONTEXT_TOO_LARGE` | 工作上下文的保守估算超过目标窗口 | 否，需先压缩或换目标 |
| `MODEL_TARGET_IN_USE` | 删除仍被会话引用的 Target | 否 |
| `MODEL_CONFIGURATION_CONFLICT` | Admin revision 冲突 | 否，需刷新 |
| `MODEL_CONFIGURATION_INVALID` | 配置、协议、密文或快照无效 | 否 |

上游网络、认证、配额和协议错误仍归 `MODEL_FAILED`，但审计必须记录 targetId@routingRevision。
错误正文不包含 endpoint 凭据、Header 或上游响应体。

## 19. 测试策略

### 19.1 Core

- ModelTarget/Ref/Capabilities、Target 的两个 revision 与全局 settingsRevision 的边界测试；
- default 三态不变量；
- Router 对 missing/disabled/forbidden/revision mismatch/未知协议逐项失败；
- Adapter 重名明确失败；
- Handoff 保留完整消息和 checkpoint、清空 state、重新估算 usage；
- Java/TypeScript 的 UTF-8 上界共享 fixture 一致，Browser 不实现第二套 handoff 变换；
- image/tool 输入能力矩阵明确接受或拒绝；ReasoningBlock 始终按展示内容保留但不转正文；
- 同一 Target 同一修订不触发 handoff；独立 reset 仍留在 ADR-002；
- Interceptor 能看到稳定 TargetRef，不读取凭据。

### 19.2 JDBC 与安全

- 配置 CRUD、record revision、routingRevision、settingsRevision、default 事务与 generation；
- AES-GCM 往返、错误主密钥、AAD 防跨记录替换、KEEP/REPLACE/CLEAR；
- Admin 响应、异常、日志和审计不泄漏凭据；
- 被会话引用的 Target 删除失败；
- 会话消息、target、modelContext 与 revision 原子保存和可重复读；
- 多实例 generation 变化与非法新快照不沿用旧路由；
- disabled Target 可以由 Admin Tester 验证但不能被 Router 调用；测试结果不落库；Catalog
  故障时 Admin 仍能按 record revision 修复或删除配置。

### 19.3 Provider 契约

- 仓库级 fixture 增加 TargetRef 和跨 Target handoff 矩阵；
- 每个 `ModelProtocolAdapter` 必须通过统一结构事件、usage、状态完整替换、取消和错误测试；
- 每个协议再保留自己的请求编码、状态格式和传输清理测试；
- 使用真实密钥时执行跨 Target 文本、图片、展示 reasoning 保留、Tool Call/Result 与压缩链路；
- 无真实密钥的 CI 不把网络测试伪装成已执行，底层契约 fixture 仍是必过 Gate。

### 19.4 Browser 与 UI

- 初始化目录、default、无可用模型、恢复会话 current Target；
- 新草稿选择、持久化 handoff、取消/失败不变、revision 冲突；
- busy 状态拒绝切换，Execution 全程冻结 Target；
- handoff 前后完整 messages 数量和值不变；
- ContextManager 使用 current Target 的窗口并在新目标达到 80% 时先压缩；
- Admin/Widget 所有不可信字符串转义，凭据输入不回填；
- Starter 内置 Bundle 与源码构建产物逐字节一致。

## 20. 实施顺序

### 阶段 A：Core 契约与存储

1. 增加 Target 领域类型、Store/Catalog/AccessPolicy Port 与错误码；
2. 增加 JDBC 表、凭据保护、Manager、generation 和 Admin 单元测试；
3. 一次性替换 ConversationContext 与 JDBC 聚合，删除旧 JSON/属性路径。

完成 Gate：没有 Provider 路由或 UI 之前，Target 配置和会话聚合已经可独立验证；不暴露半成品
公共端点。

### 阶段 B：Provider 路由

1. 把 OpenAI Chat 协议实现收敛为 `ModelProtocolAdapter`；
2. 增加 Router，并让 Pipeline、Java Gateway、SSE 和 Compaction 全部经过它；
3. 删除 `ModelRequest.model`、全局 Provider 和全局 ContextCompactionSettings；
4. 增加 Target-aware 契约 fixture 和真实同协议多 Target 验证。

完成 Gate：所有运行时模型入口都无法绕过 Target，且不存在配置文件/JDBC 双路；Admin Tester
只能在管理权限边界复用 Adapter 诊断 disabled Target。

### 阶段 C：会话 handoff

1. 实现统一 ModelHandoffService；
2. 实现草稿和持久化端点；
3. 完成能力预检、状态清除、usage 估算、冲突和审计；
4. 扩展 ADR-004 的 handoff estimated 语义。

完成 Gate：切换失败保持旧快照，成功后完整历史逐值一致。

### 阶段 D：Browser、Widget 与 Admin

1. 增加 Catalog/Handoff Client、AgentState 和 reducer event；
2. 增加 Widget Selector 与确认交互；
3. 增加 Admin 模型配置、测试、启停和 default；
4. 更新 Demo 权限、真实验证、Bundle、Guide 与 Reference。

完成 Gate：面向用户的能力才可以在路线图标记完成。

### 阶段 E：后续协议

- R2 Anthropic Adapter 接入同一 Router，并实现 ADR-002 的独立状态重置；
- R3 Responses Adapter 接入同一 Router；
- 每个新协议增加真实跨 Provider handoff 验证，不修改 Browser 消息类型和 Agent Loop。

## 21. 验收条件

R1.8 只有同时满足以下条件才能标记完成：

1. 模型配置不再从 `patchbridge-agent.model.*` 读取，默认 JDBC 是唯一事实源；
2. Admin 能安全完成多个 Target CRUD、凭据、disabled 测试、启停和 default，诊断结果不落库；
3. 普通目录与每次真实调用都执行 ModelAccessPolicy；
4. Model Stream、Compaction 和 Java Gateway 等运行时调用全部通过同一 Router；Admin Tester
   只能在管理权限边界诊断精确配置，不能成为普通调用旁路；
5. Browser 不能用任意模型名绕过目录；
6. 会话原子保存 current Target、完整消息和 ModelContext；
7. handoff 保留完整历史、保留文本 checkpoint、清空私有状态并重新估算窗口；
8. 不支持的图片或 Tool 历史明确失败，无占位、删除或文本降级；展示 ReasoningBlock 完整保留、
   不发送给新 Target、不转换为正文，旧 Provider reasoning 状态随 handoff 清除；
9. Target 被停用、修改、删除或无权限时不自动选择 default；
10. 多 Tab、多实例、配置修订和进行中流式调用的边界都有测试；
11. 默认 Widget 和 Admin Console 都有入口，Demo 使用至少两个真实 Target 验收；
12. Guide、配置/HTTP/Browser/Runtime/Error Reference、架构总览、ADR、Quick Start、README、
    Demo 验收和路线图在同一次变更同步；
13. Java/Web 全量测试、所有 workspace 构建、Starter Bundle 字节一致和 Markdown 链接检查通过。

## 22. 被拒绝的方案

| 方案 | 拒绝原因 |
| --- | --- |
| 只在 Admin 修改现有 properties Bean | 仍然只有一个 Provider，不能表达会话 target、路由修订和状态边界 |
| 用 `ModelRequest.model` 作为 Target ID | 把上游模型名和配置记录 ID 混为一谈，旧客户端仍可绕过目录 |
| 每个会话只保存 modelId | endpoint、协议、能力、窗口和修订无法一致恢复 |
| 会话不保存 Target，永远使用当前 default | 管理员改 default 会静默改变所有旧会话，并可能误用 ModelState |
| 把 Target 塞入 `ModelState.data` | Runtime 无法路由却必须解析 Provider 私有状态，破坏 ADR-001/002 |
| 在 AssistantMessage 保存 provider/api/model/signature | 跨 Java/HTTP/Browser/JDBC 扩大公共契约；与现有独立 ModelState 决策冲突 |
| 为每个 Target 创建一套 Controller/Pipeline | 重复安全、审计、压缩和取消逻辑，违反统一入口 |
| properties 与 JDBC 同时存在并自动导入 | 产生双真相、首启竞态和凭据复制，违反当前唯一契约策略 |
| Target 不可用时自动使用 default | 用户以为仍在原模型，成本、数据出境和状态语义不可解释 |
| 跨 Provider 自动删除不支持块 | 历史语义无提示改变，与明确失败原则冲突 |
| 保存每个 routingRevision 的完整历史配置 | 第一版只需要显式接受当前修订；版本仓库、旧凭据生命周期和清理成本过高 |
| 把目录做成通用 Plugin Marketplace | 当前只有少量明确协议 Adapter，通用插件生命周期属于过度设计 |

## 23. 代价与风险

- 当前公共 `ModelRequest`、Conversation JSON、数据库 Schema 和配置项会发生一次 pre-release
  破坏性替换；这是消除旧单 Provider 路径所必需的成本。
- 宿主自定义 `ModelProvider` 需要迁移为 `ModelProtocolAdapter` 并配置 Target；不保留能绕过
  Catalog/Policy 的旧 Bean 优先级或包装兼容层。
- Target 的两个 revision 加上全局 settingsRevision 比单 revision 多两个概念，但分别解决记录
  覆盖、会话状态语义和 default 并发，不能安全合并成一个数。
- handoff 会不可恢复地丢弃旧 Provider 私有状态；确认 UI、审计和显式调用用于让代价可见。
- 管理员可配置 Server 出站 endpoint，天然具有 SSRF 与数据出境风险；Admin 权限、严格 URI/
  Header 校验、出口网络策略和审计必须共同承担，不能只依赖前端表单。
- routingRevision 在多轮 Execution 中途被管理员修改会让下一次模型调用失败，而不是继续使用
  旧快照；这是后端无跨请求执行状态与明确配置事实之间的有意取舍。
- 首次部署数据库没有 Target 时，聊天不可调用模型，但 Admin 仍可使用；框架不会播种假模型。

## 24. 实施前需要确认的三个产品决策

本方案推荐以下选择，但在 ADR-005 从 Proposed 转为 Accepted、开始改代码前，需要维护者明确确认：

1. **配置归属**：第一版采用“全局 Admin 管理 Target，普通用户只选择有权 Target”，不做用户
   自带 API Key 或租户级目录。
2. **旧配置迁移**：删除 `patchbridge-agent.model.*` 单模型源；Demo/pre-release 开发库按新
   Schema 重建。真实宿主如需保留已有会话，必须在自己的显式数据库迁移中为每个会话指定
   `ModelTargetRef`，框架不猜 default、不提供运行时兼容读取。
3. **切换兼容策略**：完整历史和文本 checkpoint 保留，旧 ModelState 清除；新 Target 无法
   原样编码当前工作上下文时拒绝切换，不采用 PI 的图片占位、reasoning 转正文或合成 Tool Result。

这三个选择分别决定安全边界、升级方式和跨 Provider 语义。其他字段命名和 UI 排布可以在不
改变架构的情况下迭代，不应阻塞核心方案评审。
