# Runtime 契约参考

## 厂商中立 Agent Runtime

### 为什么使用 Message + ContentBlock

把图片、思考、Tool Call 和 Tool Result 压成一段字符串，会让 View、Provider、持久化
层反复猜测内容格式。公共消息现在只表达 Agent 语义：

```ts
const message = {
  id: 'message-42',
  role: 'assistant',
  blocks: [
    { type: 'reasoning', text: '可展示的思考摘要' },
    { type: 'text', text: '我先查询设备。' },
    {
      type: 'tool-call',
      callId: 'call-7',
      name: 'local.device_get',
      input: { serial: '100000000001' },
    },
  ],
};
```

`text / image / reasoning / tool-call / tool-result` 按产生顺序排列。OpenAI、Anthropic
或 Responses API 的消息字段由各自服务端 `ModelProvider` 编解码，不进入 Browser
Controller 或 Conversation 公共协议。

### 为什么 ModelState 不放进展示消息

推理签名、加密 reasoning item 和 continuation token 可能必须原样回传，但它们不是给
用户看的内容。`ModelState { format, data }` 与展示消息一起按会话 revision 原子保存；
Runtime 只透传，Provider 校验格式并解释数据。这样更换模型协议时无需修改 UI。
每个 `message-stop` 携带的是完整下一状态：非空值替换旧值，`null` 清空旧值。
生命周期由 Provider 按实际厂商、模型和配置判定，Runtime 不根据 Tool 环推测。

### 配置有界循环、Hook 与 Interceptor

Headless 用法：

```ts
const controller = createAgentController({
  endpoint: '/ai',
  runtime: {
    // 工厂入口只覆盖业务需要收紧的字段。
    limits: {
      maxModelCalls: 8,
      maxDurationMs: 120_000,
    },
    hooks: [{
      onEvent: (event, context) => auditQueue.push({ event, context }),
    }],
    onHookError: failure => diagnosticsQueue.push(failure),
    modelInterceptors: [{
      async *intercept(request, context, signal, next) {
        for await (const event of next(request, context, signal)) {
          yield event;
        }
      },
    }],
    toolInterceptors: [{
      intercept: (invocation, next) => next(invocation),
    }],
  },
});
```

静态 Widget 用法是在 bundle 加载前或元素连接前设置 JavaScript 属性：

```js
document.querySelector('patchbridge-agent').runtimeOptions = {
  limits: { maxModelCalls: 8, maxDurationMs: 120_000 },
  hooks: [{ onEvent: event => console.debug(event.type) }],
};
```

- `runtime.limits` 在 `createAgentController()` 和 Widget 中是部分覆盖；未写字段使用下表的统一工厂默认值。
- Hook 只同步观察稳定生命周期，不接收 token 增量或 ModelState，也不能改写 Tool 参数。
- Model / Tool Interceptor 用于包围对应调用，`next` 每次只能调用一次。
- 普通生命周期 Hook 抛错会明确终止本轮；已经选定的完成/取消/失败终态不能被终态观察
  Hook 改判，其异常进入 `onHookError`。未配置该回调时 Runtime 会在异常路径写入
  `console.error`；异步审计和 diagnostics 应写入宿主队列，不能阻塞模型流。

| 限额 | 工厂默认值 | 保护对象 |
| --- | ---: | --- |
| `maxModelCalls` | 16 | 单次 Execution 的模型调用次数 |
| `maxToolCalls` | 32 | 单次 Execution 累计接受的 Tool Call 数 |
| `maxDurationMs` | 300000 | 整轮时长，包含模型、Tool 和用户确认等待 |
| `maxModelOutputCharacters` | 100000 | 单次模型聚合的正文、展示思考与 Tool 参数字符 |
| `maxToolResultCharacters` | 100000 | 单个 Tool 回填模型的结果字符 |

直接构造 Runtime 是更低层的入口，不合并默认值；必须主动填齐五项：

```ts
const runtime = new DefaultAgentRuntime(model, {
  limits: {
    maxModelCalls: 8,
    maxToolCalls: 16,
    maxDurationMs: 120_000,
    maxModelOutputCharacters: 50_000,
    maxToolResultCharacters: 20_000,
  },
});
```

所有限额都必须是安全正整数，`maxDurationMs` 还不得超过 `2147483647`；
超限时明确失败，不静默截断、摘要、降级或自动重试。

### Outcome、Tool 预检与错误边界

`AgentExecution.result` 的非异常终态是唯一的 `AgentRunOutcome`：

- `completed` 表示 `end-turn` / `stop-sequence` / `other` 自然结束；
- `max-tokens` 表示 Provider 已封闭稳定消息，但内容可能不完整；Controller 会保存消息和
  ModelState，Widget 使用独立提示而不把它当成错误；
- `cancelled` 表示宿主或用户主动取消，不保存当前轮；
- 协议违约、资源超限、Deadline 和编程失败通过 `result` rejection 传播。

Runtime 在一条 Assistant 消息中任何 Tool 执行前，整批校验停止原因、Call ID 唯一性、
Tool 是否属于本轮快照、参数是否为完整 JSON 对象以及次数预算。任一项失败时当前批次零执行。
该承诺不能扩张为“所有限额都能回滚 Tool”：Tool 结果超限或 Tool 运行中 Deadline 到达时，
宿主副作用可能已发生；Runtime 只保证不发布该结果、不调用下一次模型，并隔离迟到完成/异常。

Tool 错误使用两条明确通道：宿主返回 `ToolCallResult.isError: true` 时生成 error Tool Result
并继续 Agent Loop；Adapter、Interceptor 或 Tool 本身 throw/rejection 时终止 Execution。需要让模型
处理的预期业务失败，应由 Tool Adapter/Interceptor 显式转换成 `ToolCallResult`。
返回对象必须精确包含字符串 `toolCallId`、字符串 `content` 与布尔 `isError`，且
`toolCallId` 必须等于当前调用 ID；Runtime 在发布结果事件前统一校验并冻结该对象，
不再接受 `null` ID、宽松类型或额外字段。

### HITL、取消、Deadline 和会话恢复

需要确认的 Tool 会产生带稳定 ID 的 `tool-confirmation` 中断。默认 Widget 显示批准 / 拒绝；
Headless View 读取 `state.pendingConfirmation` 后调用 `approveTool()` 或 `rejectTool()`。
“停止”调用当前 `AgentExecution.cancel()`，同一个 AbortSignal 会传给 Model 和 Tool。终态门
同时让不响应 Abort 的 Model、Tool 或确认等待立即失去发布资格；未完成的内容块、迟到结果和迟到异常
都不会进入稳定消息。宿主不主动取消时，整体 `maxDurationMs` Deadline 使挂起操作以
`AGENT_EXECUTION_TIMEOUT` 明确失败。

会话 API 以 `ConversationContext { messages, modelState }` 为一个整体保存。刷新页面只恢复
最后一次完整回合，不尝试恢复中断到一半的 Execution。这保持后端无 Agent Session，避免
框架演化成分布式工作流引擎。

完整接口与自定义 Model 示例见
[`web/packages/agent/README.md`](../../web/packages/agent/README.md)。核心决策见
[`ADR-001`](../architecture/adr/0001-provider-neutral-runtime.md)。

---

## Message + ContentBlock


不同模型协议对图片、思考、Tool Call 和 Tool Result 的编码完全不同。框架不把这些
状态压成字符串，也不把 OpenAI `tool_calls` 或 Anthropic `thinking` 放进公共类型：

```ts
const message = {
  id: 'message-42',
  role: 'assistant',
  blocks: [
    { type: 'reasoning', text: '可展示的思考摘要' },
    { type: 'text', text: '我先查询设备。' },
    {
      type: 'tool-call',
      callId: 'call-7',
      name: 'local.device_get',
      input: { serial: '100000000001' },
    },
  ],
};
```

当前稳定块包括：

- `text`：用户或 Assistant 正文；
- `image`：显式的 URL 或 Base64 图片来源；
- `reasoning`：Provider 明确允许向用户展示的思考文本或摘要；
- `tool-call`：已经完成 JSON 对象校验的调用；
- `tool-result`：与 `callId` 严格关联的成功或失败结果。

块顺序就是模型产生和 UI 展示的顺序。流式增量只存在于 Execution / AgentState 的临时
状态，收到完整 `block-stop` 与 `message-stop` 后才形成可以持久化的消息。

---

## ModelState 为什么独立


`reasoning` 块是展示内容，不等于模型继续推理所需的私有协议状态。签名、加密
reasoning item、continuation token 等状态放在独立信封中：

```ts
const state = {
  format: 'openai-chat-reasoning/v1',
  data: {
    reasoningByMessageId: {
      'message-42': 'Provider 私有内容',
    },
  },
};
```

Runtime 和 Conversation 只原样传递、保存 `ModelState`，不读取 `data`。Provider 必须
校验 `format`；不匹配时明确失败，不能丢弃状态后继续请求。这样接入 Anthropic 或
Responses API 时只新增 Provider Adapter，不需要修改 Controller、View 或稳定消息模型。

---

## 有界 AgentExecution 与取消


默认 Runtime 每次 `start()` 返回独立 `AgentExecution`。Execution 独占取消信号、
中断响应槽、完整资源预算和最终结果，不在 Engine 上保存“当前运行”单例。
直接构造 `DefaultAgentRuntime` 时必须填齐五项 `limits`：

```ts
const runtime = new DefaultAgentRuntime(model, {
  limits: {
    maxModelCalls: 8,
    maxToolCalls: 16,
    maxDurationMs: 120_000,
    maxModelOutputCharacters: 50_000,
    maxToolResultCharacters: 20_000,
  },
});
const execution = runtime.start(input, event => observe(event));

// 用户点击停止时，会同时取消 Model、Tool 和等待中的确认。
execution.cancel();
const result = await execution.result;
```

通过 `createAgentController()` 或 Widget 时，`runtime.limits` 是部分覆盖，未写字段使用工厂默认值：

| 限额 | 默认值 |
| --- | ---: |
| `maxModelCalls` | 16 |
| `maxToolCalls` | 32 |
| `maxDurationMs` | 300000 |
| `maxModelOutputCharacters` | 100000 |
| `maxToolResultCharacters` | 100000 |

模型调用次数、Tool 调用次数、整轮 Deadline、单次模型聚合字符和单个 Tool 结果均明确有界。
超限不静默截断、摘要、降级或重试。整批 Tool 预检失败保证当前批次零执行；Tool 结果超限
或运行中 Deadline 无法回滚已发生的宿主副作用，但保证不发布结果、不调用下一次模型，
并隔离迟到完成和异常。

`execution.result` 使用唯一 `outcome`：`completed` 是自然结束，`max-tokens` 是已封闭但可能
不完整的稳定结果，`cancelled` 是主动取消。Controller 会保存 `completed` 和 `max-tokens`
的消息/ModelState，只不保存 `cancelled`。协议、限额、Deadline 和编程失败通过 result rejection 传播。

通过 Controller / Widget 使用时，直接调用 `controller.abort()` 或界面的“停止”按钮。
取消与 Deadline 先关闭唯一终态门，再传递协作 `AbortSignal`。因此不响应 Abort 的 Model、Tool
或确认等待不能阻止 Execution 收敛，迟到事件、Hook 和异常不再对外发布。取消只保留已经完成的稳定消息，
不保存半截流式块，也不恢复被浏览器关闭到一半的执行。

Tool 的预期业务失败应显式返回 `ToolCallResult.isError: true`，Runtime 会将它作为 error Tool Result
交给模型继续处理。Tool/Adapter/Interceptor throw 或 Promise rejection 则终止 Execution，不会被包装成业务失败。
`ToolCallResult` 必须精确包含字符串 `toolCallId`、字符串 `content` 和布尔 `isError`，
且 ID 与当前调用一致；Runtime 在发布任何结果事件前完成运行时校验和冻结。

---

## Human-in-the-loop


未明确只读的纯前端 Tool，以及 `requireConfirmation: true` 的任意 Tool，会产生带稳定 ID
的 `tool-confirmation` 中断。Controller 把它投影到 `state.pendingConfirmation`：

```ts
controller.subscribe(state => {
  if (state.pendingConfirmation != null) {
    showApprovalDialog({
      tool: state.pendingConfirmation.tool,
      arguments: state.pendingConfirmation.arguments,
      approve: () => controller.approveTool(),
      reject: () => controller.rejectTool(),
    });
  }
});
```

底层自定义 Engine 使用 `execution.respond({ interruptId, value })`。响应 ID 不匹配、重复
响应或没有挂起中断时会明确失败；取消会消费等待槽，迟到批准不能重新启动 Tool。

---

## Tool Registry 与本轮快照


Controller 在每轮开始前刷新唯一 `ToolRegistry`，把定义和调用路由一起冻结成
`ToolRegistrySnapshot`。本轮模型看到什么，后续就只能调用同一 revision 的实现；
运行中注册或注销 Tool 只影响下一轮。

可选 Inspector 应绑定 `controller.getToolInspectionSource()`。执行中它返回
`current-execution` 快照，空闲时返回 `current-registry`，避免页面把新 revision 误报成
本轮模型已经拥有的能力。

纯前端 Tool 与 WebMCP 的完整用法见[文档中心](../README.md)。

---

## Hook 与 Interceptor


三类扩展点职责不同：

- `AgentHook`：同步观察稳定生命周期事实；`model-call-completed` 包含 usage、首 token 延迟与输出阶段耗时，但不接收 token delta 或 `ModelState`；
- `ModelInterceptor`：包围一次 Model 调用；
- `ToolInterceptor`：包围一次 Browser Tool 调用。

```ts
const controller = createAgentController({
  endpoint: '/ai',
  runtime: {
    limits: { maxModelCalls: 8, maxDurationMs: 120_000 },
    hooks: [{
      onEvent(event, context) {
        auditQueue.push({ event, traceId: context.traceId });
      },
    }],
    onHookError(failure) {
      diagnosticsQueue.push(failure);
    },
    modelInterceptors: [{
      async *intercept(request, context, signal, next) {
        metrics.modelCalls += 1;
        for await (const event of next(request, context, signal)) {
          yield event;
        }
      },
    }],
    toolInterceptors: [{
      async intercept(invocation, next) {
        enforcePagePolicy(invocation.tool, invocation.arguments);
        return next(invocation);
      },
    }],
  },
});
```

Interceptor 的 `next` 每次只能调用一次，防止重复计费或重复执行副作用。Hook 按注册顺序
同步调用；普通生命周期 Hook 抛错会使本轮明确失败。终态一旦选定就不允许观察 Hook
反向改判：异常进入 `onHookError`，未配置时只在该异常路径写入 `console.error`，其余 Hook
继续看到同一终态。异步上报应写入宿主队列，不能在 Hook 或 diagnostics 中等待网络。
Hook 收到的是独立深冻结数据，不能改写真实 Tool 参数。

---

## ConversationContext


会话保存和读取以一个 revision 原子处理消息与 Provider 状态：

```json
{
  "title": "查询设备",
  "revision": 3,
  "context": {
    "messages": [],
    "modelState": null
  }
}
```

服务端返回 `ConversationSnapshot { conversation, context }`。`modelState` 字段必须显式
出现，值允许为 `null`。状态损坏或 revision 冲突必须明确失败，不能改用另一份历史数据。