# `@patchbridge-agent/agent`

这是 PatchBridge Agent 的 Headless 浏览器核心。它提供 Controller、显式状态机、
厂商中立 Model 端口、Unified Tool Registry 和默认 Agent Runtime，不包含 DOM 或视觉样式。

设计决策的完整记录见
[ADR-001：厂商中立 Agent Runtime 核心契约](../../../docs/architecture/adr/0001-provider-neutral-runtime.md)。
上下文压缩的双层会话结构与失败边界见
[ADR-004：完整聊天历史与模型工作上下文分离的压缩机制](../../../docs/architecture/adr/0004-context-compaction.md)。

## 最小接入

```ts
import { createAgentController } from '@patchbridge-agent/agent';

const controller = createAgentController({ endpoint: '/ai' });
const unsubscribe = controller.subscribe(state => renderAgent(state));

await controller.initialize();
// 如需切换：await controller.switchModelTarget('部署目录中的目标 ID');
await controller.sendMessage('查询当前告警设备');

// 页面模块卸载时释放请求、Execution 和订阅。
unsubscribe();
controller.dispose();
```

Controller 是 Browser Application Service，也是 `AgentState` 的唯一所有者。View 只订阅
状态并调用具名用户意图，不应直接维护第二份消息、确认或流式状态。模型目标来自服务端脱敏目录，当前会话按 `ModelTargetRef` 路由；仅在空闲时调用 `switchModelTarget(targetId)`。

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

## Model 与结构化流

`Model` 是 Browser Runtime 依赖的最小厂商中立端口：

```ts
import type { Model } from '@patchbridge-agent/agent';

const model: Model = {
  async *stream(request, context, signal) {
    // 自定义实现应调用自己的框架网关；这里仅展示结构化返回契约。
    yield { type: 'block-start', index: 0, block: { type: 'text' } };
    yield {
      type: 'block-delta',
      index: 0,
      delta: { type: 'text', text: '完成' },
    };
    yield { type: 'block-stop', index: 0 };
    yield {
      type: 'message-stop',
      stopReason: 'end-turn',
      usage: { inputTokens: 12, outputTokens: 1, totalTokens: 13 },
      modelState: null,
    };
  },
};

const controller = createAgentController({ endpoint: '/ai', model });
```

内置 `HttpModel` 请求 `/ai/model/stream`，只接受框架的 `block-start`、
`block-delta`、`block-stop`、`message-stop` 和 `error` 事件。HTTP Adapter 不解析
厂商 chunk；OpenAI Chat 等协议字段只存在于服务端 `ModelProvider` Adapter。

仓库级
[`model-provider-contract-v1.json`](../../../test-fixtures/model-provider-contract-v1.json)
由 Browser `HttpModel` 与 Java OpenAI 协议内核共享标准事件序列。各 Adapter 仍独立测试请求编码、
`ModelState`、取消与传输生命周期；fixture 不进入生产 bundle，也不要求新 Provider 继承测试基类。

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

## 有界 AgentExecution 与取消

默认 Runtime 每次 `start()` 返回独立 `AgentExecution`。Execution 独占取消信号、
中断响应槽、完整资源预算和最终结果，不在 Engine 上保存“当前运行”单例。
直接构造 `DefaultAgentRuntime` 时必须填齐五项 `limits`：

```ts
const contextManager = new DefaultContextManager(
  new HttpContextCompactionGateway('/ai'),
);
await contextManager.loadConfiguration();

const runtime = new DefaultAgentRuntime(model, {
  contextManager,
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

## Tool Registry 与本轮快照

Controller 在每轮开始前刷新唯一 `ToolRegistry`，把定义和调用路由一起冻结成
`ToolRegistrySnapshot`。本轮模型看到什么，后续就只能调用同一 revision 的实现；
运行中注册或注销 Tool 只影响下一轮。

可选 Inspector 应绑定 `controller.getToolInspectionSource()`。执行中它返回
`current-execution` 快照，空闲时返回 `current-registry`，避免页面把新 revision 误报成
本轮模型已经拥有的能力。

纯前端 Tool 与 WebMCP 的完整用法见[文档中心](../../../docs/README.md)。

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

## ConversationContext 与上下文压缩

会话保存和读取以一个 revision 原子处理完整消息与模型工作上下文：

```json
{
  "title": "查询设备",
  "revision": 3,
  "context": {
    "messages": [],
    "modelContext": {
      "checkpoint": null,
      "firstRetainedMessageId": null,
      "modelState": null,
      "usage": null
    }
  }
}
```

`messages` 始终是可见的完整聊天历史；自动或手动压缩只更新 `modelContext`。服务端返回
`ConversationSnapshot { conversation, context }`，其中 `modelContext` 必须显式出现且四个字段
完整。状态损坏或 revision 冲突必须明确失败，不能改用另一份历史数据。

Controller 初始化会读取服务端窗口配置，达到 80% 时在下一次模型调用前自动压缩。View 可
读取 `state.contextWindow` 并调用 `controller.compactContext()` 手动触发。正常模型响应必须
携带 token usage；压缩后的保守估算标记为 `estimated`，下一次正常响应再恢复 Provider 计量。
完整配置、UI 和 Provider 要求见
[上下文压缩指南](../../../docs/guides/context-compaction.md)。

## 默认 Widget 与 Demo

不需要自定义 UI 时使用 `@patchbridge-agent/widget`。Widget 支持通过 JavaScript
`runtimeOptions` 注入同一套有限扩展点：

```js
const widget = document.querySelector('patchbridge-agent');
widget.runtimeOptions = {
  limits: { maxModelCalls: 8, maxDurationMs: 120_000 },
  hooks: [{ onEvent: event => console.debug(event.type) }],
};
```

Demo 首页会显示真实 Hook / Model Interceptor / Tool Interceptor 触发计数；危险的
`local.device_restart` 演示 HITL，“停止”按钮演示取消，聊天消息演示 ContentBlock，
会话重新加载演示完整 `ConversationContext` 与压缩检查点持久化。Provider 返回 `max-tokens` 时，
Widget 保留已封闭内容并显示可通过 `::part(max-tokens-notice)` 定制的截断提示。
