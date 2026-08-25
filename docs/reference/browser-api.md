# Browser API 参考

## Browser Headless 接入

### 分发边界

当前未发布 npm。以下 TypeScript 示例适用于仓库 workspace、本地链接或宿主自行构建的源码集成，不表示公共 npm 安装命令可用。

### 最小 Controller

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

### Controller 公共意图

当前接口包括：

- <code>getState()/subscribe()</code>；
- <code>initialize()/refreshConversations()/loadConversation()</code>；
- <code>startNewConversation()/deleteConversation()</code>；
- <code>sendMessage(text, images)</code>；
- <code>approveTool()/rejectTool()/abort()/dispose()</code>；
- <code>getToolRegistry()/getToolInspectionSource()/getCallTraceSource()</code>；
- <code>registerTool()</code>。

Navigation 和 Run 使用独立取消与 generation；旧请求、旧 Execution 和迟到保存结果不能覆盖新状态。

### createAgentController 选项

| 选项 | 作用 |
| --- | --- |
| <code>endpoint</code> | 必填，Agent API 根路径 |
| <code>engine</code> | 完整替换默认 AgentEngine；不能同时提供 model/runtime |
| <code>transport</code> | Model/Tool/Conversation 默认 Client 共用的 HttpTransport |
| <code>model</code> | 替换默认 HttpModel；不能与 engine 同时提供 |
| <code>runtime.limits.maxModelCalls</code> | 默认 16，整轮模型调用次数 |
| <code>runtime.limits.maxToolCalls</code> | 默认 32，整轮接受的 Tool Call 总数 |
| <code>runtime.limits.maxDurationMs</code> | 默认 300000，包含模型、Tool 与确认等待的整轮 Deadline |
| <code>runtime.limits.maxModelOutputCharacters</code> | 默认 100000，单次模型聚合的正文、思考和 Tool 参数字符总数 |
| <code>runtime.limits.maxToolResultCharacters</code> | 默认 100000，单个 Tool 结果回填上限 |
| <code>runtime.now</code> | 单调毫秒时钟，同时用于 Deadline 边界和性能指标；生产自定义实现不得回退或返回非有限值 |
| <code>runtime.hooks</code> | 只读生命周期观察者 |
| <code>runtime.onHookError</code> | 终态 Hook 异常的独立 diagnostics 回调；省略时异常路径写入 <code>console.error</code>，不改判已经选定的终态 |
| <code>runtime.modelInterceptors</code> | 模型调用拦截器 |
| <code>runtime.toolInterceptors</code> | Tool 调用拦截器 |
| <code>toolClient</code> | 替换后端 Tool Client |
| <code>toolRegistry</code> | 完整替换 Registry；不能与 toolClient 同时提供 |
| <code>conversationClient</code> | 替换会话 Client |
| <code>storageKey</code> | 上次会话 localStorage key；默认 <code>patchbridge-agent:last-conversation</code> |
| <code>callTrace</code> | 调用轨迹采集配置；缺省不采集（不创建采集 Hook、不访问 localStorage）。<code>{ mode: 'memory' }</code> 仅当前页内存；<code>{ mode: 'persistent' }</code> 终态写入 localStorage，可用 <code>storageKey</code> 指定键前缀（默认 <code>patchbridge-agent:call-trace</code>） |

默认 storageKey 不包含用户身份。共享浏览器环境应由宿主提供按应用和用户隔离的 key；这只是 UX/隐私隔离，服务端 owner 校验仍是安全边界。

<code>createAgentController()</code> 中的 <code>runtime.limits</code> 是部分覆盖，只需写需要调整的字段：

~~~ts
const controller = createAgentController({
  endpoint: '/ai',
  runtime: {
    limits: {maxModelCalls: 8, maxDurationMs: 120_000},
  },
});
~~~

直接 <code>new DefaultAgentRuntime(model, options)</code> 不是部分覆盖入口；
<code>options.limits</code> 必须同时提供上表五项安全正整数，且
<code>maxDurationMs</code> 不得超过 <code>2147483647</code>。这一差异用来防止高级宿主直接组装 Runtime 时无意遗漏关键资源边界。

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

---

## 默认 Widget

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

### 四个 Starter Bundle

<code>{basePath}</code> 默认是 <code>/ai</code>：

| HTTP 路径 | classpath 资源 |
| --- | --- |
| <code>{basePath}/assets/patchbridge-agent.js</code> | <code>META-INF/patchbridge-agent/patchbridge-agent.js</code> |
| <code>{basePath}/assets/patchbridge-agent-webmcp-adapter.js</code> | <code>META-INF/patchbridge-agent/patchbridge-agent-webmcp-adapter.js</code> |
| <code>{basePath}/assets/patchbridge-agent-tool-inspector.js</code> | <code>META-INF/patchbridge-agent/patchbridge-agent-tool-inspector.js</code> |
| <code>{basePath}/assets/patchbridge-agent-call-trace.js</code> | <code>META-INF/patchbridge-agent/patchbridge-agent-call-trace.js</code> |

### 样式扩展

Widget 提供三层稳定入口：

1. <code>--patchbridge-agent-*</code> CSS Variables 调整颜色、字体、间距、尺寸和圆角；
2. <code>::part()</code> 覆盖 panel、header、message、composer、input、send-button、reasoning、max-tokens-notice 等语义节点；
3. <code>theme="none"</code> 移除参考视觉主题。

不要依赖 Shadow DOM 内部 class。需要改变 DOM 或交互结构时，使用 Headless Controller 自建 View。