# ADR-005：可管理 ModelTarget、按会话路由与显式切换

- 状态：Proposed
- 决策日期：待评审
- 替代关系：无；扩展 ADR-001、ADR-002 与 ADR-004
- 关联里程碑：R1.8
- 详细方案：[《可管理模型目标、按会话路由与显式切换技术方案》](../designs/model-target-registry-and-switching.md)

## 1. 背景

PatchBridge 已有厂商中立消息、Provider Port、独立 `ModelState`、结构化模型流和完整
ConversationContext，但默认装配仍然只有一个由 `patchbridge-agent.model.*` 构造的
OpenAI-compatible Provider。可选 `ModelRequest.model` 只覆盖上游模型名，不能同时选择
endpoint、API 协议、上下文窗口、能力、凭据和状态解释器。

这意味着当前可以整体替换 Provider，却没有框架级的模型目录、服务端路由或按会话切换。
直接给单 Provider 配置加 CRUD 会形成 UI 可见的“多模型”，但 Model Stream、压缩、Java
Gateway 和 Conversation 仍然无法共享同一目标事实。

## 2. 决策

### 2.1 管理完整 ModelTarget，不管理孤立模型名

一个可执行目标统一包含：

```text
protocol + baseUrl + upstreamModel + capabilities
+ contextWindow/maxOutput/keepRecent + timeouts + server credential
```

公共引用为 `ModelTargetRef { targetId, routingRevision }`。Browser 只能选择目录中的引用，不能
再用任意模型字符串覆盖上游目标。`ModelRequest.model` 在实施时删除，不保留别名或兼容路径。

### 2.2 enabled、default 与 conversation current 分离

- 多个 Target 可以同时 enabled；
- 存在 enabled Target 时恰好一个 default；
- 每个可发送草稿或持久化会话恰好一个 current Target；无可用模型时空白草稿不可发送；
- default 改变只影响新会话，不改已有会话；
- 不可用时不按列表顺序或 default 自动替代。

### 2.3 ConversationContext 原子保存 ModelTargetRef

目标会话结构为：

```text
ConversationContext
├── messages
├── modelTarget
└── modelContext
```

`modelTarget` 是会话路由事实，`modelContext` 是检查点、保留边界、私有状态和计量。二者与完整
消息使用同一个会话 revision 原子保存。Target 不进入 `AgentMessage`，也不塞入不透明
`ModelState.data`。

会话保存当前 Target 是为了恢复和状态校验，不是永久绑定旧消息；用户可通过显式 handoff
改变 current Target。

### 2.4 一个 Router 服务所有运行时模型入口

新增 `ModelTargetStore`、`ModelTargetCatalog`、`ModelAccessPolicy`、`ModelProviderRouter` 和
按 protocol 注册的 `ModelProtocolAdapter`。Model Stream、Context Compaction 与 Java
`ModelGateway` 等运行时调用必须经过同一个 Router。

Router 每次调用按精确 TargetRef 检查存在、enabled、routingRevision、用户权限和协议 Adapter，
再把不可变 Resolved Target 交给 Adapter。任何失败都不换 Target、不重试备用 Provider。
`ModelAccessPolicy` 使用由可信入口构造、不可由 Browser 声明的调用上下文，区分已认证 HTTP
用户与可信 JVM 调用；HTTP 发起的嵌套压缩必须透传原 origin，匿名 HTTP 不能借 Java 无用户
入口绕过授权。

Admin 连通测试由受 `MODEL_MANAGE` 保护的独立 Tester 读取精确配置并复用相同 Adapter，因此
可以在启用前测试 disabled Target；它不经过普通 Router、不使 Target 可路由，测试结果也不落库。

### 2.5 显式 handoff 保留历史、清除私有状态

handoff 只能在 Browser Controller 空闲时发起。成功转移：

1. 完整 `messages` 不变；
2. 文本 `checkpoint` 与 `firstRetainedMessageId` 不变；
3. 旧 Target 的 `ModelState` 明确变为 null；
4. 使用服务端权威估算器按 ADR-004 的 UTF-8 上界重新生成 `estimated` usage；估算超过目标窗口
   时以 `MODEL_TARGET_CONTEXT_TOO_LARGE` 拒绝 handoff；
5. 工作上下文包含目标不能原样编码的 image 或 Tool 块时明确拒绝；展示用 ReasoningBlock 保留
   在完整历史但不作为跨 Provider 输入，也不转换为正文；
6. 持久化会话以 expectedRevision 保存并返回完整快照；任一失败保持原状态。

source Target 可以已停用、修订过期或不再对用户开放；离开它不要求恢复 source 权限，但目标
Target 必须重新解析和授权。`MODEL_SWITCH` 只记录脱敏事实，并沿用现有 AuditRecorder 可用性
契约，不把审计写入擅自升级为 handoff 的事务回滚条件。

不翻译 Provider 私有签名，不保留多份状态，不把 reasoning 转正文，不用图片占位，也不合成
Tool Result。ADR-002 的独立“仅重置 ModelState”操作仍是单独用户意图，不与切换混为一条
fallback。

### 2.6 配置使用服务端单一事实源

默认事实源改为 JDBC，Admin 是唯一在线变更入口。正式实施时删除旧
`patchbridge-agent.model.*` 单模型配置和单 Provider 自动装配路径，不做导入、双读或 JDBC
失败后回退 properties。

模型凭据只以 AES-256-GCM 密文落库，Admin 响应只给出认证类型和是否已配置。加密主密钥仍由
部署环境、Secret Manager 或宿主 Bean 提供，不能与密文存放在同一数据库。

### 2.7 Target 双修订与 default 设置修订各负其责

- `revision`：单条 Target 任意修改的 Admin 乐观锁；
- `routingRevision`：protocol、endpoint、上游模型、认证类型、能力或窗口等语义变化时递增。

显示名、同一上游账户内的凭据轮换、enabled、default 和超时不使会话 TargetRef 失效。切换
上游账户必须创建新 Target，不能伪装成普通密钥轮换。routingRevision 变化后，旧会话必须显式
handoff 到新修订，不能自动使用当前配置。

default 存在独立 `agent_model_settings` 记录，以 `settingsRevision` 防止两个管理页互相覆盖。
default 变化不修改任一 Target revision；启停或删除当前 default 时在同一事务校验 Target
revision 与 settingsRevision。

## 3. 原因

1. `ModelTargetRef` 把路由选择与稳定内容、私有状态分开，符合 ADR-001/002 的既有边界。
2. 单一 Router 保证 Browser、Java 和压缩不会形成三套路由或权限逻辑。
3. Target 目录让新增协议遵守开闭原则：增加 Adapter 与契约测试，不修改 Agent Loop。
4. 会话 current Target 与 routingRevision 让恢复、配置变化和 ModelState 误用都可检测。
5. 显式 handoff 保留用户数据，同时诚实表达不同 Provider 的私有状态通常不可翻译。
6. JDBC、乐观锁、generation 和密文沿用项目已验证的服务端配置模式，不需要引入配置中心或
   插件系统。
7. Target 双 revision 避免凭据轮换或改显示名无意义地阻断全部会话，又不允许路由语义静默
   变化；独立 settingsRevision 解决 default 自身的并发覆盖。

## 4. 代价与影响

- `ModelRequest`、Conversation JSON、JDBC Schema、Browser State、Starter 配置和 Admin UI 都会
  发生一次 pre-release 契约替换。
- 宿主原先直接替换单个 `ModelProvider` Bean 的扩展需要迁移为带稳定 protocol ID 的
  `ModelProtocolAdapter`；不保留可绕过 Catalog/Policy 的旧装配路径。
- 已有会话没有 TargetRef；Demo/开发库需要按新 Schema 重建，真实宿主必须执行显式迁移并选择
  Target，框架不能猜 default。
- handoff 会不可恢复地清除旧 ModelState；UI 与审计必须明示。
- 首轮结束时 title、TargetRef、完整消息和 ModelContext 一次创建，失败或取消的草稿不产生空
  Server 会话；普通保存不能改变 current Target。
- 管理端可配置服务端出站 endpoint，必须继续由 Admin 权限、URI/Header 校验、网络出口策略和
  审计共同控制 SSRF 与数据出境风险。
- Admin 在 Agent Tool Loop 中修改 routingRevision 可能让下一次 HTTP 模型调用明确失败；框架
  不为隐藏该竞态而在 Server 保存跨请求 Execution 状态。

## 5. 被拒绝的方案

| 方案 | 拒绝原因 |
| --- | --- |
| 只给现有 Model properties 做后台 CRUD | Pipeline 仍绑定一个 Provider，不能形成可信切换 |
| 复用 `ModelRequest.model` 作为配置 ID | 上游模型名与完整 Target 身份混淆，客户端仍可绕过目录 |
| 永远使用系统 default，不在会话保存 Target | default 变化会静默改变旧会话并误用 Provider 状态 |
| 把 Target 放入 `ModelState.data` | Router 必须解析本应不透明的 Provider 数据 |
| 给每条 Assistant 消息增加厂商签名与来源 | 扩大跨 Java/HTTP/Browser/JDBC 公共契约，违背 ADR-001 |
| 为第一版增加逐消息模型归属或切换时间线 | current Target 已足以决定后续路由；历史归因应在真实需求出现后用独立投影表达 |
| 跨模型自动删除或改写不支持内容 | 用户无法知道上下文语义已经变化，违背明确失败原则 |
| properties 与 JDBC 并存并自动迁移 | 产生凭据和默认模型的双真相 |
| 保存所有历史 Target 配置修订 | 第一版增加旧凭据、清理和版本选择复杂度，实际需求只要求显式接受当前修订 |
| 自动故障转移到其他模型 | 成本、数据边界、能力和 ModelState 均可能改变，属于独立路由产品 |

## 6. 与其他 ADR 的关系

- ADR-001 的 `Message + ContentBlock`、独立 `ModelState` 和 Provider 协议边界保持不变；
- ADR-002 的状态完整替换、format 不匹配明确失败和独立显式重置保持不变；本 ADR 只新增真正
  需求出现后的 Target 路由与 handoff；
- ADR-004 的完整历史/工作上下文分离保持不变；实施 handoff 时需要把成功切换后到下一次真实
  usage 之间补充为 `estimated` 的第二个合法过渡场景；
- 本 ADR Accepted 后，架构总览才加入 Router/Catalog 为当前组件；在此之前本文不代表能力已实现。

## 7. 验收约束

- Browser 或 Java 无法用任意模型名绕过 Target 目录；
- Catalog Descriptor、真实 Router 和 Context Compaction 使用同一配置快照；
- enabled/default/current 三层状态、Target 双 revision 和 settingsRevision 有明确测试；
- 每次模型调用重新执行 Target 解析和 ModelAccessPolicy；
- 会话原子保存 Target、完整消息和 ModelContext；
- handoff 成功后完整消息逐值不变、旧状态为空、窗口用量为新 Target 的估算；
- 具有稳定输入语义的不兼容 ContentBlock 明确失败，无降级或合成消息；展示 ReasoningBlock
  完整保留且不转正文；
- 配置凭据密文存储、只写不回显，日志/错误/审计不泄漏；
- 多 Tab、多实例、配置变更和流式调用竞态有契约测试；
- 默认 Widget、Admin Console 和真实 Demo 入口完成；
- Guide、Reference、架构总览、Quick Start、README 和路线图在实现变更中同步；
- 在上述条件完成前，路线图必须保持 R1.8 为待实现。
