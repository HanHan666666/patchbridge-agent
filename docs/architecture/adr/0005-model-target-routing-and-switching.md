# ADR-005：部署模型目录、统一路由与显式会话切换

- 状态：Accepted（最小部署目录与路由已实施；在线管理另行决策）
- 决策日期：2026-09-24
- 替代关系：扩展 ADR-001、ADR-002 与 ADR-004；替代本 ADR 早期的 JDBC 管理前置方案
- 关联里程碑：R1.8、R2
- 当前实施状态以[路线图](../../roadmap.md)为准；旧完整后台方案见[历史设计稿](../designs/model-target-registry-and-switching.md)

## 背景

单一 `patchbridge-agent.model.*` 和全局 Provider 无法保证 Browser 模型流、摘要、Java Gateway 与恢复后的会话使用同一协议、地址、窗口和私有状态解释器。仅切换上游模型名不能表达一个可执行目标。此前设计稿提出 JDBC 模型管理后台，但维护者决定先用部署配置验证目标路由和第二种真实协议；完整在线管理没有成为前置条件。

## 决策

1. `patchbridge-agent.models` 是默认实现的**唯一配置来源**：`default-target` 指向 `targets` 中一个启用目标；每个目标显式声明 `protocol`、`routing-revision`、endpoint、上游模型、能力和上下文窗口。凭据由环境变量或宿主安全配置注入服务端。宿主可以完整替换 `ModelTargetCatalog`，不与 YAML 合并。当前不提供模型 CRUD、数据库密钥存储或管理后台。
2. 公开身份为 `ModelTargetRef { targetId, routingRevision }`。`routingRevision` 由部署维护者在影响协议、账户、模型、能力或窗口的变更时递增。旧引用失效时明确报错，不自动转到默认目标。多个目标可启用，默认目标只用于新草稿和明确采用默认值的可信 Java 调用；持久化会话保存自身当前引用。
3. `ModelProviderRouter` 统一执行存在性、启用状态、修订和授权检查。Browser 模型流、摘要、Java `ModelGateway`、目录和 handoff 走同一 Catalog/Router；认证 HTTP 用户与可信 JVM 使用不同的 `ModelAccessContext`。协议通过 `ModelProtocolAdapterFactory` 绑定到各个不可变目标。当前协议为 `openai-chat-completions` 和 `anthropic-messages`。
4. `ConversationContext` 原子保存完整 `messages + modelTarget + modelContext`。首轮创建携带稳定的完整 Context；普通保存不能改目标。只有显式 handoff 能在当前会话 revision 下更换目标。Browser 只接收脱敏目录，不接收 endpoint 或密钥。
5. handoff 保留完整可见历史、文本 checkpoint 和 retained 边界，清除旧 Provider 的 `ModelState`，为新窗口重估 usage。目标无法原样编码当前工作消息或预算超限时拒绝，不改旧会话。保存会话从服务端原历史生成候选并以 revision 原子提交；草稿只返回候选，由 Browser 成功后提交状态。运行中的 AgentExecution 不允许切换。
6. Anthropic Messages 的签名思考块只放在协议私有 `ModelState` 中，公开 ReasoningBlock 仅供展示，不转换为正文。协议解码完整 `message_stop` 后才交付下一状态；取消、断流或错误不产生成功续接状态。普通流和摘要都使用同一目标引用和窗口预算。

## 原因

此边界让两个不同 HTTP 协议共享一套会话、授权和预算逻辑，直接检验 Provider 抽象。部署配置可满足当前验证范围，避免在缺少真实管理需求时先引入 JDBC 配置复制、密钥轮换、分布式刷新和管理事务。保持可替换 Catalog 端口，为未来独立决策留下空间，而不在当前运行路径中叠加双来源。

## 代价与影响

- 更换目标配置需要重新部署，并由维护者负责推进 `routingRevision`；框架不推测配置语义是否变化。
- 旧单模型配置、旧会话 JSON 和数据库 schema 不能静默兼容。升级前须备份，并由宿主明确迁移或重建历史会话；不自动给旧会话补默认目标。
- 当前访问策略默认允许已认证用户访问启用目标。需要按用户或租户细分权限的宿主必须提供自己的 `ModelAccessPolicy`。
- “真实协议联调通过”仅证明已测试路径，不能证明所有第三方兼容端点、图片、工具交错和长期状态恢复都可用。验收范围以[路线图](../../roadmap.md)记录为准。

## 不采用的方案

| 方案 | 原因 |
| --- | --- |
| 继续只覆盖 `ModelRequest.model` | 无法选择协议、endpoint、能力、窗口和凭据，且浏览器可绕过目录 |
| 同时保留旧单模型与新目录 | 两份事实会导致流、摘要和会话恢复产生不同路由 |
| 先实现完整 JDBC/Admin 模型管理 | 当前目标是验证最小路由和第二协议，管理需求与生命周期尚未确定 |
| 目标失败时自动换默认模型 | 会把私有状态、窗口和费用语义悄然改变 |
| 转译签名、删除图片或伪造 Tool Result | 破坏真实历史和可验证的执行事实 |

## 验收约束

Browser、摘要和 Java Gateway 均不能绕过精确目标引用；禁用、修订过期、无权限和未注册协议明确失败。handoff 成功前完整历史不变，失败不落库。配置、HTTP/Browser 契约、Demo 与测试必须同步；完成状态只在路线图维护。
