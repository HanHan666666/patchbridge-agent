# ADR-002：ModelState 生命周期所有权与显式会话连续状态重置

- 状态：已接受（2026-08-22 评审后修订），实现排期 R2
- 日期：2026-08-22
- 关联：[ADR-001](0001-provider-neutral-runtime.md) §2（ModelState 与展示消息分离）、路线图 R2

## 1. 背景与问题

ADR-001 用不透明 `ModelState { format, data }` 隔离 Provider 续推状态：
Runtime、Controller、Conversation 和 View 只传递或保存，不读取厂商字段。这个边界
仍然正确，不需要为新 Provider 打开。

多 Provider 评审暴露的真实问题是：

1. 当前文档没有把“下一份状态的完整替换语义”写成不变式，容易让实现者
   误以为 Runtime 需要保留、合并或推测旧状态的寿命。
2. 普通续跑遇到 `format` 不匹配时必须明确失败，但企业在模型下线、供应商
   切换或上游状态过期后，缺少一个由用户明确触发的状态处置路径。

`ModelState` 本来就是可空的，`message-stop` 也已经可以返回 `null`。因此第一个
问题不需要增加新字段；应该明确已有契约，并把生命周期责任留在最了解
厂商协议的 Provider 内。

## 2. 决策

### 2.1 ModelState 公共形状保持不变

`ModelState` 仍然只有两个字段：

```text
ModelState
├── format: string
└── data: JSON value
```

不增加 `scope`、TTL 或任何需要 Runtime 理解的厂商生命周期字段。

- `format` 是 Provider 校验的协议与版本边界。
- `data` 可以包含 Provider 需要的签名、加密 reasoning item、续推标识、上游过期
  信息或其他私有元数据；公共层不读取它们。
- Provider 收到非空状态时，必须在调用上游前校验 `format` 和自己所需的
  `data` 形状；不匹配时明确失败。

### 2.2 message-stop 携带完整的下一状态

每个成功的 `message-stop` 都必须对下一次模型调用给出完整决策：

| Provider 输出 | 语义 | Runtime 行为 |
| --- | --- | --- |
| 非空 `ModelState` | 下一次调用仍需要该完整状态 | 原样替换旧状态 |
| `null` | Provider 确认下一次调用不再需要续推状态 | 清空旧状态 |

这是**完整替换**，不是 patch 或 merge：

- Runtime 不得在 Provider 返回 `null` 时保留上一份状态。
- Runtime 不得根据 Tool Call、`stopReason`、`format` 或 `data` 自行推测何时清理。
- Provider 需要历史状态时，必须把仍然有效的部分放入新 `ModelState`；可以在
  自己的 `data` 内裁剪过期项。
- 生命周期由具体 Provider、模型和配置决定。例如一个 Anthropic Adapter 可能在
  Tool 环中保留签名 thinking，但是否跨用户轮次保留必须遵循当前模型规则，
  不能由通用 Runtime 假设。

模型失败、协议失败或在 `message-stop` 之前取消时，不产生新状态。取消发生在已完成的
`message-stop` 之后时，该完整响应及其下一状态仍是稳定事实，继续遵守 ADR-001 的唯一完成边界。

### 2.3 过期状态明确失败

某些状态依赖上游保留窗口，例如 Responses API 的服务端续推链。Provider 可以把
已知的过期信息放在不透明 `data` 内，或根据上游的明确错误判定失效。

- 过期或不可续推映射为稳定、不可重试的 `MODEL_STATE_EXPIRED`。
- Provider 不得在同一次普通调用内丢弃状态后自动重试无状态请求。
- 用户可以根据明确错误选择执行 §2.4 的重置，也可以保留原会话不动。

### 2.4 显式会话连续状态重置

新增一个**必须由用户明确触发**的应用操作，概念签名为：

```text
resetModelState(ownerKey, conversationId, expectedRevision)
    -> ConversationSnapshot
```

HTTP 边界不接受客户端传入 `ownerKey`；它仍由服务端从宿主可信身份解析。该操作
必须在 Repository 事务内完成以下唯一转移：

1. 在 `ownerKey + conversationId` 范围内读取当前快照。
2. 严格校验 `expectedRevision`；不一致返回冲突，不静默覆盖。
3. `messages`、标题和会话元数据保持不变，仅将 `modelState` 设为 `null`。
4. 无论旧状态是否已为 `null`，都将 revision 加一。该 revision 是并发屏障，用于
   使其他 Tab 或迟到 Execution 的旧保存明确冲突。
5. 返回包含新 revision、完整消息和 `modelState = null` 的同一事务快照。

Browser Controller 必须先取消当前 Execution，再发起重置，并用返回的完整快照替换
本地 `ConversationContext`。这个赋值不是解析 `data`，不破坏不透明边界。
尚未持久化的新会话没有服务端 revision；Controller 取消 Execution 后可以对本地上下文
执行同一个显式转移，保留消息并将 `modelState` 设为 `null`。

重置的不变式：

- 它是单独的用户意图，不是普通模型调用的 fallback。
- 普通续跑中 `format` 不匹配或状态过期仍然明确失败。
- 重置后不解析、不翻译、不备份 Provider 私有状态。
- 重置不选择目标 Provider 或模型。当前框架使用宿主全局配置或自定义
  `ModelProvider` Bean；未来如果需要按会话路由，应另行设计 `ModelTarget` 与
  `ModelProviderRouter`，不将路由信息塞入 `ModelState`。
- 重置后的下一次调用由当前 Provider 从稳定消息重新编码。如果目标不支持
  某种 `ContentBlock`，必须明确失败，不得静默删除。

对用户必须明示的代价：历史展示消息保留，但未进入 `ContentBlock` 的签名 thinking、
加密 reasoning item、续推链和缓存上下文会不可恢复地丢弃，下一家模型只能
从稳定消息重建上下文。

## 3. Provider 与框架的责任边界

| 责任 | Provider | Runtime / Controller / Conversation |
| --- | --- | --- |
| 校验 `format` 与 `data` | 是 | 否 |
| 判定下一次调用需要什么状态 | 是 | 否 |
| 裁剪 Provider 私有状态 | 是 | 否 |
| 将下一状态映射为完整 `ModelState` 或 `null` | 是 | 否 |
| 完整替换、传递和原子保存状态 | 否 | 是 |
| 解释 `data` | 是 | 否 |
| 根据用户显式意图把整份状态设为 `null` | 否 | 是 |

这个边界使新 Provider 可以独立决定是使用短期 Tool 环状态、会话级续推链，
还是完全无状态，而不修改 Browser Runtime 或 Conversation schema。

## 4. 与跨 Provider 切换的关系

当前版本没有“一个会话选择一个 Provider”的路由契约。因此本 ADR 不把状态重置
包装成尚不存在的“迁移到目标 Provider”。它只保证：

1. 宿主通过当前配置或自定义路由切换 Provider 后，旧状态不会被静默交给新
   Provider。
2. 用户可以显式重置续推状态，然后让当前 Provider 从稳定消息开始下一轮。
3. 如果未重置，新 Provider 遇到不兼容 `format` 必须失败，不得自动完成第 2 步。

未来的按会话多 Provider 选择是独立需求，必须先明确路由、权限、模型可用性与
配置归属，再用新 ADR 决策；不在当前 `ModelState` 契约中预留猜测字段。

## 5. 对标与被拒绝的方案

pi（badlogic/pi-mono）把 thinking（含签名）归一为一等消息 part，会话是自包含的统一
格式 JSONL，天然便于换模型续跑。该取舍在单进程 TypeScript 与单一消费者边界下
成立；PatchBridge 还需要维持 Java Core、Browser 包、HTTP 契约、JDBC 与宿主自定义 View
的独立演进，因此不把厂商签名提升为公共 `ContentBlock`。

| 方案 | 拒绝原因 |
| --- | --- |
| 给 `ModelState` 增加 `scope` 并由 Runtime 清理 | `null` 已能表达完整状态失效；额外字段会把 Provider 判断泄漏到公共层 |
| Runtime 根据“无待处理 Tool Call”自动清理 | 不同模型跨工具环的 thinking 保留规则不同，通用循环不能推测 |
| `format` 不匹配或过期时自动无状态重试 | 静默丢失推理上下文，违反明确失败与无 fallback 原则 |
| 翻译两个 Provider 的私有状态 | 签名、加密 item 和上游 ID 通常不可翻译，会让 Core 依赖厂商协议 |
| 同时按 `format` 保存多份状态 | 制造多真相、过期垃圾与不可预期的恢复选择 |
| 现在引入按会话 `ModelTarget` | 当前没有按会话多 Provider 需求；提前加路由、权限和配置聚合属于过度设计 |

## 6. 好处与代价

好处：

1. 不扩大公共契约；Java、TypeScript、HTTP 和 JDBC 继续使用同一个
   `ModelState { format, data } | null`。
2. 符合开闭原则：新厂商的状态保留策略只修改自己的 Provider 和专项测试。
3. 模型网关仍然不保存会话内存；状态随请求显式传递，不破坏后端节点无状态目标。
4. 显式重置通过 revision 形成线性化屏障，迟到保存不能把旧状态写回。
5. 错误、状态丢弃和 Provider 切换都是可观察的显式事实。

代价：Provider 必须对自己的状态转移负完整正确性责任；通用 Runtime 不再尝试用
公共规则修正 Provider 错误。这是有意的责任分配，而不是缺少兜底。

## 7. 实现排期与验收要点

排期 R2（与 Anthropic Provider 同期）。本 ADR 不改变 `ModelState` 公共形状，但需要
把完整替换、过期错误和显式重置固化为可验证契约。

验收（进入 R2 完成标准）：

- Java Core、TS 类型、HTTP 与 JDBC 仍精确使用 `ModelState { format, data } | null`，不增加
  `scope` 或厂商字段。
- Runtime/Assembler 回归测试证明非空下一状态完整替换旧值，`null` 必须清空旧值。
- Anthropic Provider 专项测试覆盖 Tool 环签名 thinking、不同模型/配置的跨轮保留策略、
  安全清空边界和状态形状严格校验。
- `format` 不匹配与 `MODEL_STATE_EXPIRED` 均明确失败；回归测试证明 Provider 不会
  自动无状态重试。
- Repository 重置在 owner 范围和 `expectedRevision` 下原子保留消息、清空状态并推进
  revision；旧状态已为 `null` 也必须推进 revision。
- Browser Controller 在重置前取消 Execution，使用服务端完整快照替换本地状态；迟到
  保存与多 Tab 重置必须冲突而不能覆盖。
- 尚未持久化会话的本地重置有状态机测试，不影响历史消息。
- 重置后使用当前 Provider 的支持范围进行重新编码；不支持的 `ContentBlock` 明确失败。
- 文档中心与对应的 Guide/Reference 说明重置的不可恢复代价、冲突处理和操作方式；Demo 提供
  可观察的显式入口。
