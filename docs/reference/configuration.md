# Starter 配置参考

## 完整配置参考

所有键位于 <code>patchbridge-agent</code> 下，未知字段在绑定期失败。

### Starter 配置总表

| 配置键 | 默认值 | 范围、条件和行为 |
| --- | --- | --- |
| <code>enabled</code> | <code>true</code> | false 时不装配整个 Starter |
| <code>base-path</code> | <code>/ai</code> | 匹配 <code>/[A-Za-z0-9._~-]+(?:/[A-Za-z0-9._~-]+)*</code> 或根路径 <code>/</code>；相对路径与尾斜杠绑定期失败。该前缀是 Starter 独占命名空间：宿主 Controller 不得映射到该空间内，否则启动失败；<code>/</code> 表示全部 MVC 路径归 Starter 所有 |
| <code>model.base-url</code> | 无 | 默认 Provider 必填；绝对 HTTP(S)，禁止 user-info、query、fragment |
| <code>model.api-key</code> | 无 | 可空，取决于上游网关 |
| <code>model.model</code> | 无 | 默认 Provider 必填、不可空白 |
| <code>model.context-window-tokens</code> | 无 | 必填安全正整数；当前模型上下文窗口。自动压缩阈值固定派生为窗口的 80%，框架不按模型名猜测 |
| <code>model.keep-recent-tokens</code> | <code>min(20000, floor(context-window-tokens × 20%))</code> | 可选安全正整数；压缩后保留近期真实消息的 token 预算，必须小于自动压缩阈值 |
| <code>model.reserved-output-tokens</code> | <code>floor(context-window-tokens × 10%)</code> | 可选安全正整数；为模型输出预留的窗口容量，与 80% 阈值之和必须小于窗口 |
| <code>model.connect-timeout-ms</code> | <code>10000</code> | 大于等于 0；0 表示无限 |
| <code>model.read-timeout-ms</code> | <code>300000</code> | 大于等于 0；0 表示无限 |
| <code>conversations.list-limit</code> | <code>50</code> | 必须大于 0 |
| <code>audit.enabled</code> | <code>true</code> | false 才停止审计写入 |
| <code>audit.payload-mode</code> | <code>metadata-only</code> | 仅 full、metadata-only、none |
| <code>audit.summary-max-length</code> | <code>4000</code> | 必须大于 0 |
| <code>mcp.enabled</code> | <code>true</code> | false 时不装配默认 MCP Client/Store/Registry/Manager/MCP Admin；不禁止宿主显式自定义 Bean |
| <code>mcp.source</code> | <code>properties</code> | 仅 properties 或 jdbc；互斥、不合并、不失败切换 |
| <code>mcp.namespace</code> | <code>mcp</code> | 1-128 字符；字母数字、下划线、连字符，可用点分段 |
| <code>mcp.servers</code> | 空 Map | properties 模式 Server；不可显式为 null |
| <code>mcp.jdbc.encryption-key</code> | 无 | 默认 JDBC Store 需要；标准 Base64 解码后恰好 32 字节 |
| <code>admin.enabled</code> | <code>false</code> | true 时必须提供 AdminAccessPolicy |

<code>audit.payload-mode=full</code> 才组装请求/响应摘要，并仍经过 <code>AuditRedactor</code>。metadata-only 和 none 当前都不保存 payload 摘要，但仍保存调用元数据；完全关闭需使用 <code>audit.enabled=false</code>。

### 上下文窗口与压缩预算

`model.context-window-tokens` 是整个 Starter 的硬配置，包括使用自定义 Provider 的场景。
服务端派生并通过 `GET {basePath}/model/config` 返回唯一参数：

~~~text
automaticThresholdTokens = floor(contextWindowTokens × 0.80)
keepRecentTokens          = min(20_000, floor(contextWindowTokens × 0.20))
reservedOutputTokens      = floor(contextWindowTokens × 0.10)
~~~

自动阈值不开放配置，避免不同 Browser 使用不同触发点。128,000 token 窗口对应 102,400
自动阈值、默认 20,000 近期预算与 12,800 输出预留；32,000 token 窗口对应 25,600 阈值、
6,400 近期预算与 3,200 输出预留。如果显式设置 `model.keep-recent-tokens`，该值必须大于 0
且小于自动阈值；如果显式设置 `model.reserved-output-tokens`，该值必须大于 0 且与自动阈值
之和小于窗口，非法配置在 `ContextCompactionSettings` 构造阶段失败。

输出预留是模型输入最终预算（窗口 − 输出预留）的唯一派生来源：Browser 的最终窗口检查
（工作消息、system 指令、本轮 Tool 定义）与服务端摘要请求预算都使用它，
任何一层不得另行推导。

~~~yaml
patchbridge-agent:
  model:
    base-url: ${PATCHBRIDGE_AGENT_MODEL_BASE_URL}
    model: ${PATCHBRIDGE_AGENT_MODEL}
    api-key: ${PATCHBRIDGE_AGENT_MODEL_API_KEY:}
    context-window-tokens: ${PATCHBRIDGE_AGENT_MODEL_CONTEXT_WINDOW_TOKENS}
    # 只有明确需要改变默认近期预算时才配置
    keep-recent-tokens: 16000
~~~

摘要固定使用当前模型。缺失正常响应 usage、摘要失败或 Provider 状态投影失败均显式失败，
没有字符估算替代、重试、换模型或清空状态路径。详细使用方式见
[《使用上下文压缩》](../guides/context-compaction.md)。

### MCP Server 子配置

每个 <code>mcp.servers.&lt;name&gt;</code> 支持：

| 配置键 | 默认值 | 范围和要求 |
| --- | --- | --- |
| Server name | 无 | 1-64 字符；首字符字母数字，其余可含下划线、连字符 |
| <code>url</code> | 无 | HTTP(S) 绝对地址；禁止 user-info、fragment；允许 query |
| <code>transport</code> | <code>streamable-http</code> | 当前只支持此值 |
| <code>enabled</code> | <code>true</code> | 是否发布该 Server 的 Tool |
| <code>timeout-ms</code> | <code>30000</code> | 100..300000 |
| <code>cache-ttl-ms</code> | <code>300000</code> | 1000..86400000 |
| <code>auth.type</code> | <code>none</code> | none/basic/bearer/api-key-header/static-headers |
| <code>auth.username</code> | 无 | 仅 basic |
| <code>auth.password</code> | 无 | 仅 basic |
| <code>auth.token</code> | 无 | 仅 bearer 或 api-key-header |
| <code>auth.header-name</code> | 无 | 仅 api-key-header |
| <code>auth.headers</code> | 空 Map | 仅 static-headers；受 Header 白名单规则约束 |
| <code>tools.include</code> | null | null/空表示不限制 |
| <code>tools.exclude</code> | null | 不得与 include 重叠 |
| <code>tools.permissions</code> | 空 Map | 远端 Tool 名到宿主权限标识 |

auth 类型是严格互斥联合：basic 只接受 username/password；bearer 只接受 token；api-key-header 只接受 header-name/token；static-headers 至少一个 Header；none 不接受任何凭据字段。非当前类型字段、未知嵌套字段、Header 值中的 CR/LF 都明确失败。自定义 Header 名必须是 RFC token，且不能是 host、content-length、transfer-encoding、connection、keep-alive、proxy-connection、proxy-authorization、te、trailer、upgrade、accept、content-type、mcp-session-id、mcp-protocol-version、authorization 或 cookie。

URL 字符校验不是 SSRF 防护。生产环境必须用出站代理、防火墙、DNS/网络策略和管理权限限制可访问目标。

### properties 模式示例

~~~yaml
patchbridge-agent:
  mcp:
    enabled: true
    source: properties
    namespace: mcp
    servers:
      inventory:
        url: https://mcp.example.com/mcp
        transport: streamable-http
        enabled: true
        timeout-ms: 30000
        cache-ttl-ms: 300000
        auth:
          type: bearer
          token: ${MCP_INVENTORY_TOKEN}
        tools:
          include:
            - query_stock
          permissions:
            query_stock: inventory:read
~~~

properties 模式是只读配置源。Admin 可以查看、刷新和测试连接，但 create/update/enable/delete 会返回 <code>409 MCP_CONFIG_READ_ONLY</code>。

### JDBC 模式示例

~~~yaml
patchbridge-agent:
  admin:
    enabled: true
  mcp:
    enabled: true
    source: jdbc
    jdbc:
      encryption-key: ${PATCHBRIDGE_AGENT_MCP_ENCRYPTION_KEY}
~~~

只使用外部密钥系统或环境变量注入密钥。Admin 查询只返回 authType 和 credentialConfigured，不回显 token、密码或静态 Header。
