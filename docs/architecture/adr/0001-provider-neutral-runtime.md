# ADR-001：厂商中立 Agent Runtime 核心契约

- 状态：已接受
- 日期：2026-08-20
- 适用范围：浏览器 Agent Runtime、模型网关、会话持久化、默认 Widget
- 扩展：[ADR-002](0002-model-state-lifecycle.md)——ModelState 生命周期所有权与显式会话连续状态重置（2026-08-22 评审后接受，实现排期 R2）
- 扩展：[ADR-003](0003-browser-runtime-guards.md)——停止原因校验、有界执行、终态隔离与 Provider 契约测试（2026-08-24 已实施，R1.5 完成）

## 背景

当前 Browser Runtime 直接使用 OpenAI Chat Completions 的消息形状，并在
`DefaultAgentRuntime` 内解析 `choices[].delta`、`reasoning_content` 和
`tool_calls`。这种实现能支撑单一 OpenAI-compatible 模型，但会让 Anthropic、
OpenAI Responses API 以及带签名推理状态的模型不断向 Runtime 增加协议分支。

框架的两个核心目标是后端应用节点无状态和低侵入接入现有系统。为了维持这两个目标，
厂商协议必须由可替换的 Model Provider 负责，Browser Runtime 只处理稳定的 Agent 语义。
后端无状态表示模型上下文由请求携带，而不是把某个厂商 SDK 的运行时对象保存在服务端内存。

## 决策

### 1. 稳定消息采用 Message + ContentBlock

一条 `AgentMessage` 只声明稳定 ID、角色和有序内容块。第一版内容块只覆盖当前已经存在的能力：

- 文本；
- 图片；
- 可展示的思考内容；
- Tool 调用；
- Tool 结果。

稳定 Tool 调用的参数必须是 JSON 对象。模型流式输出中的参数片段可以暂时是字符串，
但只能存在于 Model 事件聚合器内；聚合完成且 JSON 校验成功后才能进入稳定消息。

消息 ID 是框架和宿主使用的稳定标识，用于渲染、持久化与链路关联。Model Provider
编码厂商请求时必须剥离该 ID，避免把框架元数据泄漏给模型。

暂不加入文件、音频、引用、缓存点、Guardrail 等当前没有需求的 Block。以后新增能力时，
通过扩展 `ContentBlock` 联合类型实现，不为未知需求预留无语义的任意字段。

### 2. Model State 与展示消息分离

`ReasoningBlock` 只表示允许用户看到和持久化展示的内容。模型下一次调用必须回传的签名、
加密 reasoning item、continuation token 等协议状态存入独立 `ModelState`：

- `format` 是 Provider 负责校验的稳定格式标识；
- `data` 是可 JSON 序列化的结构化值；
- Runtime、Controller 和 View 不解释 `data`；
- 状态格式不匹配时明确失败，禁止静默丢弃或降级为无状态调用。

`message-stop` 携带的 `modelState` 是下一次调用所需状态的完整替换，不是
patch：Provider 返回非空值时替换旧状态，返回 `null` 时清空旧状态。是否仍需要
续推状态只由对应 Provider、模型和配置判定；Runtime 不得根据 Tool 环或厂商
字段推测生命周期。

会话运行上下文由 `messages + modelState` 组成。浏览器在每次模型请求中携带两者，
Provider 返回下一份状态，因此模型网关不需要保存会话内存。启用 Conversation 模块时，
`modelState` 作为会话独立字段持久化，不能塞回展示消息或 DOM 状态。

### 3. Model Provider 独占厂商协议

Browser `Model` 端口只接收厂商中立请求，并产生框架结构化流事件。Java
`ModelProvider` 负责：

1. 把 `AgentMessage`、Tool 定义和 `ModelState` 编码为厂商请求；
2. 发起真实模型调用并管理上游取消；
3. 把厂商流转换成 `block-start / block-delta / block-stop / message-stop` 事件；
4. 从厂商响应中提取可展示内容与下一份 `ModelState`；
5. 把厂商错误转换成框架错误。

`DefaultAgentRuntime` 和 `HttpModel` 中不得出现 `choices`、`tool_calls`、
`reasoning_content`、`[DONE]` 等厂商字段。这些字段只能存在于对应 Provider Adapter。

### 4. Tool 定义与执行使用同一份快照

每轮执行只接收一个 `ToolRegistrySnapshot`。模型看到的 Tool 定义取自
`snapshot.tools`，Tool Call 也只能通过该 snapshot 调度。禁止同时传入另一份
`tools` 数组，避免定义列表与执行器来自不同 revision。

2026-09-05 审查（VA-01）补充：Browser 冻结的闭包还必须冻结它所代表的服务端能力
版本。后端动态 Tool（当前为 MCP）的定义携带服务端认可的定义/路由版本引用，
发现时下发、调用时原样回传；服务端在授权检查之前校验一致性，配置或定义发生
语义变化后旧引用明确失败而不是执行新目标。版本引用只是一致性凭证，
权限撤销与工具停用仍每次调用重新判定。MCP 使用独立共享密钥对路由、认证主体和定义
计算 HMAC-SHA-256：相同密钥保证多实例一致，公开引用不会成为低熵密码的无密钥校验器。
密钥由宿主外部配置提供，轮换使旧引用失效，不维护旧密钥兼容路径。

### 5. 取消和 Human-in-the-loop 属于单次 Execution

`AgentEngine` 创建独立的 `AgentExecution`。订阅、取消和中断响应都绑定到该执行，
不再依赖 Engine 内部的隐式“当前 run”。第一版 Human-in-the-loop 只保证当前浏览器
Execution 内暂停和恢复，不实现跨刷新、跨实例的 durable checkpoint。

中断使用带 ID、类型、Tool 定义、参数和结构化响应的明确对象。第一版只实现 Tool 确认，
但公共语义不再固定为 `Promise<boolean>`。

Tool Result 的 `execution` 是持久化业务事实，取值为真实结果、未执行、结果未知或结果未回填。
Runtime 从完整历史重建未知调用的核实约束；Controller 不保存另一份临时集合。这只是恢复
核实约束，不恢复旧 Execution 或自动补执行；压缩也不能清除这项事实。

### 6. 扩展点保持有限

- Hook 只读观察生命周期事件，适用于日志、指标、Tracing 和 Debug UI；
- Model Interceptor 只包围模型调用；
- Tool Interceptor 只包围 Tool 调用；
- 扩展点不得直接获得 Controller、DOM 或 Runtime 私有可变状态。

不提供一个无所不能的 Plugin Context，也不在本阶段实现 Graph、Swarm、多 Agent、
长期记忆、Agent-as-Tool 或持久化执行恢复。

## 当前契约规则

项目尚未公开发布，`AgentMessage + ContentBlock + ModelState` 是唯一领域契约。TypeScript、
Java Model Gateway、Conversation API、JDBC Schema、Widget、Demo、测试和文档必须使用同一
结构，不提供其他消息 DTO、字段别名、数据库双写、自动迁移或失败后的替代实现。

非法形状和未知字段必须明确拒绝。严格输入校验用于保护当前契约，不承担历史协议识别职责。
完整的架构边界与未发布阶段契约策略见
[《架构设计：模块化单体、六边形架构与设计模式》](../overview.md)。

## 验收标准

- Runtime 不解析任何厂商 SSE JSON；
- Java Core 不再把公共消息契约描述为 OpenAI 风格 Map；
- Tool 参数在稳定消息中为 JSON 对象；
- 展示 reasoning 与 Model State 分开存储和传递；
- Provider 能针对 Tool Call 正确保留或回传厂商要求的推理状态；
- 取消后不再提交迟到事件、稳定消息或保存结果；
- Debug Tool 列表与本轮模型实际收到的 Tool snapshot 一致；
- 文档和 Demo 覆盖核心契约、Provider、自定义扩展、中断与取消的使用方式。
