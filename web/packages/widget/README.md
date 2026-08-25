# `@patchbridge-agent/widget`

`<patchbridge-agent>` 是框架提供的可选参考界面，不是必须采用的组件库主题。业务系统可以直接使用 `@patchbridge-agent/agent` 构建自己的界面，也可以通过 CSS 设计令牌和稳定的 Shadow Parts 调整本 Widget。

## 注册纯前端 Tool

Widget 不保存第二份 Tool 列表。`toolRegistry` getter、`registerTool()` 与 Agent Engine
使用 Controller 持有的同一个 Unified Tool Registry；可选 Inspector 使用同一
Controller 暴露的执行感知只读投影。

Controller 装配完成时，Widget 会派发 `patchbridge-agent-ready` 事件。该事件支持冒泡
并可穿越 Shadow DOM 边界，`detail.toolRegistry` 用于注册页面 Tool，
`detail.toolInspectionSource` 用于让 Inspector 展示本轮 Runtime 实际冻结的 revision。
endpoint、HTTP Transport 或 Runtime 配置变化导致 Controller 重建时，事件会重新派发，
便于宿主重新挂载页面 Tool 和 Inspector。

```js
const widget = document.querySelector('patchbridge-agent');
let pageToolRegistration;

widget.addEventListener('patchbridge-agent-ready', () => {
  // 重建后的 Widget 持有新 Registry，因此每次 ready 都重新注册。
  pageToolRegistration?.dispose();
  pageToolRegistration = widget.registerTool({
    name: 'frontend.current_device',
    description: '获取当前页面正在查看的设备',
    inputSchema: { type: 'object', properties: {} },
    annotations: { readOnlyHint: true },
    execute: async () => deviceStore.currentDevice,
  });
});
```

`registerTool()` 会返回幂等的 Registration，页面卸载时应调用 `dispose()`。在 Widget 尚未启动或已断开时调用 `registerTool()` 会明确抛错；需要同步查询时，可先判断只读 getter `widget.toolRegistry` 是否为 null。

## 配置 Browser Runtime 扩展点

`runtimeOptions` 是 JavaScript 属性，不是 HTML 字符串属性。它只暴露默认 Runtime 的
有限配置；替换完整 Engine 时应使用 Headless `@patchbridge-agent/agent`。

```js
const widget = document.querySelector('patchbridge-agent');

// 可以在 Widget bundle 加载前设置；元素升级后会恢复该属性。
widget.runtimeOptions = {
  limits: { maxModelCalls: 8, maxDurationMs: 120_000 },
  hooks: [{
    onEvent(event, context) {
      auditQueue.push({ event, traceId: context.traceId });
    },
  }],
  modelInterceptors: [{
    async *intercept(request, context, signal, next) {
      metrics.modelCalls += 1;
      for await (const event of next(request, context, signal)) {
        yield event;
      }
    },
  }],
  toolInterceptors: [{
    intercept(invocation, next) {
      return next(invocation);
    },
  }],
};
```

`limits` 是部分覆盖：未写字段使用工厂默认值
`maxModelCalls=16`、`maxToolCalls=32`、`maxDurationMs=300000`、
`maxModelOutputCharacters=100000`、`maxToolResultCharacters=100000`。完整替换
Runtime 时应使用 Headless Agent；直接构造 `DefaultAgentRuntime` 必须一次提供五项完整限额。

运行中替换 `runtimeOptions` 会销毁旧 Controller、取消其 Execution 并完整重建；宿主会
收到新的 `patchbridge-agent-ready`，必须按该事件重新注册页面 Tool。Hook 是同步观察者，
Interceptor 的 `next` 每次只能调用一次，详细契约见
[`@patchbridge-agent/agent` 文档](../agent/README.md)。

## 修改参考主题

常用视觉属性通过 `--patchbridge-agent-*` 变量暴露：

```css
patchbridge-agent {
  --patchbridge-agent-color-primary: #00695c;
  --patchbridge-agent-color-primary-hover: #004d40;
  --patchbridge-agent-color-background: #fcfffe;
  --patchbridge-agent-color-text: #10201d;
  --patchbridge-agent-color-border: #b7d4ce;
  --patchbridge-agent-font-family: Inter, sans-serif;
  --patchbridge-agent-font-size: 15px;
  --patchbridge-agent-radius-panel: 0;
  --patchbridge-agent-radius-message: 4px;
  --patchbridge-agent-spacing-lg: 12px;
  --patchbridge-agent-panel-min-height: 520px;
  --patchbridge-agent-conversation-list-width: 240px;
}
```

设计令牌分为以下几组：

- 尺寸：`panel-width`、`panel-height`、`panel-min-height`、`conversation-list-width`、`message-max-width`、`input-max-height`、`image-max-size`、`send-button-width`。
- 字体：`font-family`、`font-size`、`font-size-small`、`font-size-caption`、`font-size-title`、`font-size-large`、`font-weight-emphasis`。
- 色彩：`color-primary`、`color-primary-hover`、`color-primary-disabled`、`color-on-primary`、`color-background`、`color-surface*`、`color-text*`、`color-border`、`color-control-border`，以及 `color-danger*`、`color-warning*`、`color-reasoning*`。
- 间距：`spacing-2xs`、`spacing-xs`、`spacing-sm`、`spacing-md`、`spacing-lg`、`spacing-xl`、`spacing-2xl`、`spacing-3xl`、`spacing-4xl`、`spacing-5xl`。
- 圆角：`radius-sm`、`radius-md`、`radius-lg`、`radius-message`、`radius-panel`。

上述名称都需要添加 `--patchbridge-agent-` 前缀。例如 `color-primary` 的完整名称是 `--patchbridge-agent-color-primary`。

## 覆盖语义节点

Widget 只承诺下面的 `part` 名称稳定，内部 class 名不属于公共 API：

| Part | 语义 |
| --- | --- |
| `panel` | Widget 根面板 |
| `header` | 标题栏 |
| `title` | 标题文本 |
| `new-conversation-button` | 新会话按钮 |
| `conversation-list` | 会话列表侧栏 |
| `conversation-items` | 可滚动的会话条目区域 |
| `messages` | 可滚动的消息区域 |
| `message` | 任意用户、助手或 Tool 消息 |
| `user-message`、`assistant-message`、`tool-message` | 按消息类型进一步筛选 |
| `content-block` | 任意已完成的 Message ContentBlock |
| `text-block`、`image-block`、`reasoning-block` | 正文、图片与可展示思考块 |
| `tool-call-block`、`tool-result-block` | Tool 调用与对应结果块 |
| `streaming-message` | 当前流式助手消息 |
| `reasoning` | 当前流式思考过程 |
| `status`、`error`、`auth-required` | 状态、错误与登录失效区域 |
| `max-tokens-notice` | 模型已封闭但内容可能不完整的独立终态提示 |
| `composer` | 完整输入区 |
| `input` | 文本输入框 |
| `attachment-button`、`send-button` | 附件与发送按钮 |

```css
patchbridge-agent::part(header) {
  border-bottom: 2px solid currentColor;
}

patchbridge-agent::part(user-message) {
  box-shadow: none;
}

patchbridge-agent::part(send-button) {
  min-width: 88px;
  text-transform: uppercase;
}

patchbridge-agent::part(max-tokens-notice) {
  border-inline-start: 3px solid currentColor;
}
```

## 不使用参考主题

设置 `theme="none"` 后，Widget 不再注入默认颜色、字体、间距和圆角令牌，只保留布局、滚动、交互状态以及键盘焦点等基础行为。宿主可以通过同名设计令牌从头定义主题，也可以直接用 `::part` 设置主要语义节点。

```html
<patchbridge-agent endpoint="/ai" theme="none"></patchbridge-agent>
```

```css
patchbridge-agent[theme="none"] {
  --patchbridge-agent-font-family: inherit;
  --patchbridge-agent-font-size: 1rem;
  --patchbridge-agent-color-background: transparent;
  --patchbridge-agent-color-text: inherit;
  --patchbridge-agent-color-primary: currentColor;
  --patchbridge-agent-color-on-primary: Canvas;
}

patchbridge-agent[theme="none"]::part(panel) {
  border: 0;
}
```

完全改变 DOM 结构或交互方式时，应直接使用 Headless `@patchbridge-agent/agent`，而不是依赖 Widget 内部实现。
