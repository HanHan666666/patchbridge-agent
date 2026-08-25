# 接入 WebMCP Adapter

## 使用与接入

WebMCP 是显式 opt-in 的独立 Adapter，只在浏览器真实提供 <code>document.modelContext</code> 时工作，不修改 Document 原型，也不提供 polyfill。

~~~html
<script src="/ai/assets/patchbridge-agent-webmcp-adapter.js"></script>
<script>
  let webMcp;
  document.querySelector('patchbridge-agent')
    .addEventListener('patchbridge-agent-ready', async event => {
      webMcp?.stop();
      webMcp = await window.PatchBridgeAgentWebMcp.connectDocumentWebMcp(
        event.detail.toolRegistry,
        document,
        {onRefreshError: cause => console.error(cause)}
      );
    });
  window.addEventListener('pagehide', () => webMcp?.stop(), {once: true});
</script>
~~~

不支持时抛出 <code>WebMcpUnavailableError</code>。toolchange 自动刷新 Provider；刷新失败保留最近成功目录并调用 onRefreshError；stop 移除监听并注销全部 WebMCP Tool。未明确只读的 WebMCP Tool 默认要求确认。

WebMCP 仍是演进中的 Community Group Draft；不要把可用性当作所有浏览器的稳定平台能力。

---

## 设计说明

### 为什么单独分包

WebMCP 目前仍是 W3C Web Machine Learning Community Group Draft。框架不应让实验性 Web API 进入核心包，因此实现位于可选的 `@patchbridge-agent/webmcp-adapter`：

- 不修改 `Document` 原型；
- 不提供 `document.modelContext` polyfill；
- 不支持时明确抛出 `WebMcpUnavailableError`；
- 只使用当前草案的 `getTools()`、`executeTool()` 和 `toolchange`；
- Adapter 停止时移除监听并从 Registry 注销全部 WebMCP Tool。

### 模块化工程用法

```ts
import { connectDocumentWebMcp } from '@patchbridge-agent/webmcp-adapter';

let adapter;
try {
  adapter = await connectDocumentWebMcp(
    controller.getToolRegistry(),
    document,
    {
      onRefreshError: cause => showWebMcpError(cause),
      // 默认只发现同源文档；确需跨源 iframe 时再显式配置：
      // fromOrigins: ['https://trusted.example.com']
    },
  );
} catch (cause) {
  showWebMcpUnavailable(cause);
}

// SPA 页面卸载时：
adapter?.stop();
```

`onRefreshError` 是必填项，因为 `toolchange` 是浏览器事件，异步刷新错误无法通过事件返回 Promise；框架不静默吞错。

WebMCP 当前只提供 `readOnlyHint` 和 `untrustedContentHint`。Adapter 对未明确只读的 Tool 设置 `requireConfirmation: true`，避免页面写操作被 Agent 直接执行；它不会凭空猜测 `destructive` 或 `idempotent`。

### 静态 HTML 用法

Starter 还提供独立的可选 IIFE：

```html
<script src="/ai/assets/patchbridge-agent-webmcp-adapter.js"></script>
<script>
  void (async () => {
    const adapter = await window.PatchBridgeAgentWebMcp.connectDocumentWebMcp(
      registry,
      document,
      { onRefreshError: console.error }
    );
    window.addEventListener('pagehide', () => adapter.stop(), { once: true });
  })();
</script>
```

Demo 只在浏览器真实存在 `document.modelContext` 时注册 `page.open_tools_tab` 并连接 Adapter；不支持时页面直接显示不支持原因。