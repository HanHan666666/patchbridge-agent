# 思考模型 reasoning_content 回传规则调研报告

> 调研日期：2026-08-20。所有厂商规则均核对自当日抓取的官方文档原文（链接见文末）。
> 思考类接口规则演变较快（DeepSeek 曾在一年内把规则完全反转，见下文），接入新模型前建议复查官方文档。

## 1. 背景与问题

调研需要判断“无差别剥离历史思考字段”能否成为通用策略。当前架构没有采用该策略：
Browser Runtime 只处理厂商中立的 `AgentMessage + ContentBlock + ModelState`，厂商字段的
保留、回传与校验全部由服务端 `ModelProvider` Adapter 负责。

本次调研要回答两个问题：

1. **思考内容（`reasoning_content` / `reasoning`）在多轮对话、尤其是工具调用场景下，到底该不该回传给模型 API？**
2. **各主流厂商（DeepSeek、小米 MiMo、Kimi、Z.ai 智谱、Qwen 等）的要求分别是什么？是否如 DeepSeek 文档所述——携带 tools 的请求必须完整回传，否则 400 报错？**

## 2. 结论摘要（TL;DR）

1. **"工具调用场景必须回传思考内容"正在成为思考型模型的主流协议要求**，根源是这些模型按 interleaved thinking（交错思考）范式训练——模型在工具调用之间持续思考，历史思考是推理链的中间状态而非展示品。
2. **强制程度分三档**：
   - **硬性 400**：小米 MiMo、DeepSeek、Anthropic（后者为自有协议）；
   - **保留模式开启后必须回传（否则性能/缓存劣化，不报错）**：Kimi、Z.ai GLM；
   - **默认忽略、推荐剥离**：Qwen（阿里云百炼）、OpenAI 官方协议、OpenRouter。
3. **调研时对本项目的直接影响**：网关对接的 mimo-v2.5 思考模式默认开启，旧 Agent
   Loop 的“tool_calls → 工具结果回填 → 再次请求”循环命中 MiMo 的 400 条件；这说明
   无差别剥离策略与官方要求直接冲突，不能进入通用 Runtime。
4. **最终架构决策**：不在 Browser Runtime 实现一条“通吃厂商”的条件剥离规则。
   可展示思考进入 `ReasoningBlock`，协议续接状态进入独立 `ModelState`；每个 Provider
   按目标厂商要求编码历史状态（详见第 8 节）。

## 3. 字段与术语说明

- **`reasoning_content`**：DeepSeek 系、MiMo、Kimi、Qwen 等厂商在**响应**的 assistant 消息（非流式 `choices[].message` / 流式 `choices[].delta`）中输出的思考过程字段。
- **`reasoning`**：OpenRouter 等平台使用的同义字段。OpenRouter 官方说明 `reasoning_content` 是 `reasoning` 的别名，二者等价。
- **"回传"**：把历史 assistant 消息（含思考字段）重新放入下一轮请求的 `messages` 数组。
- 本项目 Browser Runtime 不再读取上述字段名。当前 OpenAI-compatible Provider 在自己的
  包私有协议 Adapter 中解析 `reasoning_content`，并转换为展示块和 Provider 状态。

## 4. 各厂商规则详解

### 4.1 小米 MiMo（mimo-v2.5 系列）——硬性 400，且为本项目在用模型

官方文档专门有一节 "Deep Thinking — Passing Back reasoning_content"，中文原文：

> "Agent 产品多轮对话开启深度思考时，若历史对话中包含工具调用，后续所有 user 交互轮次回传的、包含工具调用的 assistant 响应，**必须完整回传 reasoning_content 字段，否则 API 将返回 400 报错**。"

要点：

- **受影响范围**：mimo-v2.5-pro 与 mimo-v2.5 的深度思考**默认开启**；
- 官方文档列出受影响产品表：TRAE、Cursor、Roo Code、Codex、Aider、Cline——全部是默认丢弃思考内容的 Agent 工具；
- 正确做法（官方示例）：工具调用轮结束后，把**完整的** assistant 消息对象（含 `reasoning_content` 与 `tool_calls`）原样 append 进 `messages` 再发起下一轮请求；
- 开源仓库 [MiMo-V2-Flash](https://github.com/xiaomimimo/MiMo-V2-Flash) README 同样要求："the user must persist all history reasoning_content" in the messages array of each subsequent request。

### 4.2 DeepSeek——硬性 400，规则按"有无工具调用"分场景

现行 Thinking Mode 文档规则（注意：R1 时代"回传 reasoning_content 即 400"的规则已废止，方向完全反转）：

| 场景 | 规则 |
|------|------|
| 两条用户消息之间**无工具调用** | 历史思考内容**不需要**拼接进上下文；传了也会被 API **忽略**（不报错） |
| 中间 assistant 消息**携带 tool_calls**（即请求携带 tools 参数） | 其 `reasoning_content` **必须完整回传**到后续**所有**请求 |

原文：

> "the reasoning_content must be fully passed back to the API in all subsequent requests"
> "If your code does not correctly pass back reasoning_content, the API will return a 400 error."

### 4.3 Kimi / Moonshot（kimi-k2.6、kimi-k2.7-code、kimi-k3）——保留模式下必须回传

官方 Chat Completions 文档要点：

- 通用要求："多轮对话中若使用思考模式，请务必将每一轮 assistant 消息的 `reasoning_content` **原样保留**在 `messages` 中，否则模型可能**丢失推理上下文**"；
- kimi-k2.6 默认（`keep: null`）："服务端会**忽略**历史 turns 的 `reasoning_content`"——即默认状态下回传与否不报错；
- kimi-k2.7-code 与 kimi-k3 的 **Preserved Thinking 始终开启**，此时必须原样保留每轮历史 assistant 消息的 `reasoning_content`；
- 未提及不回传导致 400（仅 `keep` 参数传非法值会报错）。

### 4.4 Z.ai / 智谱 GLM（GLM-4.5 起）——交错思考默认开启，保留模式下强制

官方 Thinking Mode 文档要点：

- GLM-4.5 起默认支持 **interleaved thinking**：模型在工具调用之间、收到工具结果之后都会思考；
- 工具调用场景："thinking blocks should be **explicitly preserved and returned together with the tool results**"，"Remember to return the historical `reasoning_content` to keep the reasoning coherent"；
- **Preserved Thinking**（`clear_thinking: false`，Coding Plan 端点默认开启、标准 API 端点默认关闭）："you must return the **complete, unmodified** reasoning_content back to the API"，且不得重排或编辑，否则"performance may degrade and cache hit rates may be affected"；
- 违反回传要求的后果是性能下降与缓存命中率降低，**未见 400 报错**说明。

### 4.5 Qwen / 阿里云百炼——默认忽略，推荐剥离

官方"深度思考模型的用法"文档要点：

- "多轮对话中，模型**默认不会读取**历史消息 `messages` 数组中的 `reasoning_content`"；
- 需要模型参考历史思考链时，显式设置 `preserve_thinking: true`；
- 未提及不回传或回传导致报错，属三档中最宽松的一档。

### 4.6 对照组：Anthropic 与 OpenAI（非 OpenAI 兼容协议）

- **Anthropic Claude**：extended thinking + 工具调用时，**最后一条** assistant 消息的 thinking blocks（含防篡改签名）必须**原样回传**，丢弃或修改会直接报错；同为 interleaved thinking 范式。
- **OpenAI**：Chat Completions 协议 message 对象没有 reasoning 类字段（这是本项目"出站剥离"的原始依据）；思考的延续走 Responses API 的独立 reasoning item 机制（`previous_response_id` 或回传加密 reasoning item），思想相同、实现不同。
- **OpenRouter**：`reasoning`（别名 `reasoning_content`）定位为响应侧展示字段、按输出 token 计费，回传行为取决于其背后实际路由到的上游厂商。

## 5. 汇总对比表

| 厂商 / 模型 | 思考默认开启 | 工具调用场景回传要求 | 纯对话场景回传要求 | 违反后果 |
|------------|:---:|---------------------|-------------------|---------|
| 小米 MiMo（mimo-v2.5 系列） | 是 | **必须完整回传** | —（文档未区分，建议遵循工具调用规则） | **400 报错** |
| DeepSeek | 思考模型是 | **必须完整回传** | 不需要；传了被忽略 | **400 报错** |
| Kimi（k2.6 / k2.7-code / k3） | 视模型 | 保留模式开启后必须原样回传 | 默认服务端忽略 | 丢失推理上下文 |
| Z.ai GLM（GLM-4.5 起） | 是（交错思考） | 应随工具结果回传；Preserved Thinking 开启后必须完整原样回传 | 同左 | 性能下降、缓存命中率降低 |
| Qwen（阿里云百炼） | 视模型 | 默认忽略；`preserve_thinking=true` 才读取 | 默认忽略 | 无 |
| Anthropic（自有协议） | — | 最后一条 assistant 的 thinking blocks 必须原样回传 | 有专门回传规则 | 请求报错 |
| OpenAI（官方协议） | — | 协议内建（reasoning item），无需手工处理 | 同左 | — |
| OpenRouter | 视上游 | 随上游厂商 | 随上游厂商 | — |

## 6. 技术根源：为什么厂商开始要求回传

上述要求高度一致的厂商（MiMo、DeepSeek、Kimi、GLM、Anthropic）都采用了 **interleaved thinking（交错思考）** 训练范式：模型在一次回复中"思考 → 调工具 → 看结果 → 再思考 → 再调工具……"，历史思考是这条推理链的中间状态。若客户端把思考剥离后回传，模型拿到工具结果时推理链断裂，只能从工具结果"冷启动"重新推理——轻则质量下降、缓存失效（GLM 的表述），重则服务端直接校验拒绝（MiMo / DeepSeek 的 400）。

Qwen 与 OpenAI 显得宽松，是因为它们在**服务端**自行处理了这件事（Qwen 默认丢弃历史思考、OpenAI 把思考作为协议内独立 item 管理），而不是"思考不需要延续"。

**工程启示**：客户端“无差别剥离”曾是 R1 时代的正确做法（那时回传会 400），在
2026 年的交错思考时代已经反转为错误做法；但“无差别保留”也不是最优解（Qwen 官方
仍推荐剥离、OpenAI 兼容端点可能严格校验未知字段）。回传策略必须属于目标厂商的
Provider Adapter；不能在通用 Agent Loop 中猜一条跨厂商规则。

## 7. 对本项目的影响分析

调研暴露的不是一个字段判断 Bug，而是协议边界放错位置：如果 Browser Runtime 直接
拼装厂商消息，它必须同时知道 MiMo、DeepSeek、Anthropic、Responses API 等互相不同且
持续变化的回传规则。新增一个 Provider 就会修改 Agent Loop，违背开闭原则。

当前影响已经收敛为三层契约：

| 层 | 当前职责 | 明确不做 |
|------|------|------|
| Browser Runtime | 处理 `AgentMessage + ContentBlock`、有界 Tool Loop 与结构化流事件 | 不读取 `reasoning_content`、`tool_calls`、签名或加密 item |
| Conversation | 同 revision 原子保存 `messages + modelState`，按 Provider 的完整下一状态替换旧值 | 不解释、合并或根据厂商语义自动清理私有状态 |
| ModelProvider Adapter | 编解码目标厂商协议，校验 `ModelState.format`，返回完整下一状态或 `null` | 不把厂商字段泄漏到 Core / Controller / View |

当前 OpenAI-compatible Adapter 使用 `openai-chat-reasoning/v1`。它按框架预分配的
`responseMessageId` 保存本次 `reasoning_content`，再次编码历史 Tool Call 消息时按相同
message ID 精确恢复；框架 ID 本身不会发给上游。状态格式错配会明确失败，不能丢弃后
继续调用。

## 8. 已实施方案

最终没有实施“携带 `tool_calls` 时保留，其余剥离”的通用 Runtime 分支。那条规则可以
修复当前 MiMo / DeepSeek 问题，却无法表达 Anthropic 带签名 thinking block 或 OpenAI
Responses reasoning item，最终仍会让 Core 随厂商增长。

已实施的方案是：

1. 稳定展示消息改为有序 `ContentBlock`；`ReasoningBlock` 只保存 Provider 明确允许展示
   的文本或摘要。
2. 新增不透明 `ModelState { format, data }`，保存回传协议需要但不应展示的内容。
3. Model 端口只输出 `block-start / block-delta / block-stop / message-stop`；
   `message-stop` 携带完整的下一份 ModelState，`null` 表示 Provider 明确清空旧状态。
4. Conversation API 只使用 `ConversationContext { messages, modelState }` 原子读写。
5. OpenAI-compatible OkHttp 与 WebFlux Provider 各自在包私有 Adapter 内实现同一套
   reasoning 聚合与历史恢复，并用对称测试保护。
6. 后续 Anthropic、OpenAI Responses API 各自新增 Provider 与独立状态格式，不修改
   Browser Runtime。

这份报告保留各厂商事实调研；本节是当前代码的权威实施结论。架构依据见
[ADR-001](../architecture/adr/0001-provider-neutral-runtime.md)，Browser 使用方式见
[`web/packages/agent/README.md`](../../web/packages/agent/README.md)。

## 9. 参考链接

| 厂商 | 文档 |
|------|------|
| 小米 MiMo | [Passing Back reasoning_content（英文）](https://platform.xiaomimimo.com/docs/usage-guide/passing-back-reasoning_content) / [中文版](https://platform.xiaomimimo.com/docs/zh-CN/usage-guide/passing-back-reasoning_content) |
| 小米 MiMo | [MiMo-V2-Flash 开源仓库](https://github.com/xiaomimimo/MiMo-V2-Flash) |
| DeepSeek | [Thinking Mode](https://api-docs.deepseek.com/guides/thinking_mode/) / [多轮对话](https://api-docs.deepseek.com/guides/multi_round_chat) |
| Kimi / Moonshot | [创建对话补全（Chat Completions）](https://platform.kimi.com/docs/api/chat) |
| Z.ai / 智谱 | [Thinking Mode（英文）](https://docs.z.ai/guides/capabilities/thinking-mode) / [中文版](https://docs.bigmodel.cn/cn/guide/capabilities/thinking-mode) |
| Qwen / 阿里云百炼 | [深度思考模型的用法](https://help.aliyun.com/zh/model-studio/deep-thinking) |
| Anthropic | [Thinking](https://platform.claude.com/docs/en/build-with-claude/thinking) / [Extended Thinking](https://platform.claude.com/docs/en/build-with-claude/extended-thinking) |
| OpenAI | [Chat Completions API Reference](https://platform.openai.com/docs/api-reference/chat/create) / [Reasoning 指南](https://platform.openai.com/docs/guides/reasoning) |
| OpenRouter | [Reasoning Tokens](https://openrouter.ai/docs/use-cases/reasoning-tokens) |
