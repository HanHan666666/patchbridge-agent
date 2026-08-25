# Demo 使用与验收

## Demo 使用与验收入口

Demo 是一个模拟企业设备系统，用真实模型和真实 MCP endpoint；仓库不内置 Mock AI API 或 Mock MCP Server。

### 启动

~~~bash
export PATCHBRIDGE_AGENT_MODEL_BASE_URL='https://model-gateway.example/v1'
export PATCHBRIDGE_AGENT_MODEL='model-name-placeholder'
export PATCHBRIDGE_AGENT_MODEL_API_KEY='<model-api-key-placeholder>'
export PATCHBRIDGE_AGENT_MCP_ENCRYPTION_KEY='<base64-encoded-32-byte-key-placeholder>'

cd java
mvn install -DskipTests
mvn -pl patchbridge-agent-demo spring-boot:run
~~~

不要使用占位符本身启动。模型 API key 是否必需取决于目标网关；模型 base URL 和 JDBC MCP 加密密钥在 Demo 当前装配下是硬前置。

### 入口

| 入口 | 地址 | 可观察内容 |
| --- | --- | --- |
| Demo 首页 | <code>http://localhost:8080/</code> | 设备业务页、聊天、主题、Runtime 指标 |
| Tools 调试 | 首页页签 | 当前 Registry/Execution 快照 |
| 调用轨迹 | 首页页签 | Browser Call Trace |
| Admin | <code>http://localhost:8080/ai-admin/</code> | Audit/Trace 和 Global MCP |
| Java 单次模型调用 | <code>POST /demo-api/model-invocations/text</code>、<code>/image</code> | 同 JVM Gateway 的文本同步与图片异步示例 |
| 登录 | <code>http://localhost:8080/login</code> | Demo Spring Security 表单 |

Demo 源码定义 admin、operator、user、auditor 四个内存用户及不同角色/权限。它们的固定测试凭据不是 Starter 默认账号，也不是生产建议；本文不复制密码。账号能力应从 Demo 的 <code>SecurityConfig</code> 和两个 Demo Policy 读取。

### 功能验收路径

1. 以不同 Demo 用户登录，比较 Tool discovery 和 restart 权限。
2. 在聊天中触发只读查询，观察结构化流和会话保存。
3. 触发破坏性 Tool，验证 HITL 批准/拒绝；再确认服务端仍执行 canInvoke。
4. 刷新页面，确认只恢复最后完整 Conversation，而不恢复未完成的 Execution。
5. 在 Tools 调试页签比较空闲 Registry 和运行中冻结快照。
6. 在支持 <code>document.modelContext</code> 的浏览器验证 WebMCP；不支持时应显示不可用。
7. 在调用轨迹页签查看 message-stop 后的模型完成、Tool、确认、耗时和 token。
8. 进入 Admin 检查 Audit/Trace；在 JDBC 模式创建占位测试 Server，验证 test/refresh/enable/revision。
9. 用 Demo 业务端点发起一次真实文本和图片模型调用，确认结果没有进入 Conversation 或 Audit。
10. 让真实 Browser Agent 产生一次 <code>max-tokens</code>，确认 Widget 显示可定制的截断提示，已封闭消息和 ModelState 仍保存；其他底层守卫通过 Runtime/Provider 契约测试验收，不另建 Demo 页。

### Demo 安全边界

Demo 对 <code>/ai/**</code> 和 logout 关闭 CSRF，是同源 SameSite Cookie 演示决策，不是 Starter 要求。生产 Cookie Session 必须按宿主威胁模型配置 CSRF token、Origin/Referer、SameSite 和 CORS；Bearer 模式应复用宿主既有安全链。

---

## Demo 验收清单

- 首页“聊天”可以让真实模型调用后端 `@AiTool`。
- 首页 `frontend.device_search` 可以调用 `/demo-api/devices`，Network 中没有 `/ai/tools/call`。
- 支持 WebMCP 的浏览器中，`page.open_tools_tab` 出现在 Tools 列表且调用前要求确认；不支持时显示真实状态。
- `/ai-admin/` 可以创建真实 Global MCP Server，并测试、刷新、启停、修改、删除。
- “Tools 调试”展示上述所有已启用来源，列表随同一 Registry revision 更新。
- “调用轨迹”按执行展示用户输入、模型调用、Tool 调用、确认、耗时与 token；每条已完成模型调用右侧显示首 token 延迟和首 token 后平均输出 tok/s；刷新后从本浏览器 localStorage 恢复，ℹ️ 弹窗解释不写后端的原因。
- 首页顶栏可实时切换 8 种主题（深蓝企业为默认，另有青绿科技、暗色、高对比度、赛博朋克、Win98 复古、奇幻 RPG、NES 像素），页面外壳与 Widget / Inspector / Call Trace 同步换肤；组件侧全部由宿主 CSS Variables 与 `::part` 完成，没有修改 Widget 内部 class，选择记忆在 localStorage。对话流中的工具调用 / 执行结果块也通过 `::part(tool-call-block)` / `::part(tool-result-block)` 参与换肤（如 Win98 的凹陷面板、赛博朋克的双色虚线发光框）。赛博朋克 / NES / RPG 三个主题的页面特效与素材来自本地 vendor 的第三方库（cybercore-css / NES.css，MIT；RPGUI，zlib；Press Start 2P 字体，OFL），各自的样式表仅在对应主题激活时启用。
- 左侧 Runtime 状态显示 Hook 最后事件及 Model / Tool Interceptor 的真实触发次数。
- `local.device_restart` 演示 HITL；流式时“停止”演示取消；刷新后继续对话演示
  `ConversationContext + ModelState` 同 revision 恢复。
- 真实模型返回 `max-tokens` 时，Widget 保留并保存已封闭内容，同时显示“内容可能不完整”提示；
  其他底层执行守卫以 Runtime/Provider 契约测试为验收入口，不增加与业务无关的 Demo 页。