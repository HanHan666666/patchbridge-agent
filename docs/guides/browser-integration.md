# 接入 Browser（Headless Controller / 默认 Widget）

> 本页是操作指南，面向“把它跑起来”的任务；完整 Controller 选项、Widget 属性、Starter Bundle 和契约见 [Browser API 参考](../reference/browser-api.md)。

## 分发边界


当前未发布 npm。以下 TypeScript 示例适用于仓库 workspace、本地链接或宿主自行构建的源码集成，不表示公共 npm 安装命令可用。

## 最小 Controller

~~~ts
import { createAgentController } from '@patchbridge-agent/agent';

const controller = createAgentController({ endpoint: '/ai' });
const unsubscribe = controller.subscribe(state => renderAgent(state));

await controller.initialize();
await controller.sendMessage('查询当前告警设备');

unsubscribe();
controller.dispose();
~~~

subscribe 注册后立即推送当前不可变快照。View 只调用 Controller 意图，不直接调用 Runtime、ModelClient、ToolClient 或 ConversationClient。

内置 HTTP Client 在边界校验响应形状：会话对象的 <code>conversationId/revision/status</code> 与时间戳字段、详情响应的 <code>context.messages</code>/<code>modelState</code> 必须符合契约类型，否则以明确的 invalid state 错误失败，不进入 Controller 状态与渲染。Widget 渲染会话列表时对所有来自响应的字符串（含 <code>conversationId</code>）做 HTML 实体转义，伪造 id 无法逃逸属性插值。


## 自定义 HttpTransport

### HttpTransport

~~~ts
import type { HttpTransport } from '@patchbridge-agent/agent';

const transport: HttpTransport = {
  async request(url, init) {
    const headers = new Headers(init.headers);
    headers.set('X-CSRF-TOKEN', readCsrfToken());
    return fetch(url, {
      ...init,
      headers,
      credentials: 'same-origin',
    });
  },
};

const controller = createAgentController({ endpoint: '/ai', transport });
~~~

默认 <code>FetchHttpTransport</code> 使用同源 fetch，并在未指定时设置 <code>credentials='same-origin'</code>。GET 网络失败自动重试一次；非幂等请求不自动重试。模型流只在尚未交付任何框架事件的网络失败时重连一次；一旦交付事件就不重试。

## 默认 Widget 接入

### 元素、属性与事件

主元素：<code>&lt;patchbridge-agent&gt;</code>。

| 类型 | 名称 | 当前行为 |
| --- | --- | --- |
| HTML 属性 | <code>endpoint</code> | 默认 /ai；运行中变化会重建 Controller |
| HTML 属性 | <code>title</code> | 默认“AI 助手”；运行中变化只按当前状态快照重绘视图，不重建 Controller |
| HTML 属性 | <code>login-url</code> | 登录跳转，默认 /login；运行中变化只重绘视图，不重建 Controller |
| HTML 属性 | <code>theme</code> | 默认主题；none 只保留结构/交互基础样式 |
| JS 属性 | <code>httpTransport</code> | 注入 Transport；变化重建 Controller |
| JS 属性 | <code>runtimeOptions</code> | 配置默认 Runtime；变化重建 Controller |
| 只读 getter | <code>toolRegistry</code> | 当前 Controller 的唯一 Registry，未启动时为 null |
| 方法 | <code>registerTool(tool)</code> | 注册页面 Tool并返回 Registration |

属性变化的影响范围按依赖等级区分：<code>endpoint</code> 改变基础设施依赖，整体重建 Controller；<code>title</code> 与 <code>login-url</code> 只影响视图，用当前 Controller 的只读快照重绘。元素未连接期间的变化不保存快照，重连后的首次订阅按新属性完整绘制。

每次 Controller 装配或重建后派发 <code>patchbridge-agent-ready</code>：

- bubbles=true；
- composed=true；
- detail.toolRegistry；
- detail.toolInspectionSource；
- detail.callTraceSource。

每个 Widget 实例装配一个主 Controller；Inspector 和 Call Trace 只消费该 Controller 的只读 Source，不创建第二份 Registry 或领域状态。

<code>runtimeOptions</code> 是 JavaScript 属性，不是 HTML 字符串属性；其 <code>limits</code> 与 Headless 工厂一样支持部分覆盖：

~~~js
const widget = document.querySelector('patchbridge-agent');
widget.runtimeOptions = {
  limits: {maxModelCalls: 8, maxDurationMs: 120_000},
};
~~~

运行中替换该属性会取消旧 Execution 并完整重建 Controller；宿主必须按新的
<code>patchbridge-agent-ready</code> 事件重新挂载页面 Tool 和只读调试视图。