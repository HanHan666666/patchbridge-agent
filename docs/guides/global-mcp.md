# 配置 Global MCP 与 Admin

## 使用与配置

### 当前 MCP 协议边界

v0.1 只支持 Global MCP、协议版本 <code>2026-07-28</code>、无状态 Streamable HTTP 和 Tools。Browser 始终调用统一 Tool Registry，不直接持有远端 MCP 凭据。

Browser Cookie、Authorization 和任意用户 Header不会默认转发给远端 MCP。远端凭据只来自选定的 properties 或 JDBC 配置源。

响应解析为严格模式：JSON-RPC 信封必须声明 <code>jsonrpc=2.0</code> 且 id 与请求一致，<code>result</code> 与 <code>error</code> 互斥且必居其一；<code>tools/list</code> 必须返回 <code>tools</code> 数组（每个 Tool 含非空 <code>name</code> 与对象 <code>inputSchema</code>），<code>tools/call</code> 必须返回 <code>content</code> 数组。任何违约都映射为 <code>502 MCP_FAILED</code> 失败，不会降级为空 Tool 列表或“空成功”。

### properties 与 JDBC

| 模式 | 适用场景 | 可变性 |
| --- | --- | --- |
| properties | 部署时固定 Server，配置即代码 | 只读；重启重新绑定 |
| jdbc | Admin 在线管理 Global Server | CRUD、启停、刷新、测试、revision 乐观锁 |

二者绝不合并。JDBC 密钥缺失、格式错误或 DataSource 缺失时启动失败，不降级为明文、内存或 properties。

JDBC 配置变更侦测使用单行 <code>agent_mcp_config_generation</code> 表：每次 create/update/delete 在同一事务内递增 generation，多实例通过该版本决定是否重读配置；稳态请求的版本读取是 O(1) 单行查询，不扫描配置行、不读取凭据密文。该表由 schema 初始化脚本创建并播种，升级部署需执行最新 <code>agent-schema-*.sql</code>。

### 工具版本共享密钥

两种配置源都必须提供 `patchbridge-agent.mcp.tool-version-key`。用 `openssl rand -base64 32`
单独生成，将同一密钥注入所有实例，并在重启后保持不变；它与 JDBC 凭据加密密钥独立。
默认 Registry 在密钥缺失或格式不合法时启动失败。

公开的 Tool 版本包含路由、认证身份和定义变化，但使用 HMAC-SHA-256 避免成为密码猜测的
离线校验器。认证凭据或共享密钥变化都会使旧引用失效；轮换密钥时统一更新实例，浏览器
重新发现工具后由用户重新发起调用，框架不自动重试。完整字段见[配置参考](../reference/configuration.md)。
底层验证见 `McpToolRegistryVersionTest`：相同密钥跨实例一致、凭据变化拒绝旧引用、不同
密钥产生不同版本、密钥轮换拒绝旧调用，以及缺失或非法密钥阻止启动。

### Admin 控制台

Admin 静态入口固定为 <code>/ai-admin/</code>。其页面、API 和静态资源都经过 <code>AdminAuthorizationInterceptor</code>；宿主还应在自己的 Security Filter Chain 中限制认证、来源网络和 CSRF/CORS。

MCP 配置响应只公开非秘密字段：name、url、transport、enabled、timeoutMs、cacheTtlMs、authType、credentialConfigured、tools、revision、时间和运行状态。创建请求顶层使用 name、url、transport、enabled、timeoutMs、cacheTtlMs、auth 和 tools，不允许 revision 或 credentialUpdate。更新请求使用同一配置字段并必须提供非负 revision 和 credentialUpdate；credentialUpdate 仅接受 KEEP、REPLACE、CLEAR，body 中 name 如存在必须等于路径 name。启停请求只接受显式 enabled 和非负 revision；删除通过 query revision 传递版本。auth 或 tools 显式 null 都拒绝。

Trace 统计卡片中的 P50/P95 耗时基于“最近一万次调用”的时间窗样本，不是全表精确分位数；窗口对耗时分布无偏，页面标签如实标注统计范围。

更新凭据由 Admin API 的明确指令处理；不要通过读取响应后再把空凭据写回实现“保持原值”。

### 当前未决 HTTP 行为

不存在 MCP Server 的本地资源错误当前没有独立 404 类型，部分 Admin 路径会被通用 <code>McpException</code> 映射成 <code>502 MCP_FAILED</code>。客户端不要据此推断一定是远端暂时故障；在该契约被正式调整前，应结合操作和响应 message 处理。

---

## 设计说明与 Admin 操作

### 设计取舍

第一版只实现 Global Server，不实现租户级、用户级或请求级 MCP 配置。配置源必须二选一：

- `properties`：从 YAML/外部化配置读取，只读；
- `jdbc`：由 Admin 管理全局配置，可创建、修改、启停和删除。

两种来源不合并，避免同名覆盖顺序、凭据来源和热更新归属变得不可解释。Registry 对外发布整表加载后的不可变路由快照；在途 Tool Call 继续使用旧快照，新请求使用新配置。

### 开启 JDBC 管理模式

分别生成版本签名与凭据加密使用的两份 32 字节密钥，命令各执行一次：

```bash
openssl rand -base64 32
```

通过环境变量或企业密钥管理系统注入，禁止提交到仓库：

```yaml
patchbridge-agent:
  admin:
    enabled: true
  mcp:
    tool-version-key: ${PATCHBRIDGE_AGENT_MCP_TOOL_VERSION_KEY}
    source: jdbc
    jdbc:
      encryption-key: ${PATCHBRIDGE_AGENT_MCP_ENCRYPTION_KEY}
```

`source=jdbc` 时缺少或格式错误的密钥会阻止启动，不会降级为明文或临时内存存储。凭据使用 AES-256-GCM 加密后进入 JDBC；Admin 查询响应只返回 `authType` 与 `credentialConfigured`，不会回显 token、密码或静态 Header。

Admin 默认关闭。开启后宿主仍必须提供自己的 `AdminAccessPolicy`，Starter 不推测企业角色，也不修改宿主 Spring Security URL 规则。

### 使用 Admin Demo

1. 用 Demo 的 `admin / admin123` 登录。
2. 打开 `/ai-admin/`，进入 MCP 配置区。
3. 新建 Server，填写稳定名称、真实 Streamable HTTP endpoint、超时、缓存 TTL、认证方式和 Tool 导入规则。
4. 保存后先执行“测试连接”；成功时查看延迟与 Tool 数量。
5. 在首页切到“Tools 调试”，确认 `MCP` 来源 Tool 已进入 Agent 的统一列表。
6. 修改、启停或删除时 UI 会携带 `revision`；并发修改冲突会明确提示刷新，不静默覆盖。

当前支持的认证类型：

- `none`
- `basic`
- `bearer`
- `api-key-header`
- `static-headers`

更新配置时凭据操作必须明确选择：`KEEP` 保留现有密文、`REPLACE` 使用本次完整凭据替换、`CLEAR` 清除凭据。这个三态协议用于区分“表单没有展示旧密钥”和“用户真的要删掉密钥”。

Tool 导入规则可配置 `include`、`exclude`，以及远程 Tool 名到宿主权限标识的 `permissions` 映射。最终是否可见、是否可调用仍由宿主 `ToolAccessPolicy` 判定。

当前协议 Adapter 明确锁定无状态 MCP `2026-07-28`：每个请求都携带协议版本、
`Mcp-Method` / `Mcp-Name` 路由 Header 与逐请求客户端 `_meta`，不依赖服务端 Session。
这是当前唯一 MCP 传输契约，不提供另一套握手或自动降级，避免同一配置出现两套运行语义。