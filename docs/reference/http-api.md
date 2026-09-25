# HTTP 与 SSE 契约参考

## HTTP、SSE 与错误契约

下表使用默认 <code>/ai</code>。修改 base-path 后，除 <code>/ai-admin</code> 外都替换前缀。

### 端点总表

| 方法和路径 | 成功状态 | 说明 |
| --- | ---: | --- |
| <code>GET /ai/tools</code> | 200 | 当前用户可发现 Tool，含定义/路由版本引用 |
| <code>POST /ai/tools/call</code> | 200 | 每次重新授权；业务 isError 仍可为 200；版本引用必须与当前定义一致 |
| <code>POST /ai/model/stream</code> | 200 SSE | 结构化模型流 |
| <code>GET /ai/model/targets</code> | 200 | 当前身份可用的脱敏目标目录、各目标窗口和默认引用 |
| <code>POST /ai/model/handoff</code> | 200 | 草稿目标切换预检，返回完整候选 Context，不保存 |
| <code>POST /ai/conversations/{id}/model-target</code> | 200 | 根据服务端历史与 revision 原子切换已保存会话 |
| <code>POST /ai/model/compact</code> | 200 | 用当前模型原子生成上下文摘要并投影 Provider 状态 |
| <code>GET /ai/conversations</code> | 200 | 当前 owner 会话列表 |
| <code>POST /ai/conversations</code> | 200 | 必须携带首轮完整 Context；目标与消息原子创建 |
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
  "version": null,
  "arguments": {"serial": "device-placeholder"},
  "requestId": "request-placeholder",
  "traceId": "trace-placeholder",
  "toolCallId": "tool-call-placeholder",
  "conversationId": "conversation-placeholder"
}
~~~

name 和 arguments 必填；即使无参数也必须显式发送 <code>{}</code>。其他字段只用于关联，不是可信身份字段。未知顶层字段拒绝。

<code>version</code> 是发现（<code>GET /ai/tools</code>）时取得并在调用时原样回传的定义/路由版本引用：
静态 Tool（本地 @AiTool、纯前端）恒为 <code>null</code>，MCP Tool 由服务端按路由配置与定义内容
计算确定性摘要。服务端在授权检查之前校验该引用，与当前定义不一致时返回
<code>409 TOOL_VERSION_MISMATCH</code>，保证“模型看到的定义”与“本次实际路由的目标”来自同一代
服务端状态；多实例之间无需共享内存快照。版本校验不能替代授权：权限撤销与工具停用仍按
当前状态逐次判定。

### Conversation 请求

创建请求必须包含完整 `context`，可选 title（最大 256 字符）；无 body 返回 400。保存请求必须包含：

~~~json
{
  "title": "可选标题",
  "revision": 0,
  "context": {
    "messages": [],
    "modelTarget": {"targetId": "deepseek-anthropic", "routingRevision": 1},
    "modelContext": {
      "checkpoint": null,
      "firstRetainedMessageId": null,
      "modelState": null,
      "usage": null
    }
  }
}
~~~

revision 必须为非负整数。冲突返回 <code>409 CONVERSATION_CONFLICT</code>，错误对象包含 currentRevision；客户端应重新加载并由用户决定，不能静默覆盖。

`messages` 始终保存完整可见聊天历史。压缩只更新 `modelContext`：

- `checkpoint` 为 `null`，或精确包含 `id/summary/trigger/compactedAt/tokensBefore/estimatedTokensAfter/compactionCount`；
- `firstRetainedMessageId` 与 `checkpoint` 必须同时为 `null` 或同时非空，并引用完整 `messages` 中压缩后第一条保留的非 system 消息；
- `modelState` 是与当前模型工作上下文严格对应的 Provider 私有状态；
- `usage` 为 `null`，或精确包含 `totalTokens/source/measuredThroughMessageId/toolDefinitionTokens`，其中 source 只能是 `provider` 或 `estimated`。 `toolDefinitionTokens` 必须为非负安全整数；estimated 基线必须为 0，用于保存该基线已经覆盖的工具目录估算量。
- 每个 `tool-result` 必须包含 `execution`：`completed/not-executed/unknown/result-omitted`，后三种必须同时使用 `status: error`。保存和加载原样保留执行事实，不推断提示文案；缺失字段按当前严格契约拒绝。


### 模型目录与 handoff

`GET /ai/model/targets` 返回 `targets` 数组和 `defaultTarget` 引用或 null。每个目标仅包含 `ref`、`displayName`、`protocol`、`imageInput`、`toolCalling`、`configuration`；`configuration` 精确包含 `contextWindowTokens`、`automaticThresholdTokens`、`keepRecentTokens`、`reservedOutputTokens`。目录按当前认证身份过滤，不公开 endpoint、上游模型或凭据。旧 `/model/config` 已删除。

草稿切换 `POST /ai/model/handoff` 请求为 `{ "context": <完整 ConversationContext>, "target": <ModelTargetRef>, "tools": [] }`，返回 `{ "context": <完整候选 Context> }`。保存会话切换 `POST /ai/conversations/{id}/model-target` 请求为 `{ "revision": 3, "target": <ModelTargetRef>, "tools": [] }`，返回 `{ "conversation": <新元数据>, "context": <完整候选 Context> }`。保存路径从服务端读取原历史并以 revision 原子提交；请求不接受客户端 context。两个路径均保留完整消息与文本 checkpoint，清除旧私有状态，按新目标重估，失败不改变原会话。

`ModelTargetRef` 精确为 `{ "targetId": "deepseek-anthropic", "routingRevision": 1 }`。模型流、摘要及 `ConversationContext.modelTarget` 必须携带同一引用；普通保存不得改变当前目标。目标禁用、修订过期或权限不足时返回明确错误，不转用默认目标。

### Context compact

`POST /ai/model/compact` 请求精确包含可选关联字段和必填 `request`：

~~~json
{
  "traceId": "trace-placeholder",
  "conversationId": "conversation-placeholder",
  "request": {
    "modelTarget": {"targetId": "deepseek-anthropic", "routingRevision": 1},
    "trigger": "manual",
    "messagesToSummarize": [
      {
        "id": "old-user-message",
        "role": "user",
        "blocks": [{"type": "text", "text": "较旧消息"}]
      }
    ],
    "retainedMessages": [
      {
        "id": "recent-user-message",
        "role": "user",
        "blocks": [{"type": "text", "text": "近期消息"}]
      }
    ],
    "previousSummary": null,
    "modelState": null,
    "responseMessageId": "compaction-response-placeholder",
    "splitTurn": false
  }
}
~~~

`trigger` 只能是 `automatic` 或 `manual`。两个消息数组都必须非空；system 消息由 Browser
固定保留，因此可以同时出现在两组输入中。`previousSummary` 和 `modelState` 即使为空也必须
显式发送。`retainedMessages` 会逐字保留在正常模型上下文中，同时作为摘要模型的只读状态
对账依据；它已经完成、取消或替代的事项不得继续出现在摘要的剩余工作中。`splitTurn` 表示
近期边界位于 assistant 回合中，摘要必须覆盖该回合被淘汰的前缀。

成功响应精确为：

~~~json
{
  "summary": "当前模型生成的非空上下文摘要",
  "usage": {
    "inputTokens": 42000,
    "outputTokens": 900,
    "totalTokens": 42900
  },
  "modelState": null
}
~~~

服务端按请求中的 `modelTarget` 精确解析；保存会话还核对其当前目标，不接受 Browser 传任意模型名，不向摘要调用提供 Tool。摘要请求本身
遵守与普通请求相同的窗口预算：摘要输入（含淘汰前缀、保留尾部与固定指令）加输出预留
超过窗口时在调用模型前明确失败，返回 <code>413 CONTEXT_WINDOW_EXCEEDED</code>（重试
同样的请求必然再次失败，必须调整输入）。摘要空白、意外 `tool-use`、`max-tokens`、缺少
usage、Provider 状态投影失败或上游失败返回明确错误；不会返回部分检查点、重试、
换模型或清空状态。Servlet 取消、断开与超时会取消同一次真实模型调用。
成功和失败均以 `COMPACTION` 类型写入现有审计入口。

### Model stream 请求

request 必须显式包含 modelTarget、responseMessageId、非空 messages、tools 和 modelState。tools 可以是空数组，modelState 可以是显式 null，但两个字段都不能缺失。maxTokens 如提供必须大于 0。

Anthropic Messages 目标把项目内 Tool 全名映射成最长 64 字符的协议别名；流式工具调用在服务端恢复为原名，Browser 和 Tool 执行层只处理项目内的全名。别名仅在本次请求的工具定义快照中有效。

### SSE 事件

响应为 <code>text/event-stream; charset=UTF-8</code>，设置 <code>Cache-Control: no-cache</code> 和 <code>X-Accel-Buffering: no</code>。每帧只使用 SSE data JSON，不设置命名 event。

| type | 主要字段 | 语义 |
| --- | --- | --- |
| <code>block-start</code> | index, block | 开始 text/reasoning/tool-call 等 Block |
| <code>block-delta</code> | index, delta | text/reasoning 文本或 Tool argumentsDelta |
| <code>block-stop</code> | index | 当前 Block 完整结束 |
| <code>message-stop</code> | stopReason, usage, modelState | 唯一完整消息结束边界；usage 必须存在 |
| <code>error</code> | error.code/message/retryable | 流建立后的模型失败 |

<code>message-stop</code> 到达前，所有已开始 Block 必须先 stop；到达后不允许再有模型事件。usage
必须包含非负整数 `inputTokens/outputTokens/totalTokens`；缺失 usage 时 Browser 无法管理明确配置的
模型窗口，因此当前正常响应失败，不用字符估算代替。流结束但没有 message-stop、存在未关闭
Block、非法 Tool 参数或后续多余事件都属于 <code>MODEL_PROTOCOL_ERROR</code>。只有完整流成功组装后，
Runtime 才发布模型调用完成事实；取消、网络失败、协议失败或未完成的消息一律不报告为完成。

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
