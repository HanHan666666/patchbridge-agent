# 使用 Call Trace

## 使用与展示

采集默认关闭：不配置 <code>call-trace</code> 属性（或工厂 <code>callTrace</code> 选项）时
不创建采集 Hook、不访问 localStorage，轨迹视图保持空白。接入视图前先显式开启采集——
Widget 元素用 <code>call-trace="memory|persistent"</code>，Headless 工厂用
<code>callTrace</code> 选项；生产页面不开启即零采集、零存储访问。

~~~html
<script src="/ai/assets/patchbridge-agent-call-trace.js"></script>
<patchbridge-agent-call-trace id="call-trace"></patchbridge-agent-call-trace>
<script>
  const trace = document.querySelector('#call-trace');
  document.querySelector('patchbridge-agent')
    .addEventListener('patchbridge-agent-ready', event => {
      trace.traceSource = event.detail.callTraceSource;
    });
</script>
~~~

稳定语义：

- 按 Execution 展示用户输入、模型调用、Tool 调用、确认、错误、耗时和 token；
- 模型调用只有在完整 <code>message-stop</code> 后才算完成；取消、错误或不完整流不是完成；
- 轨迹 schema v3 直接保存 Runtime Outcome：自然完成、<code>max-tokens</code> 截断和主动取消不再共用一个布尔值，截断终态必须与最后模型记录的 stopReason 一致；
- 首 token 是首个非空 text、reasoning 或 Tool 参数增量；空增量不计；
- 首 token 延迟和输出阶段耗时使用 <code>performance.now()</code> 语义的单调时钟；非有限、负值、回退或时序不一致的数据不应生成有效性能指标；
- 平均输出速度为 <code>outputTokens * 1000 / outputDurationMs</code>，缺少 usage 或有效输出耗时时不显示；
- localStorage 恢复只接受 schema、嵌套结构、时间、终态和关联引用一致的完整轨迹；非法缓存整体拒绝，不做局部修补；持久化结构不是跨版本公共契约。

存储边界：

- 只在当前 origin、当前浏览器 localStorage；
- 每个会话最多最近 30 个已完成 Execution；
- 正文、思考、参数和结果单字段最多 4000 字符；
- 不写入服务端 Conversation，不跨设备同步；
- 新会话轨迹先在内存，保存出真实 conversationId 后迁移；
- 清空轨迹不影响服务端消息或 ModelState；
- localStorage 不可用或配额溢出时，本页内存轨迹继续，并通过 persistenceError 显示失败。

使用工厂同时配置 Call Trace 与宿主 Hook 时，框架会先让内部只读采集 Hook 建立执行轨迹，
再按配置顺序调用宿主 Hook。这样宿主在 <code>execution-started</code> 阶段抛错也会留下完整的
失败轨迹，Controller 仍返回同一个原始错误；该机制不会改变宿主 Hook 之间的相对顺序。

自定义 Engine 若没有接入 CallTrace Hook，默认不会产生轨迹。

---

## 设计说明与采集契约

### 为什么轨迹不写进会话历史

服务端返回的会话历史已经完整保存用户文本、Assistant 正文/思考、Tool Call 参数与
Tool Result，但它的契约是 `ConversationContext { messages, modelContext }`：完整消息用于展示，
`modelContext` 只表达下一轮模型调用需要恢复的工作上下文。调用开始时间、耗时、token 用量、确认交互和执行失败边界
是可观测性数据，不属于模型上下文；把这些字段塞进消息会污染公共消息语义，也可能被
Provider 误送给模型。

当前服务端审计表另有按 `traceId` 记录的 MODEL / Tool 审计，可供有权限的管理员在
`/ai-admin/` 查看；普通用户读取自己会话轨迹的服务端端点不在本期范围，轨迹由浏览器
采集和保留：

```text
Runtime AgentHook（开始/完成/usage/性能计时/中断） ─┐
Controller 稳定消息（正文/思考/Tool 结果） ─┼→ CallTraceStore
                                             ├→ 内存快照
                                             └→ localStorage（仅显式 persistent 模式，按会话）
                                                       ↓
                                                CallTraceSource
                                                       ↓
                                      <patchbridge-agent-call-trace>
```

因此需要向最终用户明确以下边界：

- 轨迹只存在于当前浏览器、当前站点的 localStorage，不会随账号跨设备同步；
- 每个会话保留最近 30 次已完成执行；正文、思考、参数和结果的单字段超过 4000 字符会截断；
- 新会话首次执行先在内存草稿桶，服务端会话保存成功后才迁移到真实会话键；
- 更换设备、清理站点数据或点击“清空本会话轨迹”后轨迹消失；
- 对话消息与 ModelState 仍由服务端会话历史保存，清除轨迹不影响对话或业务数据；
- localStorage 不可用或配额溢出时继续保留本页内存轨迹，并在轨迹页显式显示保存错误。

轨迹组件头部的“ℹ️ 存储说明”使用按需 dialog 展示这段说明，默认关闭，不占用页面常驻区域。

### 采集与保留契约

`CallTraceStore` 是唯一采集入口：它实现 `AgentHook`，按 `traceId` 配对
`execution/model-call/tool-call/interrupt` 生命周期事件，并在事件到达时记录时间戳。
Runtime 在内部流循环中采样第一个非空正文、思考或 Tool 参数增量，并以唯一
`message-stop` 事件封闭稳定模型响应和输出阶段计时。HttpModel 交付该事件后立即发起
SSE reader 取消但不等待清理完成；迟到的 EOF、取消或清理错误不影响已交付的结果。
`model-call-completed` 一次性携带 Provider 标准化后的 `ModelUsage`、首 token 延迟和
首 token 后的输出阶段耗时。逐 token 增量仍不进入 Hook，避免观察插件拖慢模型流。
Controller 在稳定消息提交后以 `responseMessageId` 与 `callId` 补全正文、思考和 Tool 结果。
Runtime 的消息事件携带本轮累计稳定消息；`CallTraceStore` 先把一次事件里的全部内容写入
候选轨迹，再统一校验 Tool 引用并一次提交。连续多轮 `tool-use` 因此不会把尚待同批补录的
新 Assistant 误判为悬空引用；任一消息非法时，整批候选都不进入实时轨迹。

组件里的 `首 token 40.6 s` 表示模型调用链开始到首个非空内容增量的等待时间；
`平均 102 tok/s` 使用 Provider 报告的 `outputTokens / 输出阶段秒数`，输出阶段从首个非空
增量持续到 `message-stop`。因此平均速度排除首 token 等待，不包含输入 token；Provider
未返回 usage 或输出阶段耗时为 0 时不显示速度。

localStorage 只接受版本化、深度校验后的纯数据。实时采集和恢复都会检查 Execution 内
模型/Tool 身份唯一性和 Tool/确认引用关系；恢复还检查会话归属、终态、重复 `traceId`、
有限时间值、每种记录字段、usage 与嵌套数组。任一字段损坏即整体拒绝该会话轨迹，不做局部修复。
正常完成要求模型声明的 Tool 都有执行记录；取消或失败允许保留尚未启动的声明。性能耗时
只按自身非负有限值校验。轨迹 schema 版本为 v3，终态与 Runtime 共用
`completed | max-tokens | cancelled` Outcome；`max-tokens` 必须与最后一条已封闭模型记录一致。

默认 `DefaultAgentRuntime` 由工厂把同一 `CallTraceStore` 放在宿主 Hook 之前，再把这个
实例交给 Controller。内部 Hook 先建立只读账本，确保宿主在 `execution-started` 阶段失败时
仍能封闭失败轨迹并保留原错误；宿主 Hook 之间的相对顺序不变。完整替换 `engine` 的高级
宿主必须自行提供等价生命周期事件；当前通用 `AgentEngine` 接口没有 Hook 注册方法，因此
自定义 Engine 下的默认轨迹数据源为空。

### 最小接入

Call Trace 与 Tools Inspector 一样是独立可选分包；默认 Widget 不依赖它。
采集与视图也是两个独立决策：Widget 需显式声明 call-trace="memory"（仅当前页内存）
或 call-trace="persistent"（终态写入 localStorage）才有轨迹数据，缺省不采集、
不创建采集 Hook、不访问 localStorage；Headless 装配对应工厂 callTrace 选项。

```html
<script src="/ai/assets/patchbridge-agent-call-trace.js"></script>
<patchbridge-agent-call-trace id="call-trace"></patchbridge-agent-call-trace>
<script>
  document.addEventListener('patchbridge-agent-ready', event => {
    document.getElementById('call-trace').traceSource = event.detail.callTraceSource;
  });
</script>
```

组件展示按 Execution 分组的账本；模型调用记录行右侧显示时刻、总耗时、token 用量、
首 token 延迟与首 token 后平均输出速度，点击后就地展开输入、输出、思考、Tool 参数/结果
或确认结果。清空操作只作用于当前会话本地轨迹。
宿主可用 `--patchbridge-agent-trace-accent-color`、`--patchbridge-agent-trace-background`、
`--patchbridge-agent-trace-card-background`、`--patchbridge-agent-trace-border-color`、
`--patchbridge-agent-trace-radius` 等变量定制参考主题，或覆盖 `panel`、`header`、`list`、
`trace`、`record`、`detail`、`info-dialog`、`empty`、`warning` 等 Shadow Parts。
