# ADR-003：Browser Agent Runtime 生产级执行守卫

- 状态：已接受，已实施
- 日期：2026-08-24
- 路线图：R1.5（R2 Anthropic 与 R3 Responses Provider 的前置条件）
- 适用范围：Browser `AgentExecution`、结构化 Model Stream 聚合、Tool 调度、Provider 契约验证
- 扩展：[ADR-001](0001-provider-neutral-runtime.md)——不改变其厂商中立和后端无 Agent Runtime 状态边界

## 1. 背景与代码结论

本决策以当前的 [`runtime.ts`](../../../web/packages/agent/src/runtime.ts)、
[`modelMessageAssembler.ts`](../../../web/packages/agent/src/modelMessageAssembler.ts)、
[`engine.ts`](../../../web/packages/agent/src/engine.ts) 和
[`modelClient.ts`](../../../web/packages/agent/src/clients/modelClient.ts) 为实现基线，不从外部项目反推一套新 Core。

当前 Runtime 已经不是原型，以下设计应当保留：

- `Message + ContentBlock` 与 `ModelState` 分离；
- Provider 把厂商协议转换成结构化 Model Stream；
- 每次 Execution 固定一份 `ToolRegistrySnapshot`；
- `maxModelCalls` 限制无限 Tool Loop；
- `AbortSignal`、Human-in-the-loop、Hook 和 Interceptor 已有独立契约；
- Controller generation 已阻止过期导航、执行和保存结果回写新状态。

对照基线实现后，R1.5 补齐的不是新的 Agent 编排模式，而是四类可验证的
运行不变量。这些不变量已进入生产实现、契约测试和真实模型 `max-tokens` 路径验收：

1. `stopReason`、Tool Call 形状和 Tool Call ID 必须一致，不安全的模型结果不得进入调度；
2. 除模型调用次数外，Execution 的 Tool 数量、持续时间和内存输入也必须有界；
3. 用户取消、超时、正常完成和失败必须只能产生一个终态，不合作的 Promise
   或 AsyncIterator 迟到后不得再发布事件；
4. 可交给模型处理的 Tool 业务失败，与必须终止 Execution 的编程/协议失败必须分开。

## 2. 目标与非目标

### 2.1 目标

- 任何 Tool 副作用发生前，Runtime 都已对本批模型结果完成全量校验；
- 任何一次 Execution 都能在明确上限内完成、取消或失败；
- UI、Hook、Call Trace 和 Controller 能区分正常完成、输出被截断与用户取消；
- 新增 Provider 必须通过同一份契约验收矩阵，而不是只验证各自的 happy path；
- 保持 Browser Agent Loop 和后端无状态边界，不为加固引入新服务或持久化执行引擎。

### 2.2 本阶段明确不做

- 不实现 Steering、Follow-up 队列、分支会话或多 Agent；
- 不实现运行中 Checkpoint、刷新恢复或 Durable Agent；
- 不实现 OS Sandbox、Browser Automation 或通用 Workflow/Graph；
- 不增加默认 Tool 并行、自动 Tool 重试或传输 fallback；
- 不在本阶段引入通用 `ToolDecisionPolicy`、OpenTelemetry Adapter 或上下文压缩；
- 不为尚未发布的旧 `AgentRunResult` 保留字段别名或双轨语义。

这些能力只能在真实宿主需求证明必要后独立设计，不作为“生产级”的默认同义词。

## 3. 统一的模型结果决策

`ModelMessageAssembler` 仍是结构化流进入稳定消息的唯一聚合器。它在返回
`AssembledModelMessage` 前完成“单次模型响应”的全部协议校验；Runtime 另外维护
“整个会话上下文/本次 Execution”的 Tool Call ID 和调用限额。

### 3.1 `stopReason` 决策表

| `stopReason` | Tool Call 数量 | 处理 |
| --- | ---: | --- |
| `tool-use` | 至少 1 个 | 通过本批预检后按模型顺序串行调度 |
| `tool-use` | 0 | `MODEL_PROTOCOL_ERROR`，不发布稳定 Assistant 消息 |
| `max-tokens` | 0 | 保留已完整封闭的消息和 ModelState，以 `max-tokens` 终态返回 |
| `max-tokens` | 任意正数 | `MODEL_PROTOCOL_ERROR`，禁止执行可能被截断的 Tool 参数 |
| `end-turn` / `stop-sequence` / `other` | 0 | 正常终止 |
| `end-turn` / `stop-sequence` / `other` | 任意正数 | `MODEL_PROTOCOL_ERROR` |

`max-tokens` 表示 Provider 已正常封闭一条消息，但用户看到的内容可能不完整。它不伪装成
`completed`，也不丢弃已产生的稳定内容；Controller/View 必须根据终态明确提示用户。

### 3.2 Tool 批次预检

一条 Assistant 消息中的任何 Tool 开始执行前，Runtime 必须一次性确认：

1. 本批所有 Tool Call ID 不重复；
2. 新 ID 不与输入 `ConversationContext` 或本次 Execution 已有 ID 重复；
3. 本批所有 Tool 都存在于当前 `ToolRegistrySnapshot`；
4. 加上本批后不超过 `maxToolCalls`；
5. 所有 Tool 参数已经是完整 JSON 对象。

任一项失败时，整批都不执行，避免前几个 Tool 已产生副作用后才发现本批无效。
稳定 Assistant 消息和 `model-call-completed` Hook 也只能在协议校验完成后发布。

## 4. 结果语义：成功、截断、取消和失败分开

`aborted: boolean` 无法表达模型输出被长度限制截断。当前实现已直接替换为有判别字段的
终态，不保留旧字段：

```ts
type AgentRunOutcome =
  | {
      readonly type: 'completed';
      readonly stopReason: 'end-turn' | 'stop-sequence' | 'other';
    }
  | { readonly type: 'max-tokens' }
  | { readonly type: 'cancelled' };

interface AgentRunResult {
  readonly messages: readonly AgentMessage[];
  readonly modelState: ModelState | null;
  readonly outcome: AgentRunOutcome;
}
```

- `completed` 只表示模型自然结束；
- `max-tokens` 是已封闭但需要 UI 提示的稳定结果；
- `cancelled` 只表示用户或宿主主动调用 `cancel()`，不展示为失败；
- 超时、资源超限、模型协议错误和编程错误仍通过 `result` rejection 显式传播，不伪装成普通结果。

Lifecycle Hook 和 Call Trace 使用同一份 `outcome`，不再维护第二套完成分类。
普通生命周期 Hook 抛错仍会使本轮明确失败；但一旦 Runtime 已经选定
`execution-completed` 或 `execution-failed`，终态观察 Hook 就不能反向改判结果。
终态 Hook 的异常通过独立 `onHookError(AgentHookFailure)` diagnostics 回调报告，
剩余 Hook 继续按注册顺序观察同一终态；未配置 diagnostics 时只在该异常路径显式写入
`console.error`。

## 5. 有界 Execution

### 5.1 最小限额集合

在已有 `maxModelCalls` 基础上，Runtime 只增加当前可以证明必要的四个限额：

```ts
interface AgentExecutionLimits {
  readonly maxModelCalls: number;
  readonly maxToolCalls: number;
  readonly maxDurationMs: number;
  readonly maxModelOutputCharacters: number;
  readonly maxToolResultCharacters: number;
}
```

语义如下：

- 所有值必须是安全正整数，配置错误在 Runtime 构造时失败；
- `maxDurationMs` 还必须不超过浏览器单次定时器可准确表达的 `2147483647`；
- `maxToolCalls` 按一次 Execution 累计，每批在任何副作用前整体检查；
- `maxDurationMs` 使用单调时钟，包含模型、Tool 和用户确认等待时间；
- `maxModelOutputCharacters` 累计单次模型调用的文本、展示思考和 Tool 参数字符串；
- `maxToolResultCharacters` 在 Tool 完成后、发布事件和回填模型前检查结果文本；
- 超限始终明确失败，不做静默截断、摘要、降级或重试。

Tool 可能在返回过大结果前已经产生业务副作用，Runtime 不能回滚宿主业务。因此该限额只保护
Browser 内存与后续模型上下文；Tool 本身的输出上限仍应由宿主接口就近控制。

直接构造 `DefaultAgentRuntime` 时必须在 `limits` 中显式给出五项完整限额。
`createAgentController()` 便捷工厂和 Widget 的 `runtime.limits` 则接受逐项覆盖，
未出现的字段使用同一组工厂默认值：

| 字段 | 工厂默认值 |
| --- | ---: |
| `maxModelCalls` | 16 |
| `maxToolCalls` | 32 |
| `maxDurationMs` | 300000（5 分钟） |
| `maxModelOutputCharacters` | 100000 |
| `maxToolResultCharacters` | 100000 |

这些值是默认 Widget 和 Demo 的有界安全预算，不是宿主业务 SLA。宿主应根据模型、
Tool 输出形状和用户确认时间明确收紧或放宽。

### 5.2 取消与迟到结果隔离

JavaScript 无法强制终止一个不合作的 Promise，因此 Runtime 同时使用两层机制：

1. 协作取消：继续把同一 `AbortSignal` 传给 Model、Tool 和等待中的人工确认；
2. 逻辑隔离：Execution 维护只读终态门闩，取消或超时先到达时立即收敛结果，
   为未完成的 Promise/Iterator 挂上清理处理，之后的 delta、Tool 结果、Hook 和异常均不再对外发布。

Runtime 串行等待 Model/Tool 时只登记一个短生命周期活动 waiter；操作先完成就立即注销，
取消或超时只唤醒当时仍活动的 waiter。它不会把每个模型事件都挂到同一个长期未决
Promise 上，因此事件数量不能线性累积停止信号订阅。

内部终态门只能从 `running` 单向进入 `settling` 再到 `settled`；对外业务结果由
`AgentRunOutcome` 或 `result` rejection 唯一表达。`cancel()` 保持幂等；Execution 完成后再取消不改变结果。
超时使用 `AGENT_EXECUTION_TIMEOUT`，不伪装成用户取消。

`message-stop` 仍是单次模型消息的封闭边界；已在超时前完整消费并校验的消息不会被迟到的
reader 清理错误推翻。

## 6. Tool 错误分类

不增加新的异常类层次，直接使用现有结果契约表达语义：

| Tool 结果 | 语义 | Runtime 处理 |
| --- | --- | --- |
| `ToolCallResult.isError === false` | 正常业务结果 | 生成 success Tool Result |
| `ToolCallResult.isError === true` | 宿主明确判定可交给模型理解的业务失败 | 生成 error Tool Result，继续 Agent Loop |
| 结果不是精确 `{ toolCallId, content, isError }`，字段类型错误或 `toolCallId` 与请求不一致 | Adapter/协议违约 | 以 `MODEL_PROTOCOL_ERROR` 终止，不发布 Tool Result |
| Promise rejection / throw | Adapter、Interceptor、协议或未预期基础设施失败 | 终止 Execution，原异常继续传播 |
| Abort 或终态门闩已关闭 | 取消/超时边界 | 不生成 Tool Result |

如果宿主需要把某类明确异常转换为模型可见的业务失败，应当在 Tool Adapter 或已有
`ToolInterceptor` 中显式返回 `ToolCallResult`。Runtime 不再把所有未知异常包装成
“工具未执行：未知错误”，以免隐藏框架编程错误。

用户在 Human-in-the-loop 中拒绝 Tool 仍是 Runtime 自身明确生成的可见 Tool Result，不属于异常。

## 7. 重试边界

- Agent Runtime 不重试模型调用，不重试 Tool 调用；
- Tool 默认重试次数继续为 0，无论注解是否声明幂等，都不由 Runtime 推测副作用安全性；
- `HttpModel` 保留现有的极窄传输语义：只在尚未交付任何框架事件的网络失败上重连一次；
- 一旦交付任何框架事件，绝不重连，避免重复文本或重复 Tool Call；
- 不增加通用 Retry Policy、指数退避、SDK 内层重试或另一条传输 fallback。

本阶段对现有规则做契约测试，不扩大重试能力。如果未来真实 Provider 证明需要更复杂的
`Retry-After` 或退避策略，由对应 Transport Adapter 独立设计，不污染 Agent Loop。

## 8. Provider 契约测试套件

多 Provider 不依赖人工复制测试。仓库级
[`model-provider-contract-v1.json`](../../../test-fixtures/model-provider-contract-v1.json) 固定可跨语言表达的
标准结构事件序列，Java OpenAI 协议内核与 Browser `HttpModel` 在各自原生测试工具中读取它。
请求编码、`ModelState` 裁剪/清空、取消和传输边界等不适合共享的用例仍由对应
Adapter 独立验证。fixture 只是测试数据，不发布成新的生产依赖，也不引入跨语言测试基类。

### 8.1 Java `ModelProvider` 契约用例

每个 Provider Adapter 必须验证：

- 文本、思考、Tool Call 与 Tool Result 编解码；
- block/message 事件顺序和唯一 `message-stop`；
- `stopReason` 与 Tool Call 数量一致；
- Tool Call ID 保留与参数分片聚合；
- `ModelState` 完整替换、清空和 format 不匹配失败；
- 取消能到达上游，取消后不再发布新事件；
- 上游在首个事件前失败、交付部分事件后失败与正常完成后清理失败的边界。

### 8.2 Browser Runtime 契约用例

统一验证：

- 本 ADR 第 3 节的全部决策表组合；
- 同响应、同 Execution 和历史上下文的 Tool Call ID 重复；
- Tool 批次预检失败时零 Tool 副作用；
- 五种限额超限路径与对应稳定错误码；
- Model、Tool 和确认等待忽略 Abort 时，Execution 仍能收敛且迟到事件为零；
- `ToolCallResult.isError` 继续循环，Tool Promise rejection 终止循环；
- `AgentRunOutcome`、Hook、Call Trace、Controller 提示和会话保存语义一致。
- 终态 Hook 或 diagnostics 自身抛错不会制造第二终态，且异常有独立可观察出口；
- Tool Adapter 返回值的字段、类型和调用 ID 关联在任何结果事件前完成运行时校验。

底层执行守卫不需要为了展示而再造一个 Demo 功能；验收证据是契约测试、一个真实模型
`max-tokens` 验证，以及现有 Widget 对截断终态的可见提示。

## 9. 错误码

实现时复用现有 `AgentError` 形状，不创造第二套错误体系：

| 错误码 | 触发条件 |
| --- | --- |
| `MODEL_PROTOCOL_ERROR` | 事件顺序、停止原因、Tool Call ID 或 Tool 批次形状违约 |
| `AGENT_MAX_MODEL_CALLS` | 达到已有模型调用上限 |
| `AGENT_MAX_TOOL_CALLS` | 本批预检发现将超过整轮 Tool 调用上限 |
| `AGENT_EXECUTION_TIMEOUT` | 整体 Execution 超过 `maxDurationMs` |
| `MODEL_OUTPUT_LIMIT_EXCEEDED` | 单次模型调用聚合字符超限 |
| `TOOL_RESULT_LIMIT_EXCEEDED` | Tool 结果文本超限 |

上述错误均为 `retryable: false`。是否重新发起一次新 Execution 由用户或宿主决定，框架不自动重试。

## 10. 实现顺序与验收

为了让每一步都能独立审查，R1.5 按以下顺序实施：

1. 先固化 `stopReason` 决策表、Tool Call ID 唯一性和整批预检；
2. 再替换 `AgentRunResult` 终态，同步 Controller、Hook、Call Trace 和 Widget；
3. 增加限额对象、整体 Deadline 与 Execution 终态门闩；
4. 收窄 Tool 异常语义，删除“任意异常都变成模型可见错误”的旧逻辑；
5. 固化 Provider/Runtime 契约验收矩阵与必要 fixture，再执行 Web 全量测试和真实模型验证。

五步均已落地。真实 OpenAI-compatible 模型以 8-token 输出预算触发 `max-tokens`；
Widget 在会话保存期间与保存完成后均显示稳定截断提示，Call Trace 同步记录
“输出已截断”，该轮只执行一次 Model 调用且未执行 Tool。

完成条件：

- 上述公共契约不保留旧 `aborted` 字段或兼容分支；
- 停止原因、Tool Call ID/名称/参数形状和 Tool 次数/模型次数等预检型违约，
  有测试证明当前批次未执行任何 Tool；
- Tool 结果超限和 Tool 运行中 Deadline 到达时，Runtime 不承诺回滚已发生的宿主副作用；
  测试只能且必须证明不发布 Tool 结果、不发起下一次模型调用，并隔离迟到结果/异常；
- 不合作 Model/Tool 的取消和超时用例能在有界时间内完成；
- Java Provider 和 Browser Runtime 对同一份契约验收矩阵的实现进入 CI；
- 现有文本、思考、Tool、HITL、取消、会话保存和 Call Trace 用例全部通过；
- 文档中心与对应的 Guide/Reference 说明限额配置、`max-tokens` 提示与 Tool 业务失败契约；
- 以底层契约测试和现有 Widget 可见终态作为验证方式，不增加与业务无关的 Demo 页。

## 11. 经验来源与取舍

本决策只吸收两个开源 Agent Loop 中与当前业务直接相关的设计：

- Pi Agent 的小循环、`max-tokens` 下不执行 Tool、有序 Tool 结果和可取消 Provider 边界：
  [agent-loop.ts](https://github.com/badlogic/pi-mono/blob/a470b121bf683b4c2b9fc0b3a7c807de7e0cfe9c/packages/agent/src/agent-loop.ts)；
- Codex Rust 的任务终态、取消清理、Tool 调度边界和致命/可见错误分类：
  [tasks](https://github.com/openai/codex/blob/fb0781b9eee6d2da741b984bed9dde95834d909d/codex-rs/core/src/tasks/mod.rs)、
  [tool registry](https://github.com/openai/codex/blob/fb0781b9eee6d2da741b984bed9dde95834d909d/codex-rs/core/src/tools/registry.rs)。

没有复用 Pi 尚在演进的 Durable Harness，也没有复制 Codex 的进程、Sandbox 和多 Agent 机制。
这些能力与“纯前端 Agent + 企业现有 Web API”的当前定位不一致。
