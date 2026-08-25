# 使用纯前端 Tool

## Browser Local Tool

使用 IIFE 时应先创建元素并注册 <code>patchbridge-agent-ready</code> 监听器，最后加载主 Widget Bundle；否则元素升级时可能已经派发 ready。以下示例也通过只读 getter 处理 Controller 已经启动的情况。

~~~js
const widget = document.querySelector('patchbridge-agent');
let registration;

function registerPageTool(registry) {
  registration?.dispose();
  registration = registry.register({
    name: 'frontend.current_device',
    description: '获取当前页面正在查看的设备',
    inputSchema: {type: 'object', properties: {}, additionalProperties: false},
    annotations: {readOnlyHint: true},
    execute: async (_arguments, context) => {
      context.signal?.throwIfAborted();
      return currentDeviceStore.snapshot();
    },
  });
}

widget.addEventListener('patchbridge-agent-ready', event => {
  registerPageTool(event.detail.toolRegistry);
});
if (widget.toolRegistry != null) {
  registerPageTool(widget.toolRegistry);
}
~~~

<code>inputSchema</code> 由 Registry 在注册期编译、在统一执行边界强制校验：模型生成的参数缺必填字段、类型不符、取值超出 enum 或携带未声明参数（<code>additionalProperties: false</code>）时，调用以明确的 Tool 失败结果结束，<code>execute()</code> 不会执行，Tool 内部不需要重复做参数保护。受支持子集：<code>type</code>（含类型联合）、<code>properties</code>、<code>required</code>、<code>additionalProperties</code>（仅布尔）、<code>enum</code>、<code>items</code> 与注释性的 <code>title</code>/<code>description</code>；使用其他关键字会在注册时明确报错。根 Schema 必须是 <code>object</code> 类型。

Schema 失败是已知、模型可修正的 <code>isError=true</code> 结果；页面 <code>execute()</code>
自身抛错、Promise rejection、返回 <code>undefined</code> 或不可 JSON 序列化值时则终止 Execution。
不要依赖 Runtime 把编程/基础设施异常包装成模型可见错误。

实际 Widget 也提供 <code>widget.registerTool()</code> 便捷方法。Tool 名必须为 1-128 位字母、数字、点、下划线或连字符；重名明确失败。未声明 <code>readOnlyHint=true</code> 时默认要求用户确认。

Browser Local Tool 不经过 <code>/ai/tools/call</code>。它执行页面 Store、前端 Service 或普通 HTTP API；凡读取或修改服务端数据，目标 API 仍必须基于可信登录态鉴权。Browser context 中的 traceId、conversationId 和 toolCallId 只用于当前执行关联，不能作为服务端身份或权限事实。

---

## 纯前端声明 Tool

### 适用场景

纯前端 Tool 用于页面已经拥有能力、但不值得再增加一层 Agent 专用后端接口的场景，例如：

- 读取当前选中的订单、设备或表格行；
- 调用页面已有的前端 Service；
- 使用企业统一 `fetch`/Axios Client 调已有业务 API；
- 切换路由、打开面板或把表单草稿交给 Agent；
- 访问只能存在于当前页面生命周期的临时状态。

它的执行链是 `Model → Browser Runtime → JavaScript execute()`，不会调用 `/ai/tools/call`。如果 `execute()` 内部使用已有 HTTP API，请继续复用宿主的 Cookie、Bearer 刷新、CSRF 和请求拦截链；框架不会绕过业务安全边界。

### 通过 Widget 注册

Widget 完成 Controller 装配后会派发 `patchbridge-agent-ready`。endpoint 或 `httpTransport` 改变会重建 Controller，因此页面应在每次 ready 时重新注册，并释放旧 Registration：

```html
<script src="/ai/assets/patchbridge-agent.js"></script>
<patchbridge-agent id="agent" endpoint="/ai"></patchbridge-agent>
<script>
  const agent = document.getElementById('agent');
  let registration;

  agent.addEventListener('patchbridge-agent-ready', () => {
    registration?.dispose();
    registration = agent.registerTool({
      name: 'frontend.order_search',
      title: '订单查询',
      description: '调用当前系统已有的订单查询接口',
      inputSchema: {
        type: 'object',
        properties: { keyword: { type: 'string' } },
        additionalProperties: false
      },
      annotations: { readOnlyHint: true },
      execute: async ({ keyword }, context) => {
        const response = await enterpriseFetch(
          `/api/orders?keyword=${encodeURIComponent(keyword ?? '')}`,
          { signal: context.signal }
        );
        if (!response.ok) {
          throw new Error(`订单查询失败：HTTP ${response.status}`);
        }
        return response.json();
      }
    });
  });
</script>
```

`execute()` 返回字符串时直接回填模型；返回对象、数组、数字或布尔值时由 Registry JSON 序列化。
Schema 校验失败是框架明确生成的 `isError: true` Tool 结果，模型可以据此修正参数；
`execute()` 返回 `undefined`/循环对象、主动 throw 或 Promise rejection 则表示 Tool Adapter/编程失败，
会终止当前 Execution，不伪装成可继续的业务结果。

`inputSchema` 不是文档性配置：Registry 注册期编译它（受支持子集为 type/properties/required/additionalProperties/enum/items 与 title、description，其余关键字注册即失败，根类型必须是 object），执行边界按它强制校验模型参数——缺必填、类型不符、enum 外取值或携带未声明参数时直接产生 Tool 失败结果，`execute()` 不被调用。这正是"参数保护收敛在统一边界"的设计：Tool 作者只声明 Schema，不在每个 execute 里重复写参数检查。

### 通过 Headless Controller 注册

不使用默认 Widget 时，直接持有 Registry：

```ts
import { createAgentController } from '@patchbridge-agent/agent';

const controller = createAgentController({ endpoint: '/ai' });
const registration = controller.registerTool({
  name: 'frontend.current_selection',
  description: '读取当前页面选中的业务对象',
  inputSchema: { type: 'object', additionalProperties: false },
  annotations: { readOnlyHint: true },
  execute: () => selectionStore.current,
});

// 页面模块卸载时释放。
registration.dispose();
```

### 安全默认值与边界

- 名称必须是 1–128 位字母、数字、点、下划线或连字符；建议使用 `frontend.*` 命名空间。
- 未声明 `readOnlyHint: true` 的页面 Tool 默认 `requireConfirmation: true`。
- `annotations: { readOnlyHint: true }` 已足够声明常见只读能力，不需要手工补齐全部布尔字段。
- `context.traceId`、`conversationId` 与 `toolCallId` 是 Runtime 生成的浏览器内关联字段；它们用于追踪，不得作为服务端身份或授权事实。业务参数只来自第一个参数。
- `context.signal` 在用户停止当前 run 时中止，页面 HTTP 请求应向下传递它。
- 前端 Tool 不能代替服务端授权。凡是会读取或修改服务端数据的 API，仍必须由服务端根据可信登录态鉴权。
- Registry 禁止同名覆盖；页面要替换实现时先 `dispose()` 旧 Registration。

Demo 的 `frontend.device_search` 会直接请求 `/demo-api/devices`，可在浏览器 Network 面板确认它不经过 `/ai/tools/call`。