# HTTP 与 SSE 契约参考

## HTTP、SSE 与错误契约

下表使用默认 <code>/ai</code>。修改 base-path 后，除 <code>/ai-admin</code> 外都替换前缀。

### 端点总表

| 方法和路径 | 成功状态 | 说明 |
| --- | ---: | --- |
| <code>GET /ai/tools</code> | 200 | 当前用户可发现 Tool |
| <code>POST /ai/tools/call</code> | 200 | 每次重新授权；业务 isError 仍可为 200 |
| <code>POST /ai/model/stream</code> | 200 SSE | 结构化模型流 |
| <code>GET /ai/conversations</code> | 200 | 当前 owner 会话列表 |
| <code>POST /ai/conversations</code> | 200 | 无 body 合法；不是 201 |
| <code>GET /ai/conversations/{id}</code> | 200 | 会话元数据与完整 Context |
| <code>PUT /ai/conversations/{id}</code> | 200 | revision + 完整 Context 原子替换 |
| <code>DELETE /ai/conversations/{id}</code> | 204 | 幂等删除；不存在也返回 204 |
| <code>GET /ai/admin/stats</code> | 200 | Admin + AuditQueryRepository |
| <code>GET /ai/admin/traces</code> | 200 | page 默认 0，pageSize 默认 20、范围 1..100 |
| <code>GET /ai/admin/traces/{traceId}</code> | 200 | Trace 详情 |
| <code>GET /ai/admin/mcp/servers</code> | 200 | Admin 和 MCP 同时开启 |
| <code>GET /ai/admin/mcp/servers/{name}/tools</code> | 200 | 当前 Server Tool 快照 |
| <code>POST /ai/admin/mcp/servers</code> | 201 | JDBC 模式创建 |
| <code>PUT /ai/admin/mcp/servers/{name}</code> | 200 | revision 乐观锁更新 |
| <code>POST /ai/admin/mcp/servers/{name}/enabled</code> | 200 | enabled + revision |
| <code>DELETE /ai/admin/mcp/servers/{name}?revision=n</code> | 204 | revision 必填 |
| <code>POST /ai/admin/mcp/servers/{name}/refresh</code> | 200 | 刷新真实 Tool 列表 |
| <code>POST /ai/admin/mcp/servers/{name}/test</code> | 200 | 连通失败也返回 200、body ok=false |
| <code>GET /ai-admin</code> 或尾斜杠 | 302 | 固定路径，跳转 index |
| <code>GET /ai-admin/config</code> | 200 | 返回实际 basePath |

Admin Trace 日期格式为 <code>yyyy-MM-dd</code> 或 <code>yyyy-MM-dd HH:mm:ss</code>，解析严格且 from 不得晚于 to。

### Tool call 请求

~~~json
{
  "name": "local.device_get",
  "arguments": {"serial": "device-placeholder"},
  "requestId": "request-placeholder",
  "traceId": "trace-placeholder",
  "toolCallId": "tool-call-placeholder",
  "conversationId": "conversation-placeholder"
}
~~~

name 和 arguments 必填；即使无参数也必须显式发送 <code>{}</code>。其他字段只用于关联，不是可信身份字段。未知顶层字段拒绝。

### Conversation 请求

创建请求只允许可选 title，最大 256 字符。保存请求必须包含：

~~~json
{
  "title": "可选标题",
  "revision": 0,
  "context": {
    "messages": [],
    "modelState": null
  }
}
~~~

revision 必须为非负整数。冲突返回 <code>409 CONVERSATION_CONFLICT</code>，错误对象包含 currentRevision；客户端应重新加载并由用户决定，不能静默覆盖。

### Model stream 请求

request 必须显式包含 responseMessageId、非空 messages、tools 和 modelState。tools 可以是空数组，modelState 可以是显式 null，但两个字段都不能缺失。maxTokens 如提供必须大于 0。

### SSE 事件

响应为 <code>text/event-stream; charset=UTF-8</code>，设置 <code>Cache-Control: no-cache</code> 和 <code>X-Accel-Buffering: no</code>。每帧只使用 SSE data JSON，不设置命名 event。

| type | 主要字段 | 语义 |
| --- | --- | --- |
| <code>block-start</code> | index, block | 开始 text/reasoning/tool-call 等 Block |
| <code>block-delta</code> | index, delta | text/reasoning 文本或 Tool argumentsDelta |
| <code>block-stop</code> | index | 当前 Block 完整结束 |
| <code>message-stop</code> | stopReason, usage, modelState | 唯一完整消息结束边界 |
| <code>error</code> | error.code/message/retryable | 流建立后的模型失败 |

<code>message-stop</code> 到达前，所有已开始 Block 必须先 stop；到达后不允许再有模型事件。流结束但没有 message-stop、存在未关闭 Block、非法 Tool 参数或后续多余事件都属于 <code>MODEL_PROTOCOL_ERROR</code>。只有完整流成功组装后，Runtime 才发布模型调用完成事实；取消、网络失败、协议失败或未完成的消息一律不报告为完成。

stopReason 为 end-turn、tool-use、max-tokens、stop-sequence 或 other，并必须与稳定 Tool Call 数严格一致：

| stopReason | Tool Call 数 | Browser Runtime 处理 |
| --- | ---: | --- |
| <code>tool-use</code> | 至少 1 | 整批预检后按模型顺序串行执行 |
| <code>tool-use</code> | 0 | <code>MODEL_PROTOCOL_ERROR</code> |
| <code>max-tokens</code> | 0 | 保留并保存已封闭消息和 ModelState，返回 <code>max-tokens</code> Outcome，Widget 提示内容可能不完整 |
| <code>max-tokens</code> | 至少 1 | <code>MODEL_PROTOCOL_ERROR</code>，不执行可能被截断的 Tool Call |
| <code>end-turn</code> / <code>stop-sequence</code> / <code>other</code> | 0 | 返回 <code>completed</code> Outcome |
| <code>end-turn</code> / <code>stop-sequence</code> / <code>other</code> | 至少 1 | <code>MODEL_PROTOCOL_ERROR</code> |

Browser 不解析任何厂商 choices、tool_calls、reasoning_content 或结束标记。一条 Assistant 消息的
所有 Tool Call 在任何 Tool 开始前整批检查 Call ID 唯一性、快照成员、JSON 对象参数与次数预算；
任一预检失败时当前批次零 Tool 执行。