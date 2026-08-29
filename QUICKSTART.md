# 快速开始（Quick Start）

本文提供 v0.1 的最短构建与 Demo 路径；完整生产接入、全部配置和排障见[《文档中心》](docs/README.md)，版本边界见[《v0.1 版本说明》](docs/releases/v0.1/release-notes.md)。架构设计见 [README.md](README.md)，Global MCP、纯前端 Tool、WebMCP、Tools Inspector、Call Trace 与样式扩展的设计原因见[《文档中心》](docs/README.md)。

## 1. 环境要求

| 组件 | 版本 | 用途 |
| --- | --- | --- |
| JDK | 8+（开发验证于 Corretto 8） | Java 侧全部模块 |
| Maven | 3.6+ | Java 构建（或直接使用 IDE 内置 Maven） |
| Node.js | 22.12+ | 前端 monorepo 构建（由当前 Vite lock 约束；widget 产物已随 Starter 打包，宿主无需 Node） |
| 数据库 | H2 / MySQL | 会话与审计存储（demo 默认 H2 文件库） |

## 2. 构建

```bash
# Java 七个子模块（包含可选 model-webflux Adapter 与 demo）
cd java
mvn install

# 前端（Agent / Widget + 可选 WebMCP Adapter / Tools Inspector / Call Trace）
cd ../web
npm ci               # 严格按 package-lock.json 安装，不改写锁文件
npm test             # 测试五个 workspace
npm run build        # 构建五个 workspace；四个 IIFE 产物复制进 Starter 资源目录

# Web 构建更新了 Starter 内置 Bundle，返回 Java Reactor 后重新安装 Starter
cd ../java
mvn install -pl patchbridge-agent-spring-boot2-starter
```

## 3. 运行 Demo

Demo 是一个模拟的“企业设备管理系统”：Spring Security 表单登录 + 四个角色 + 本地 Tool + Global MCP JDBC 管理。
Demo 不内置 Mock AI 或 Mock MCP 协议服务；模型与 MCP 调用都连接用户显式配置的真实 endpoint。

```bash
cd java
# JDBC MCP 凭据密文密钥必须显式注入（生成：openssl rand -base64 32）
export PATCHBRIDGE_AGENT_MCP_ENCRYPTION_KEY='<32 字节密钥的 Base64>'
# 模型地址和名称必须显式配置；API Key 是否必需由目标网关决定
export PATCHBRIDGE_AGENT_MODEL_BASE_URL='https://api.your-llm.com/v1'
export PATCHBRIDGE_AGENT_MODEL='your-model'
# 必须与所选模型的真实上下文窗口一致
export PATCHBRIDGE_AGENT_MODEL_CONTEXT_WINDOW_TOKENS='128000'
# export PATCHBRIDGE_AGENT_MODEL_API_KEY='<目标网关 API Key，仅在需要时取消注释>'
# 先安装当前多模块 Reactor，避免 Demo 误用本机仓库中的旧 SNAPSHOT
mvn install -DskipTests
mvn -pl patchbridge-agent-demo spring-boot:run
```

| 入口 | 地址 |
| --- | --- |
| 模拟企业系统首页（含 AI 助手） | http://localhost:8080/ |
| 管理后台（审计 Trace / Global MCP 配置） | http://localhost:8080/ai-admin/ |
| 登录账号 | admin/admin123、operator/op123456、user/user123456、auditor/auditor123 |

角色差异（RBAC 对后端 Tool 可见性的影响）：

- `admin`：可见全部本地 Tool，可进管理后台配置真实 MCP；
- `operator`：可查询、可重启设备，MCP Tool 仍由宿主权限策略判定；
- `user`：仅可见本地只读 Tool；
- `auditor`：不可见 Demo 的后端业务 Tool，但可进管理后台查看审计。

首页注册的 `frontend.device_search` 是页面能力，不走后端 Tool RBAC；
它调用的 `/demo-api/devices` 是作为一个调用现有业务接口转Tool演示。

## 4. 在宿主应用中接入（真正的使用方式）

### 4.1 引入依赖

```xml
<dependency>
    <groupId>io.patchbridge.agent</groupId>
    <artifactId>patchbridge-agent-spring-boot2-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

引入即获得 Agent `/ai/**` 端点、`/ai/assets/**` 前端产物与可替换的默认实现。
Admin Console 与 `/ai/admin/**` 默认关闭，不会因为引入 Starter 而向宿主暴露管理界面。

### 4.2 配置

```yaml
patchbridge-agent:
  model:
    base-url: https://api.your-llm.com/v1     # OpenAI-compatible
    model: your-model
    api-key: ${PATCHBRIDGE_AGENT_MODEL_API_KEY} # 环境变量注入，浏览器永远拿不到
    context-window-tokens: 128000 # 必须与所选模型真实窗口一致
  mcp:
    # 默认 properties：配置只读，不与 JDBC 数据合并
    source: properties
    servers:
      inventory:
        url: https://mcp.example.com/mcp
        transport: streamable-http
        auth:
          type: bearer
          token: ${MCP_INVENTORY_TOKEN}
```

需要 Global Admin 在线管理时，改为互斥的 JDBC 配置源：

```yaml
patchbridge-agent:
  admin:
    enabled: true
  mcp:
    source: jdbc
    jdbc:
      # 严禁写死在仓库；解码后必须恰好 32 字节
      encryption-key: ${PATCHBRIDGE_AGENT_MCP_ENCRYPTION_KEY}
```

在 JDBC 模式下，登录后访问 `/ai-admin/` 可新增、编辑、启停、删除、
测试连接和刷新 Global MCP Server。凭据只写不回显，并以 AES-256-GCM 密文落库。

宿主必须满足的硬依赖：

- 一个 `ConversationRepository`；使用默认 JDBC Adapter 时还必须提供 `DataSource`，v0.1 不做内存降级；
- 一个 `CurrentUserProvider` Bean（类路径有 Spring Security 时自动装配默认实现，否则自行提供）。

配置在绑定或默认 Bean 构造阶段严格校验，不会把拼写错误静默转成另一种行为：

- 默认模型 `base-url` 必须是无 user-info、query、fragment 的绝对 HTTP(S) 地址；
- 模型 `context-window-tokens` 必须显式配置为安全正整数；自动压缩固定在 80% 触发；
- `conversations.list-limit` 和 `audit.summary-max-length` 必须大于 0；
- `audit.payload-mode` 只接受 `full`、`metadata-only`、`none`；
- `mcp.source` 只接受 `properties` 或 `jdbc`，二者不合并；
- `mcp.enabled=false` 时不装配默认 MCP Client、Store、Registry 或 Admin，也不要求 JDBC 加密密钥；
- MCP 开启且 `source=jdbc` 时，32 字节 Base64 密钥是启动硬依赖。

Starter 自有 JSON 请求同样使用严格字段契约。未知字段、缺少必填字段、非法 JSON、
字段类型错误和非法 Admin 查询参数统一返回 `400 INVALID_ARGUMENT`，客户端不应依赖
Spring/Jackson 忽略多余字段。

Starter 默认用可信 `UserContext.userId` 作为会话归属键。若 userId 只在租户内唯一，
必须由宿主覆盖 `ConversationOwnerResolver`，例如：

```java
@Bean
public ConversationOwnerResolver conversationOwnerResolver() {
    return user -> user.getTenantId() + ":" + user.getUserId();
}
```

框架将返回值作为不透明 `ownerKey` 贯穿全部会话读写，不解释租户业务规则。
tenantId 必须来自 `CurrentUserProvider` 解析的服务端可信登录态，不能取自请求参数。

> 当前唯一 Schema 使用 `owner_key`、`blocks_json` 和 `model_context_json`。项目尚未发布，
> 不提供其他数据库结构的迁移或双写逻辑。本地开发数据库若不是当前结构，停止 Demo 后
> 直接重建；宿主首次集成时用自己的 Flyway / Liquibase 按当前建表脚本创建结构。

### 4.3 声明工具

在任意 Spring Bean 的方法上加注解：

```java
@AiTool(name = "device_restart", description = "重启指定设备（危险操作）",
        destructive = true, requireConfirmation = true,
        permissions = {"ai:tool:device:restart"})
public String restart(@AiParam(name = "sn", value = "设备序列号", required = true) String sn, AiRequestContext context) {
    // context.getUser() 是服务端注入的可信身份，绝不来自浏览器参数
    return deviceStore.restart(sn, context.getUser().getUsername());
}
```

`permissions` 只是交给宿主策略解释的 metadata。Starter 默认的
`AuthenticatedToolAccessPolicy` 对所有 Tool 只检查用户已登录，不解释该字段；Demo 提供
自定义策略并选择 ALL 语义。生产环境只要依赖角色、权限、租户或业务 ACL，就必须提供自己的
`ToolAccessPolicy`，并同时实现 discovery 与每次 invocation 的复核。

### 4.4 前端接入（两行）

```html
<script src="/ai/assets/patchbridge-agent.js"></script>
<patchbridge-agent endpoint="/ai" title="AI 助手"></patchbridge-agent>
```

Widget 顶栏会显示模型上下文占比，并在 80% 时自动压缩；空闲且存在安全历史前缀时也可
点击“立即压缩”。压缩不会删除完整聊天历史。配置与验证见
[《使用上下文压缩》](docs/guides/context-compaction.md)。

需要自定义 UI 时，直接使用 `@patchbridge-agent/agent` 包的 Controller 契约（Web Component 只是默认 View）：

```ts
import { createAgentController } from '@patchbridge-agent/agent';

const controller = createAgentController({ endpoint: '/ai' });
controller.subscribe(state => render(state));
await controller.initialize();
await controller.sendMessage('查询所有告警设备');
```

如果企业已有统一的 Bearer 刷新、CSRF 或请求拦截链，注入一个返回标准
`Response` 的 `HttpTransport`；Model SSE、Tool 和 Conversation 会共用它：

```ts
const controller = createAgentController({
  endpoint: '/ai',
  transport: { request: (url, init) => enterpriseFetch(url, init) },
});
```

Web Component 也支持 JavaScript 属性注入（不是 HTML 字符串属性）：

```js
document.querySelector('patchbridge-agent').httpTransport = transport;
```

### 4.5 配置 Agent Runtime

默认 Runtime 使用有界 Agent Loop。Headless 工厂可以注入 Hook、Model Interceptor、
Tool Interceptor 和模型调用上限：

```ts
const controller = createAgentController({
  endpoint: '/ai',
  runtime: {
    maxModelCalls: 8,
    hooks: [{ onEvent: (event, context) => auditQueue.push({ event, context }) }],
    modelInterceptors: [{
      async *intercept(request, context, signal, next) {
        for await (const event of next(request, context, signal)) {
          yield event;
        }
      },
    }],
    toolInterceptors: [{
      intercept: (invocation, next) => next(invocation),
    }],
  },
});
```

默认 Widget 使用等价的 JavaScript 属性：

```js
document.querySelector('patchbridge-agent').runtimeOptions = {
  limits: { maxModelCalls: 8 },
  hooks: [{ onEvent: event => console.debug(event.type) }],
};
```

Hook 只观察稳定生命周期；Interceptor 才能包围调用，且 `next` 只能执行一次。
需要确认的 Tool 会进入 `state.pendingConfirmation`，默认 Widget 提供批准 / 拒绝；流式时
发送按钮切换为“停止”，用于取消同一个 Execution。详细消息、ModelState、自定义 Model
与会话协议见 [`web/packages/agent/README.md`](web/packages/agent/README.md)。

### 4.6 可选调用轨迹

调用轨迹是独立分包，默认 Widget 不会自动渲染它：

```html
<script src="/ai/assets/patchbridge-agent-call-trace.js"></script>
<patchbridge-agent-call-trace id="call-trace"></patchbridge-agent-call-trace>
<script>
  document.addEventListener('patchbridge-agent-ready', event => {
    document.getElementById('call-trace').traceSource = event.detail.callTraceSource;
  });
</script>
```

它按 Execution 展示模型调用、Tool 调用、确认、耗时和 token。已完成模型调用还显示
`首 token` 等待延迟与首 token 后的平均输出 `tok/s`；缺少 Provider usage 时不显示速度。
轨迹按会话保存在当前浏览器 localStorage，每个会话最近 30 次；不写入服务端会话历史，
也不跨设备同步。
组件内的“ℹ️ 存储说明”解释原因、清理影响和服务端会话历史的边界。详细设计见
[《Call Trace 指南》](docs/guides/call-trace.md#设计说明与采集契约)。

### 4.7 可选 Admin

开启 Admin 时必须同时提供宿主授权策略：

```yaml
patchbridge-agent:
  admin:
    enabled: true
```

```java
@Bean
public AdminAccessPolicy adminAccessPolicy() {
    return (user, capability) -> user.getRoles().contains("AI_ADMIN");
}
```

框架不会推测宿主的角色名或 URL 放行规则。默认审计仅保存 metadata；显式选择
`payload-mode: full` 时仍应提供企业自己的 `AuditRedactor`。

## 5. 测试与验证套件

| 套件 | 命令 | 规模 |
| --- | --- | --- |
| Java 单元 / 集成测试 | `mvn clean test`（java/ 下） | 覆盖 Core 管线 / JDBC 隔离与凭据加密 / MCP 并发与动态配置 / Starter 安全 / 异步模型 / WebFlux Adapter |
| 前端单元测试 | `npm run test`（web/ 下） | 覆盖结构化 SSE / ContentBlock / ModelContext / 自动与手动压缩 / AgentExecution / HITL / Hook 与 Interceptor / 显式状态机 / HttpTransport / Unified Registry / WebMCP / Widget / Inspector / Call Trace 采集、持久化与组件契约 |
| API E2E（Node） | `node web/tools/e2e-api-test.mjs`（Demo 运行中） | 18 项 API、安全、模型窗口与资源断言 |
| 真实模型浏览器 E2E | `node web/tools/e2e-real-llm-test.mjs`（已配置真实模型的 Demo 运行中） | 覆盖工具决策、多模态识图和可观察时的取消路径 |

浏览器 E2E 依赖 `playwright-core` 和本机 Chrome；可通过
`E2E_BROWSER_CHANNEL=msedge` 等 Playwright channel 显式选择其他已安装浏览器。
两个 E2E 脚本均支持 `E2E_BASE` 环境变量覆盖目标地址（默认 `http://localhost:8080`），
便于在隔离端口验证而不影响正在运行的演示实例。

仓库不提供 Mock AI API。真实模型 E2E 不进入 CI，避免泄露密钥、自动消耗额度，
也避免把外部网络和供应商可用性变成提交合并的随机条件；模型协议边界由本地单元测试覆盖。

## 6. 目录结构

```
java/
  patchbridge-agent-annotations/     # @AiTool / @AiParam（零依赖）
  patchbridge-agent-core/            # 纯契约与默认实现（无 Spring）
  patchbridge-agent-model-webflux/   # 可选的非阻塞模型上游 Adapter
  patchbridge-agent-storage-jdbc/    # 会话 / 审计 JDBC 存储
  patchbridge-agent-mcp/             # Streamable HTTP MCP 客户端与工具导入
  patchbridge-agent-spring-boot2-starter/  # 自动装配 + /ai/** 端点 + 静态资源
  patchbridge-agent-demo/            # 模拟企业系统（Security + 本地 Tool + Global MCP 管理）
web/
  packages/agent/                   # Headless Runtime（ContentBlock / Model / Execution / Controller）
  packages/widget/                  # 默认 View：Web Component 聊天面板（块级平铺）
  packages/webmcp-adapter/          # 可选 document.modelContext 协议 Adapter
  packages/tool-inspector/          # 可拔插的只读 Tool 列表
  packages/call-trace/              # 可拔插的调用轨迹账本与本地存储说明
  tools/                            # 真实模型 API / 浏览器 E2E 脚本
```
