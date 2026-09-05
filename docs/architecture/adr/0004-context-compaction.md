# ADR-004：完整聊天历史与模型工作上下文分离的压缩机制

- 状态：Accepted（已实施）
- 决策日期：2026-08-27
- 替代关系：无；扩展 ADR-001 与 ADR-002
- 路线图：R1.7
- 适用范围：Browser `ContextManager`、Conversation 持久化、上下文压缩 HTTP API、当前模型 Provider、默认 Widget
- 关联决策：[ADR-001](0001-provider-neutral-runtime.md)与[ADR-002](0002-model-state-lifecycle.md)；不改变后端无 Agent Runtime 状态和 Provider 独占 `ModelState` 语义的边界

## 1. 背景

长会话最终会超过模型上下文窗口。直接删除旧消息会破坏用户可见历史、审计解释和刷新后的
体验；把全部消息继续发给模型则会导致请求失败。这里需要把两个原本混在一起的概念分开：

- 聊天历史是用户数据，必须完整显示和持久化；
- 模型工作上下文是一次 Provider 请求的输入投影，可以用摘要检查点替换较旧前缀。

开源实现提供了两项经过实践的参考：Codex 使用压缩摘要并保留近期输入，Pi 使用可重复合并
的上一份摘要、约 20,000 token 的近期消息和不会切开 Tool Result 的切分点。参考代码固定到
[Codex `694edc2`](https://github.com/openai/codex/blob/694edc23b22b4696400dc47663ecacd437623870/codex-rs/core/src/compact.rs)与
[Pi `ccfe79e`](https://github.com/badlogic/pi-mono/blob/ccfe79ed238674f760c986e3a61493aab794000a/packages/coding-agent/docs/compaction.md)。
本项目只采用“摘要 + 近期原始消息”的机制，不复制外部项目的会话存储或 Provider 协议。

## 2. 决策

### 2.1 双层会话上下文

`ConversationContext` 的唯一当前结构是：

```text
ConversationContext
├── messages                完整、可见、永不因压缩删除的聊天历史
└── modelContext
    ├── checkpoint          最近摘要、触发方式、时间和压缩计量
    ├── firstRetainedMessageId
    ├── modelState          与当前工作上下文严格对应的 Provider 私有状态
    └── usage               Provider 用量或压缩后的临时估算
```

压缩只替换 `modelContext`。`messages` 数组保持同一份完整历史；读取旧会话、刷新页面和
Widget 渲染都不会把摘要伪装成用户消息。两个字段仍使用同一 revision 和事务原子保存。

当前唯一 JDBC Schema 使用 `model_context_json`，不保留 `model_state_json` 双写、读取兼容或
迁移 fallback。项目仍是 pre-release；已有本地开发库需要按当前 Schema 重建，生产宿主应由
自己的 Flyway / Liquibase 执行明确迁移。

### 2.2 窗口配置与固定预算

宿主必须配置 `patchbridge-agent.model.context-window-tokens`。框架不猜模型窗口，也不维护
可能过期的模型名称映射。

- 自动压缩阈值固定为 `floor(contextWindowTokens × 0.80)`；
- 默认近期保留预算为 `min(20_000, floor(contextWindowTokens × 0.20))`；
- 宿主只有明确需要时才用 `model.keep-recent-tokens` 覆盖近期预算；该值必须大于 0 且小于自动阈值；
- 默认输出预留为 `floor(contextWindowTokens × 0.10)`，宿主可用 `model.reserved-output-tokens`
  覆盖；该值必须大于 0 且与自动阈值之和小于窗口（2026-09-05 审查 VA-05 补充）；
- Browser 初始化时从 `GET {basePath}/model/config` 取得服务端派生值，不另设前端默认值。

### 2.3 Browser 是工作上下文编排的唯一入口

`ContextManager` 集中负责自动与手动压缩，Controller、Runtime 和 View 不复制切分或计量规则：

1. system 消息逐字固定保留，不进入淘汰预算；
2. 非 system 消息按“非 tool 消息 + 紧随其后的连续 tool 结果”组成安全段，Tool Call 和 Result 不被切开；
3. 从最新段向前累计近期预算，同时必须留下至少一个可摘要段；
4. 重复压缩只处理上一检查点后的真实前缀，并把上一份摘要交给当前模型合并；
5. 摘要模型同时读取保留尾部作为状态对账依据，但只摘要被淘汰前缀；尾部已经完成、取消或
   替代的事项不得继续作为剩余工作写入检查点；
6. 下一次正常模型输入由 system、合成摘要检查点和保留的近期真实消息组成；
7. 切分点落在 assistant 开始处时显式传递 `splitTurn`，摘要必须覆盖该回合已淘汰的前半段。

每次模型调用前检查阈值；达到 80% 时，Runtime 进入 `compacting-context`，成功后才发起正常
模型请求。用户也可以在 Agent 空闲且存在安全淘汰前缀时调用 `compactContext()`。

### 2.4 摘要和 Provider 私有状态

`POST {basePath}/model/compact` 使用当前默认模型和同一条 `ModelGateway` 管线生成摘要，不提供
另选廉价模型的配置。摘要调用不携带 Tool，意外 `tool-use`、`max-tokens`、空摘要或缺少 usage
均为显式失败。

摘要请求中的 `retainedMessages` 不只是 Provider 状态投影依据，也作为只读对账上下文交给
摘要模型。检查点描述考虑保留尾部后的当前有效状态，而不是把切分边界时已经过期的“剩余工作”
继续传播；尾部仍逐字保留，因此摘要只引用解决早期依赖所需的结论。

Browser 不读取、清空或猜测 `ModelState.data`。当前 Provider 必须实现 `ModelStateProjector`，
把原状态投影成只与摘要和保留消息一致的下一状态；没有私有状态的 Provider 可以明确返回
`null`。默认 OpenAI-compatible Provider 只保留仍被引用消息的 reasoning 状态。

### 2.5 计量与失败原子性

正常 `message-stop` 必须包含 Provider token usage。缺失 usage 时当前模型调用明确失败；不能用
字符估算代替 Provider 基线，也不能等到真正溢出后再处理。

一次成功压缩后，Browser 在缺少模型专用 tokenizer 的边界使用“一个 UTF-8 字节按一个 token”
的保守上界展示新工作上下文，标记为 `estimated`；
下一次正常 Provider 响应到达后立即替换为 `provider` 精确计量。估算只承担两次 Provider
响应之间的阈值保护和 UI 展示，不改变正常响应必须有 usage 的契约。

**最终窗口预算检查（2026-09-05 审查 VA-05 确认）**：压缩成功不证明下一次请求装得下。
每次模型调用前，`prepareForModelCall` 对最终出站输入执行统一检查——工作消息（含 system
指令与摘要检查点）、本轮 Tool 定义与输出预留之和不得超过“窗口 − 输出预留”。有 Provider
基线时优先使用基线加新增消息估算，避免用字节上界重复计量已精确计量的历史；首次调用
尚无 usage 时按完整出站输入估算。检查失败（含保留段超出近期预算导致压缩结果本身超窗、
摘要过长、较大 system 或 Tool 目录、首次大输入）以 `CONTEXT_WINDOW_EXCEEDED` 终止本次
请求，检查点不提交、完整历史保留，由用户调整输入后重新发起；不自动重复压缩、不静默
删除历史、不更换模型。服务端摘要请求遵守同一预算：摘要输入（淘汰前缀、保留尾部与
固定指令）加输出预留超过窗口时在调用模型前失败。UTF-8 字节上界是有意的保守限制，
不构成覆盖所有 Provider/模态的数学保证；新增协议时应验证相应计量或收紧限制。

压缩请求、摘要协议、状态投影、预算检查或手动保存任一环节失败时：

- 不提交检查点；
- 不改变完整消息或旧 `modelContext`；
- 不重试、不更换模型、不清空状态、不发送未压缩请求；
- 手动压缩的 revision 冲突按普通会话冲突暴露，由用户重新加载后决定。

## 3. UI 决策

默认 Widget 顶栏展示当前 token、窗口占比、数据来源、80% 自动阈值、近期保留预算和最近检查点。
“立即压缩”只在空闲、有 usage 且存在安全历史前缀时可用。压缩期间显示独立进度，并复用停止
入口取消请求；错误进入现有 Agent 错误区域，不清空聊天内容。

Headless 宿主读取 `state.contextConfiguration`、`state.contextWindow` 和
`state.modelContext.checkpoint`，通过 `controller.compactContext()` 触发同一业务入口。

## 4. 被拒绝的方案

- **压缩后删除旧聊天消息**：破坏可见历史和恢复语义。
- **在 Server 保存跨请求 Agent 执行状态**：违反无后端 Agent Runtime 状态边界。
- **按模型名称猜窗口或在 Browser 写默认值**：配置容易过期并造成两端阈值不一致。
- **缺 usage 时按字符数继续运行**：估算不能证明真实 Provider 窗口。
- **Browser 通用地清空 `ModelState`**：会破坏需要连续状态的 Provider。
- **摘要失败后继续原请求、重试或换模型**：可能直接溢出或产生双重费用与不确定状态。
- **默认使用另一个摘要模型**：改变成本、身份和状态投影语义，且违背当前模型决策。

## 5. 验收约束

- 自动阈值、默认近期预算、显式覆盖和非法配置都有 Core 测试；
- 首次、重复、Tool 原子边界、system 固定、失败不变和缺 usage 都有 Browser 测试；
- 摘要成功、意外 Tool、缺 usage、投影与取消都有 Java Provider / Controller 测试；
- Conversation JSON 和 JDBC 只接受 `messages + modelContext` 当前结构；
- 默认 Widget 可观察自动状态并提供手动入口；
- 使用指南、HTTP / Browser / 配置参考、架构总览、Demo 验收和路线图同步更新。
