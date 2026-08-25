# 文档写作与维护规范

本文是 PatchBridge Agent 长期文档治理规则。新功能、架构变化和发布证据必须按这里的分类同步到唯一权威位置。

## 1. 文档分类

| 位置 | 主要读者 | 只负责什么 | 不负责什么 |
| --- | --- | --- | --- |
| 根 `README.md` | 第一次了解项目的人 | 定位、价值、核心边界、最小示例、文档导航、许可证 | 完整配置、端点表、详细架构和路线图 |
| `QUICKSTART.md` | 第一次运行的人 | 从零构建、启动 Demo、完成最短真实链路 | 完整生产配置和全部功能说明 |
| `docs/README.md` | 所有文档使用者 | 中文文档门户，按用户任务导航 | 复制各文档正文 |
| `docs/roadmap.md` | 维护者和采用者 | 唯一实施状态、范围、顺序和验收结果 | 详细使用说明和完整架构解释 |
| `docs/guides/` | 集成和使用框架的人 | 完成一个具体任务的步骤、边界、验证与 Demo | 完整配置枚举和架构历史 |
| `docs/reference/` | 查字段和契约的人 | 配置、API、错误码、公共类型和默认值 | 教学流程和方案讨论 |
| `docs/architecture/overview.md` | 架构师和贡献者 | 当前模块关系、责任、不变量与扩展边界 | 实时进度和历史审计 |
| `docs/architecture/designs/` | 实现者和审查者 | 一项已接受或待实施设计的完整方案 | 替代路线图状态 |
| `docs/architecture/adr/` | 架构维护者 | 已决策问题的背景、决策、代价和替代方案 | 使用教程 |
| `docs/research/` | 决策者和 Provider 实现者 | 调研问题、事实来源、结论和对项目的影响 | 声称未落地能力已经实现 |
| `docs/releases/` | 发布者和版本使用者 | 固定版本的说明、门禁、审计和证据 | 当前路线图 |
| `docs/archive/` | 需要追溯历史的人 | 已失效但值得保留的早期设计 | 当前实现说明 |
| `docs/contributing/` | 贡献者和执行型 AI | 如何编写、放置、检查和维护文档 | 产品使用说明 |

## 2. 唯一权威来源

同一个当前事实只保留一个持续维护的权威位置，其他文档只提供摘要并链接到权威文档：

- 当前完成度、下一阶段、暂缓范围：`docs/roadmap.md`
- 当前总体架构和模块责任：`docs/architecture/overview.md`
- 架构决策原因：Accepted ADR
- 首次运行命令：`QUICKSTART.md`
- 某项功能的操作步骤：对应 `docs/guides/*.md`
- 完整配置项和默认值：`docs/reference/configuration.md`
- HTTP/SSE 契约：`docs/reference/http-api.md`
- Browser 公共入口与选项：`docs/reference/browser-api.md`
- Runtime 结果、限额和扩展契约：`docs/reference/runtime-contracts.md`
- 稳定错误码：`docs/reference/error-codes.md`
- 安全上线和排障：`docs/guides/production-readiness.md`
- 某版本交付内容：对应 `docs/releases/<version>/release-notes.md`
- 某候选的验收证据：对应 `docs/releases/<version>/evidence/`

代码与测试仍是运行行为的最终事实来源。发现文档与代码冲突时，先确认正确契约，再修改唯一权威文档及必要引用，并同步路线图。

## 3. 中文优先与术语

- 文档标题、导航、正文和链接文字默认使用中文。
- 文件路径使用 ASCII `kebab-case`，不承担展示职责。
- 公共类名、接口名、方法名、配置项、HTTP 字段、错误码和协议事件保留源码原名并使用反引号。
- 第一次出现抽象概念时采用“中文解释（技术名称）”或“技术名称（中文解释）”。
- 不在不同页面使用多个中文别名指代同一公共类型。

建议固定术语：

| 技术名称 | 中文说明 |
| --- | --- |
| `Agent Runtime` | Agent 执行运行时 |
| `ModelState` | 模型续接状态 |
| `ContentBlock` | 结构化内容块 |
| `ToolRegistrySnapshot` | Tool 注册表执行快照 |
| `ModelGateway` | Java 单次模型调用门面 |
| `Hook` | 生命周期观察扩展点 |
| `Interceptor` | 调用链拦截扩展点 |
| `Call Trace` | Browser 调用轨迹 |
| `Tools Inspector` | Tools 只读检查器 |
| `Human-in-the-loop` | 人工确认流程，首次出现时可同时保留 HITL 缩写 |

## 4. 文件与路径规则

- `docs/` 下目录和文件统一使用 ASCII；
- 普通文件使用小写 `kebab-case.md`；
- ADR 使用四位编号：`0001-description.md`；
- 版本目录使用 `v0.1` 这种可识别版本名；
- 禁止空格、中文路径、括号、外部系统导出 ID 和日期前缀；
- 不创建没有真实内容的空模板或导航占位页；
- 不保留旧路径兼容文件或同内容双份文档。

## 5. 文档模板

### Guide

```markdown
# 使用中文描述的任务标题

## 目标
## 适用场景
## 前置条件
## 操作步骤
## 验证结果
## 安全与边界
## 常见问题
## 相关文档
```

### Reference

```markdown
# 被查询契约的中文标题

## 适用范围
## 完整契约
## 默认值与约束
## 错误语义
## 示例
## 兼容与版本边界
```

### Architecture Design

```markdown
# 设计名称

- 文档类型：实施设计
- 状态：待讨论 / 已接受，待实施 / 已实施
- 关联里程碑：...

## 背景
## 目标
## 非目标
## 当前约束
## 方案
## 责任边界
## 实施顺序
## 验收条件
```

### ADR

```markdown
# ADR-0000：决策名称

- 状态：Proposed / Accepted / Superseded
- 决策日期：YYYY-MM-DD
- 替代关系：无 / ADR-XXXX

## 背景
## 决策
## 原因
## 代价与影响
## 被拒绝的方案
## 验收或约束
```

### Research

```markdown
# 调研标题

- 文档类型：Research
- 状态：Current / Historical
- 调研日期：YYYY-MM-DD

## 问题
## 调研范围
## 事实与来源
## 结论
## 对项目的影响
## 是否形成架构决策
```

### Release Evidence

```markdown
# 版本或候选验收记录

- 版本或候选：...
- Commit / Tag：...
- 验收日期：...

## 环境
## Gate
## 实际结果
## 偏差与残余风险
## 最终结论
```

## 6. 同步要求

新增、修改、删除或暂缓功能时，必须同一次变更中同步：

1. `docs/roadmap.md` 的状态、范围、验收条件、最近更新时间和更新记录；
2. 对应 Guide 的操作步骤、边界和验证方式；
3. 相关 Reference 的配置、API、错误码或公共类型；
4. 架构边界变化时同步 `docs/architecture/overview.md` 或新增 ADR；
5. Demo 或明确的底层验证入口；
6. 根 README、QUICKSTART 和文档门户中的入口。

## 7. 提交前检查

- 全部 tracked Markdown 链接可解析；
- `docs/` 下路径为 ASCII，普通文件使用小写 `kebab-case.md`；
- 不存在指向旧文档路径的有效 Markdown 链接；
- 不存在空目录、空模板、旧路径兼容文件或同内容双份文档；
- 历史 Release 数字、候选 Commit 和审计结论未被改写；
- 历史、研究、发布证据与当前使用文档物理隔离；
- 工作区中无关文件未被纳入。

常用检查：

```bash
git diff --check
git ls-files -z '*.md' | xargs -0 lychee --offline --include-fragments --no-progress
git ls-files docs | rg '[^\x00-\x7F]| '
```
