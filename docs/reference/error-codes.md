# 错误码参考

## 错误码

统一 HTTP 错误体：

~~~json
{
  "error": {
    "code": "INVALID_ARGUMENT",
    "message": "请求不符合当前契约"
  }
}
~~~

| HTTP | code | 当前场景 |
| ---: | --- | --- |
| 400 | <code>INVALID_ARGUMENT</code> | 严格 DTO、非法 JSON、缺参或类型错误 |
| 401 | <code>AUTH_REQUIRED</code> | 未登录或登录态失效 |
| 403 | <code>TOOL_FORBIDDEN</code> | Tool 二次授权拒绝 |
| 403 | <code>MODEL_TARGET_FORBIDDEN</code> | 当前身份不能使用目标 |
| 403 | <code>ADMIN_FORBIDDEN</code> | Admin 能力拒绝或未知管理路由 |
| 404 | <code>TOOL_FAILED</code> | Tool 不存在 |
| 404 | <code>CONVERSATION_NOT_FOUND</code> | 当前 owner 范围找不到会话 |
| 404 | <code>MODEL_TARGET_NOT_FOUND</code> | 目标 ID 不在目录 |
| 409 | <code>CONVERSATION_CONFLICT</code> | 会话 revision 冲突 |
| 409 | <code>MODEL_TARGET_DISABLED</code> / <code>MODEL_TARGET_REVISION_MISMATCH</code> | 目标已停用或配置修订与请求不符 |
| 409 | <code>MODEL_TARGET_MISMATCH</code> / <code>MODEL_TARGET_INCOMPATIBLE</code> | 普通保存试图换目标，或目标不能原样编码工作上下文 |
| 409 | <code>TOOL_VERSION_MISMATCH</code> | Tool 定义/路由版本引用已过期，需重新发现工具 |
| 409 | <code>MCP_CONFIG_CONFLICT</code> | MCP 名称或 revision 冲突 |
| 409 | <code>MCP_CONFIG_READ_ONLY</code> | properties 模式写操作 |
| 500 | <code>TOOL_FAILED</code> | Tool 执行异常 |
| 413 | <code>CONTEXT_WINDOW_EXCEEDED</code> | 上下文摘要请求超过“窗口 − 输出预留”预算 |
| 413 | <code>MODEL_TARGET_CONTEXT_TOO_LARGE</code> | handoff 后工作上下文超过目标窗口，原会话不变 |
| 502 | <code>MCP_FAILED</code> | MCP 协议或连接异常 |
| 502 | <code>MODEL_FAILED</code> | 未进入 SSE 流的模型网关异常，或上下文摘要 / Provider 状态投影失败 |
| 200 SSE | <code>MODEL_FAILED</code> | 流内 error 帧 |

当前边界必须如实理解：

- MCP Admin 查找不存在的本地 Server，部分路径当前也落入通用 <code>502 MCP_FAILED</code>，没有独立 404 契约；test 端点则返回 200 和 <code>ok=false</code>。
- 405、415、406 发生在 Handler 选中之前，由路径限定的协议异常解析器处理：<code>base-path</code> 独占空间内返回上述统一信封（405 保留 <code>Allow</code>，415 保留 <code>Accept</code>），空间外完全交回宿主异常链，不影响宿主 Controller。
- 已通过宿主身份认证但 userId 为空或纯空白时，按 <code>401 AUTH_REQUIRED</code> 处理；框架不裁剪或重建 userId。
- Browser 本地还会产生 NETWORK_ERROR、ABORTED、INVALID_STATE 和 MODEL_PROTOCOL_ERROR。
- 上下文压缩缺少正常 Provider usage、没有安全可压缩前缀或服务端配置/响应形状违约时，
  Browser 使用 <code>INVALID_STATE</code>；摘要请求取消使用 <code>ABORTED</code>。失败不会提交检查点，
  自动路径也不会继续发送可能溢出的原模型请求。
- Browser Runtime 还会产生 <code>AGENT_MAX_MODEL_CALLS</code>、<code>AGENT_MAX_TOOL_CALLS</code>、<code>AGENT_EXECUTION_TIMEOUT</code>、<code>MODEL_OUTPUT_LIMIT_EXCEEDED</code> 和 <code>TOOL_RESULT_LIMIT_EXCEEDED</code>，五者都是不自动重试的明确终止。
- 压缩后的最终输入（工作消息、system 指令与本轮 Tool 定义）或摘要请求超过
  “窗口 − 输出预留”预算时，Browser Runtime 产生 <code>CONTEXT_WINDOW_EXCEEDED</code>：
  终止本次请求、保留完整历史，由用户调整输入后重新发起；不自动重复压缩、
  不静默删除历史、不更换模型。

---

## Browser Runtime 错误码

实现时复用现有 `AgentError` 形状，不创造第二套错误体系：

| 错误码 | 触发条件 |
| --- | --- |
| `MODEL_PROTOCOL_ERROR` | 事件顺序、停止原因、Tool Call ID 或 Tool 批次形状违约 |
| `AGENT_MAX_MODEL_CALLS` | 达到已有模型调用上限 |
| `AGENT_MAX_TOOL_CALLS` | 本批预检发现将超过整轮 Tool 调用上限 |
| `AGENT_EXECUTION_TIMEOUT` | 整体 Execution 超过 `maxDurationMs` |
| `MODEL_OUTPUT_LIMIT_EXCEEDED` | 单次模型调用聚合字符超限 |
| `TOOL_RESULT_LIMIT_EXCEEDED` | Tool 结果文本超限 |
| `CONTEXT_WINDOW_EXCEEDED` | 压缩后输入或首次输入超过最终窗口预算（Browser 本地检查，或服务端摘要超限的 413 响应） |
| `INVALID_STATE` | 缺少必需模型 usage、压缩边界或严格上下文配置/响应不成立 |

上述错误均为 `retryable: false`。是否重新发起一次新 Execution 由用户或宿主决定，框架不自动重试。
