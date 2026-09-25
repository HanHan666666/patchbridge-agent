# PatchBridge Agent 文档中心

> 本文档默认使用中文。类名、接口名、配置项、HTTP 字段、错误码和协议事件保留源码原名。

> 适用版本：`0.1.0-SNAPSHOT`。发布状态：source-only pre-release，当前没有发布到 Maven Central 或 npm 公共仓库；本文中的 Maven 和 Browser 接入都以本仓库源码、本地 Maven Reactor 和 Starter 内置 Bundle 为前提。

> 文中的账号、密钥和业务数据示例均为占位符。DeepSeek Flash 的模型名与接口地址是可用示例，部署前仍须核对当前官方文档和账户能力；不要把任何真实凭据提交到仓库。

> 本文面向实际接入 PatchBridge Agent 的 Java、前端、平台安全和运维人员。阅读本文不要求先了解项目设计历史；当前实施状态以[路线图](roadmap.md)为准，架构边界以[架构总览](architecture/overview.md)和 ADR 为准，具体行为以当前源码和测试为准。

当前完成度、后续顺序与暂缓范围统一维护在[《路线图与当前进度》](roadmap.md)。模块边界、六边形架构、显式状态机和设计模式的完整说明见[《架构总览》](architecture/overview.md)与[ADR 索引](architecture/adr/README.md)。



## 第一次使用

- [项目简介](../README.md)
- [快速开始](../QUICKSTART.md)：从零构建、启动 Demo、完成最短真实链路。
- [从源码构建](guides/build-from-source.md)：Java、Web 与 Starter 内置 Bundle 的完整构建步骤。
- [接入 Spring Boot 2 Starter](guides/spring-boot-integration.md)：引入依赖、准备数据库、配置模型、声明 Tool、最小页面入口。
- [接入 Browser](guides/browser-integration.md)：Headless Controller 与默认 Widget 的接入方式。
- [使用上下文压缩](guides/context-compaction.md)：完整聊天历史、80% 自动触发、手动检查点与 Provider 要求。
- [Demo 使用与验收](guides/demo-validation.md)：Demo 启动、入口、角色和逐项验收路径。

## 按任务查找

### Java 后端接入

- [Java Tool 开发](guides/java-tools.md)：`@AiTool` / `@AiParam`、Schema 边界、调用与重试。
- [Java 后端模型调用](guides/java-model-invocation.md)：`ModelGateway` 单次调用文本、图片、异步、取消与宿主自行保存。
- [生产接入准备、身份权限与排障](guides/production-readiness.md)：`CurrentUserProvider`、`ToolAccessPolicy`、owner 隔离、Admin 权限、安全上线检查、故障处理。

### Browser 与 UI

- [上下文压缩](guides/context-compaction.md)：自动与手动压缩、窗口配置、失败边界和验证步骤。
- [前端 Tool](guides/frontend-tools.md)：页面注册 Browser Local Tool、Schema 校验和执行边界。
- [WebMCP Adapter](guides/webmcp.md)：接入 `document.modelContext`。
- [Tools Inspector](guides/tools-inspector.md)：只读查看当前 Tool 目录与执行快照。
- [Call Trace](guides/call-trace.md)：按 Execution 查看模型、Tool、确认、耗时与 token。
- [定制默认 Widget 样式](guides/widget-customization.md)：CSS Variables、`::part()`、`theme="none"`。

### MCP 与 Admin

- [Global MCP 与 Admin](guides/global-mcp.md)：properties/JDBC 配置、凭据加密、管理台使用。

## Reference

- [Starter 配置参考](reference/configuration.md)
- [HTTP 与 SSE 契约参考](reference/http-api.md)
- [错误码参考](reference/error-codes.md)
- [Browser API 参考](reference/browser-api.md)
- [Runtime 契约参考](reference/runtime-contracts.md)

## Architecture、ADR 与 Research

- [架构总览](architecture/overview.md)
- [项目愿景、实现一致性与方向修正审查](architecture/reviews/vision-and-implementation.md)：2026-09-05 的愿景对照、五处已复现缺口、修正验收条件与 R1.8 顺序建议；整改状态以路线图为准。
- [架构决策记录（ADR）](architecture/adr/README.md)
- [可管理模型目标、按会话路由与显式切换技术方案](architecture/designs/model-target-registry-and-switching.md)：R1.8 待讨论方案，不代表当前已经支持后台模型配置或会话切换。
- [调研：思考模型 reasoning_content 回传规则](research/reasoning-content.md)

## Roadmap 与 Release

- [路线图与当前进度](roadmap.md)
- [v0.1 版本说明](releases/v0.1/release-notes.md)
- [v0.1 发布检查清单](releases/v0.1/release-checklist.md)
- [v0.1 公开契约与安全默认值审计](releases/v0.1/audits/public-contract-and-security.md)
- [v0.1 源码发布收口代码质量审计](releases/v0.1/audits/source-quality.md)
- [v0.1 源码发布候选预检（rc.2）](releases/v0.1/evidence/rc2-preflight.md)

## 包级 README

- [`@patchbridge-agent/agent`](../web/packages/agent/README.md)：Headless 浏览器核心。
- [`@patchbridge-agent/widget`](../web/packages/widget/README.md)：默认参考 Web Component。

## 历史文档

以下内容只作为历史背景保留，不代表当前行为：

- [原始纯前端 Agent 架构实现方案](archive/original-design.md)
- [前端架构设计：AgentController + 单一状态源（v0.1）](archive/frontend-architecture-v0.md)

## 当前能力总览

下表是 v0.1 已有实现并可通过 Demo 或底层验证观察的能力摘要。2026-09-05 审查发现的
Tool 路由、会话保存、取消后继续、异常分类与压缩预算缺口见
[审查报告](architecture/reviews/vision-and-implementation.md)，当前完成状态以[路线图](roadmap.md)为准：

| 能力 | 设计目的 | Demo 入口 |
| --- | --- | --- |
| Global MCP 配置 | 让后端统一保管远程 MCP endpoint 与凭据，运行时热更新 Tool 路由 | `/ai-admin/` 的 MCP 管理区（Demo 空库首启自动预置麦当劳 MCP 示例 `mcd`：无凭据、默认停用，在管理区编辑认证为 bearer 并填入令牌后启用） |
| 纯前端 Tool | 直接复用页面状态、前端 Service 或已有 HTTP API，不经过 `/ai/tools/call` | 首页注册的 `frontend.device_search` |
| WebMCP Adapter | 把浏览器 `document.modelContext` Tool 接到同一 Agent，而不污染核心包 | 首页 WebMCP 状态；支持时注册 `page.open_tools_tab` |
| Tools Inspector | 只读展示 Agent 当前真正可调用的全部 Tool | 首页“Tools 调试”页签 |
| Call Trace | 按 Execution 展示模型/Tool/确认调用细节、耗时与 token；显式开启 persistent 后在浏览器本地保留 | 首页“调用轨迹”页签（Demo 显式开启） |
| 上下文压缩 | 完整保留聊天历史，只用当前模型把较旧工作上下文压成检查点；80% 自动触发并支持手动压缩 | 首页聊天 Widget 顶栏的 token 占比、检查点与“立即压缩” |
| Widget 样式扩展 | 框架提供参考 View，但颜色、字体、尺寸和结构样式由宿主决定 | Demo 用 CSS Variables 与 `::part` 覆盖默认主题 |
| 厂商中立 Runtime | 用 Message + ContentBlock、ModelState、结构化流和独立 Execution 承载可替换 Agent Loop，并以五项资源预算、唯一终态门和严格 Tool 批次预检限定执行 | 首页 Runtime 扩展状态、HITL、停止按钮、`max-tokens` 提示和会话恢复 |
| Java 后端单次模型调用 | 让宿主 Service 复用同一 Provider 发起一次模型请求，不引入后端 Agent Loop 或默认持久化 | `/demo-api/model-invocations/text` 与 `/demo-api/model-invocations/image` |

## 协议参考

- [MCP 2026-07-28 官方发布说明](https://blog.modelcontextprotocol.io/posts/2026-07-28/)
- [WebMCP Community Group Draft](https://webmachinelearning.github.io/webmcp/)


## 参考文档

- [项目根 README](../README.md)
- [快速开始](../QUICKSTART.md)
- [路线图与当前进度](roadmap.md)
- [当前架构说明](architecture/overview.md)
- [ADR-001：厂商中立 Agent Runtime 核心契约](architecture/adr/0001-provider-neutral-runtime.md)
- [ADR-002：ModelState 生命周期所有权与显式会话连续状态重置](architecture/adr/0002-model-state-lifecycle.md)
- [ADR-003：Browser Agent Runtime 生产级执行守卫](architecture/adr/0003-browser-runtime-guards.md)
- [ADR-004：完整聊天历史与模型工作上下文分离的压缩机制](architecture/adr/0004-context-compaction.md)
- [ADR-005（Accepted）：部署模型目录、按会话路由与显式切换](architecture/adr/0005-model-target-routing-and-switching.md)
- [可管理模型目标、按会话路由与显式切换技术方案](architecture/designs/model-target-registry-and-switching.md)
- [Java 后端单次模型调用 API 设计](architecture/designs/java-model-gateway.md)
- [v0.1 公开契约与安全默认值审计](releases/v0.1/audits/public-contract-and-security.md)
- [Headless Browser README](../web/packages/agent/README.md)
- [Widget README](../web/packages/widget/README.md)
- [调研：思考模型 reasoning_content 回传规则](research/reasoning-content.md)
- [原始完整设计方案](archive/original-design.md)
- [前端架构设计：AgentController + 单一状态源（v0.1）](archive/frontend-architecture-v0.md)

## 文档贡献

- [文档写作与维护规范](contributing/documentation.md)
