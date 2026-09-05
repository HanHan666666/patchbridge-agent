# 使用上下文压缩

## 目标

上下文压缩只缩短下一次模型调用看到的工作上下文，不删除聊天历史。用户仍能在 Widget 中查看
全部用户、Assistant 和 Tool 消息，服务端也会把完整 `messages` 与压缩后的 `modelContext`
作为同一 Conversation revision 保存。

模型工作上下文达到配置窗口的 80% 时会在下一次模型调用前自动压缩。默认 Widget 也提供
“立即压缩”，适合在长任务进入新阶段时主动建立检查点。自动和手动路径共享同一个
`ContextManager`，不会出现不同切分规则。

## 适用场景

- 长会话需要继续使用当前模型，但不能删除或隐藏用户历史；
- 宿主希望按模型真实窗口自动控制输入，并保留近期原始消息；
- 用户希望在任务阶段切换前主动建立摘要检查点；
- 自定义 Headless View 或 Provider 需要接入同一压缩契约。

## 前置条件

- Starter 已完成模型、身份和 Conversation 存储装配；
- 已确认所选模型的真实上下文窗口；
- Provider 的正常响应和摘要响应都能返回 token usage；
- 自定义 Provider 同时实现 `ModelStateProjector`。

## 操作步骤

### 1. 配置模型窗口

模型上下文窗口必须显式配置：

~~~yaml
patchbridge-agent:
  model:
    base-url: ${PATCHBRIDGE_AGENT_MODEL_BASE_URL}
    model: ${PATCHBRIDGE_AGENT_MODEL}
    api-key: ${PATCHBRIDGE_AGENT_MODEL_API_KEY:}
    context-window-tokens: ${PATCHBRIDGE_AGENT_MODEL_CONTEXT_WINDOW_TOKENS}
~~~

框架固定使用：

~~~text
automaticThresholdTokens = floor(contextWindowTokens × 0.80)
keepRecentTokens          = min(20_000, floor(contextWindowTokens × 0.20))
reservedOutputTokens      = floor(contextWindowTokens × 0.10)
~~~

例如 128,000 token 窗口在 102,400 token 自动触发，默认保留近期 20,000 token 并为输出预留
12,800 token；32,000 token 窗口在 25,600 token 触发，并保留近期 6,400 token、预留 3,200 token。

只有业务确实需要其他预算时才显式覆盖：

~~~yaml
patchbridge-agent:
  model:
    context-window-tokens: 128000
    keep-recent-tokens: 16000
    reserved-output-tokens: 16384
~~~

`keep-recent-tokens` 必须大于 0 且小于自动阈值；`reserved-output-tokens` 必须大于 0 且与自动
阈值之和小于窗口。自动阈值不能配置；Browser 从 `GET /ai/model/config` 读取同一份服务端
派生值。

### 最终窗口预算检查

每次模型调用前，Browser 会对最终出站输入做一次预算检查：工作消息（含 system 指令与摘要
检查点）、本轮 Tool 定义与输出预留之和不得超过“窗口 − 输出预留”。压缩成功不代表检查通过：

- 压缩后的工作上下文仍装不下窗口（例如最新安全段过大、摘要过长）时，本次请求明确失败，
  检查点不提交，完整历史保留；
- 首次调用尚无 Provider usage 时按完整出站输入估算，超大输入同样明确失败；
- 服务端摘要请求遵守同一预算，超限在调用模型前失败。

失败统一表现为 `CONTEXT_WINDOW_EXCEEDED`：终止本次请求，不自动重复压缩、不静默删除
历史、不更换模型，由用户缩短输入或开启新会话后重新发起。估算采用 UTF-8 字节上界口径，
会保守高估普通英文文本；新增协议时应验证相应计量边界。

### 2. 使用默认 Widget

完成一次正常模型响应后，Widget 顶栏会显示当前 token、窗口占比和来源：

- `Provider 计量` 表示最近一次正常响应的精确 usage；
- `压缩后估算` 表示刚完成压缩、尚未收到下一次正常响应；
- 达到 80% 后，下一次模型调用先显示“正在压缩上下文”；
- 有可淘汰的安全历史前缀时，可以点击“立即压缩”；
- 最近检查点会显示触发方式、压缩次数和完成时间。

手动压缩只允许在 Agent 空闲时进行。进行中可以用停止按钮取消；失败时完整聊天和旧模型工作
上下文保持不变。如果会话已持久化，只有摘要生成和 revision 保存都成功后，界面才提交新检查点。

### 3. 使用 Headless Controller

Headless View 从状态读取展示数据，并只调用 Controller 意图：

~~~ts
const state = controller.getState();

renderContextUsage({
  currentTokens: state.contextWindow.currentTokens,
  percentage: state.contextWindow.percentage,
  source: state.contextWindow.source,
  compactable: state.contextWindow.compactable,
  checkpoint: state.modelContext.checkpoint,
});

if ((state.status === 'idle' || state.status === 'done' || state.status === 'error')
    && state.contextWindow.compactable) {
  await controller.compactContext();
}
~~~

自定义传输继续通过 `transport` 注入。需要替换压缩 HTTP Adapter 时，使用
`contextCompactionGateway`；自动与手动压缩仍由框架创建的同一个 `ContextManager` 编排。

### 4. 满足自定义 Provider 契约

上下文摘要使用当前模型，不接受 Browser 指定其他模型。自定义 Provider 必须满足：

1. 正常 `message-stop` 和摘要结果都返回完整 token usage；
2. 摘要调用不得返回 Tool Call 或 `max-tokens`；
3. 实现 `ModelStateProjector`，把私有状态投影到摘要与保留消息；没有私有状态时明确返回 `null`；
4. 取消到达真实上游，取消后不发布部分摘要。

摘要模型会同时收到待压缩前缀和近期保留消息。它只把前缀写入检查点，但必须用保留消息核对
当前任务状态：如果前缀中尚未完成的事项已经在近期尾部完成、取消或被替代，检查点不得继续把
它列为剩余工作。近期消息仍会逐字出现在检查点之后，摘要只在关闭早期依赖时引用必要结论，
不复制完整尾部。

默认 OpenAI-compatible 请求已经开启流式 usage。缺失 usage、空摘要、状态投影失败和上游错误
都会显式失败，不估算替代、不清空状态、不重试或换模型。

成功压缩到下一次正常响应之间没有模型专用 tokenizer 基线，Browser 会按 UTF-8 字节上界
保守计量并标记为“压缩后估算”；该值可能高于实际 token，但不会冒充 Provider 精确 usage。

## 验证结果

1. 配置真实模型窗口并启动 Demo；缺少配置时应在启动阶段失败。
2. 完成一次真实模型响应，确认顶栏显示 Provider token 用量。
3. 点击“立即压缩”，确认聊天消息数量不变、检查点和压缩次数更新、来源变成“压缩后估算”。
4. 再发送消息，确认来源恢复为 Provider 计量。
5. 构造达到 80% 的长会话，确认下一次模型请求前自动进入压缩阶段。
6. 让摘要上游失败或制造会话 revision 冲突，确认旧检查点、完整历史和旧状态均未改变。

仓库的明确底层验证是 Browser `ContextManager` / Controller 契约测试、Java
`DefaultContextCompactionProvider` / Starter 集成测试、五个 Web workspace 构建和 Starter
Bundle 字节一致性；默认 Widget 本身就是 Demo 的可观察入口。

## 安全与边界

### 持久化升级说明

当前 pre-release 唯一 Schema 使用 `model_context_json`，不读取或双写旧的
`model_state_json`。本地开发数据库仍是旧结构时，停止应用并按当前 H2 / MySQL Schema 重建；
生产宿主应在自己的 Flyway / Liquibase 中执行受控迁移。

压缩失败不会提交部分摘要或候选状态，也不会继续发送可能溢出的模型请求。摘要固定使用当前
模型；框架不重试、不换模型、不清空 Provider 私有状态。窗口配置错误会造成过早或过晚压缩，
因此必须以目标模型官方配置为准。

## 常见问题

**压缩后还能查看旧消息吗？** 可以。完整 `messages` 不变，摘要只进入模型工作上下文。

**能否为摘要选择另一个模型？** 不能。当前契约固定使用当前模型，避免引入第二套状态、成本和质量语义。

**为什么第一次响应前不能手动压缩？** 没有 Provider usage 就没有真实窗口基线，框架不会用字符估算冒充正常计量。

**为什么按钮有时不可用？** Agent 必须空闲，并且当前工作上下文必须存在至少一个可摘要的安全前缀；Tool Call / Result 不能被切开。

## 相关文档

精确协议见[HTTP 与 SSE 契约](../reference/http-api.md)，Headless 状态见
[Browser API](../reference/browser-api.md)，设计原因见
[ADR-004](../architecture/adr/0004-context-compaction.md)。
