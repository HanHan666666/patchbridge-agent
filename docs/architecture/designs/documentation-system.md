# 开源文档信息架构与中文写作体系重构方案

- 文档类型：实施设计与任务规范
- 状态：已接受，已实施
- 关联里程碑：R1.6
- 主要读者：负责执行文档重构的 AI 或维护者
- 当前基线：`8aef731`
- 实施完成：2026-08-24（R1.6 完成，路线图已同步）
- 权威边界：本文定义文档重构的目标、范围、迁移规则和验收条件；实际实施状态只以当前路线图为准

## 1. 如何使用本文

本文不是概念讨论稿，而是一份可以直接交给实现型 AI 执行的任务说明。执行者必须完整阅读
仓库根目录的 `AGENTS.md`、当前路线图和本文，再开始修改文件。

执行者不得仅根据目标目录树机械移动文件。必须同时完成以下工作：

1. 建立清晰的文档信息架构和中文文档入口；
2. 将现有文档按职责迁移到准确位置；
3. 拆分职责混杂的超长文档，合并重复内容；
4. 更新仓库内全部 Markdown 链接、脚本路径和发布检查路径；
5. 建立长期有效的文档写作规范；
6. 同步 `AGENTS.md` 和路线图中的唯一可信路径；
7. 用可重复命令验证文件名、链接、内容边界和工作区范围。

如果实施时发现当前仓库已经新增了本文没有覆盖的文档，应先按本文的“文档类型与职责”完成
分类，再决定目标位置。只有出现无法从现有内容判断权威归属的实质冲突时，才需要暂停并请求
用户决策；不得以“暂时保留两份”作为默认处理方式。

## 2. 背景与当前问题

当前文档内容总体完整，但文件组织已经不适合以开源项目的形式继续扩展。基线仓库中：

- `docs/` 根目录同时存在用户手册、架构说明、ADR、实现设计、调研报告、发布说明、审计报告和候选证据；
- 两份早期设计文档仍散落在仓库根目录；
- 根 `README.md`、`QUICKSTART.md`、`docs/用户手册.md` 和 `docs/功能设计与使用备忘.md`
  重复描述架构、接入、配置和功能；
- `docs/用户手册.md` 同时承担教程、配置参考、API 参考、安全说明、排障和验收入口；
- `docs/功能设计与使用备忘.md` 持续累积“为什么设计”和“如何使用”，已经成为无法稳定扩展的汇总文档；
- 当前文件名混合中文、英文、空格、标点、版本信息和外部导出 ID，不利于稳定链接、脚本处理和未来文档站；
- 使用者需要先理解仓库历史，才能判断哪份文档描述当前行为。

问题的核心不是 Markdown 排版，而是缺少明确的信息架构、文档生命周期和唯一权威来源。
本次重构必须先解决这些结构问题，不通过增加文档网站掩盖内容职责混乱。

## 3. 目标

### 3.1 中文优先的使用体验

PatchBridge Agent 的默认文档语言是中文。文档中心、页面标题、导航、正文、错误说明和任务名称
均以中文使用者为第一读者。类名、接口名、配置项和协议字段保留源码中的准确名称。

中文友好不等同于使用中文文件路径。本次采用“中文展示层 + ASCII 稳定路径”的分层设计：

- 使用者看到中文标题、中文目录、中文链接文字和中文说明；
- Git、脚本、外部链接和未来文档站使用简短的 ASCII `kebab-case` 路径；
- 当前不创建 `zh-cn/` 目录；只有真正开始长期维护第二语言时才引入语言层级。

### 3.2 清晰的文档入口

陌生使用者进入仓库后，应能在不理解项目历史的情况下完成以下判断：

- 这个项目解决什么问题；
- 如何最快运行 Demo；
- 如何接入 Spring Boot 和 Browser；
- 如何使用 Tool、MCP、Widget、Inspector 和 Call Trace；
- 去哪里查完整配置、HTTP 契约和错误码；
- 当前实现到了什么阶段；
- 为什么采用当前架构；
- 哪些文件只是历史证据，不代表当前行为。

### 3.3 唯一权威来源

同一个事实只允许有一个持续维护的权威位置。其他文档只能提供摘要并链接到权威文档，不能复制
另一张可独立演进的配置表、路线图、错误码表或架构规则。

### 3.4 为未来文档站做好准备

目录、文件名和导航关系应能直接被常见文档生成器消费，但本次不选择、不安装也不配置任何
文档站框架。信息架构必须先独立成立，不能依赖某个生成器才能理解。

### 3.5 保留有价值的历史证据

发布审计、候选预检、研究报告和早期设计不能与当前使用文档混在一起，但也不能因为整理目录而
丢失。它们应进入明确的发布、研究或归档目录，并标明生命周期。

## 4. 非目标

本次明确不做：

- 不搭建 VitePress、Docusaurus、MkDocs、GitHub Pages 或其他文档网站；
- 不创建英文翻译或 `zh-cn/en` 双份内容；
- 不修改 Java、TypeScript、HTML、CSS 或运行时行为；
- 不借文档整理改变已经接受的产品和架构决策；
- 不为旧文档路径创建重定向文件、兼容副本或占位页；
- 不把历史文档改写成当前事实；
- 不为了减少行数删除仍有独立信息价值的内容；
- 不引入内容管理系统、数据库、全文搜索或额外构建链；
- 不创建没有真实内容的目录和空白模板文档；
- 不提交、删除或修改与本任务无关的工作区文件和未跟踪产物。

## 5. 总体设计原则

### 5.1 先按读者任务分类，再按代码模块分类

普通使用者首先关心“如何完成接入”，不是 Java 模块或 npm package 的内部结构。因此使用指南
按任务组织；只有 Reference 和 Architecture 才按公共契约或模块边界组织。

### 5.2 教程、指南、参考和解释必须分开

- Quick Start 只负责第一次成功；
- Guide 负责完成一个具体任务；
- Reference 负责完整、精确、可查询的契约；
- Architecture 负责解释系统如何组织；
- ADR 负责记录为什么选择某个不可轻易改变的决策；
- Research 负责保存事实调研，不自动成为实现契约；
- Release 负责固定某个版本的交付和证据；
- Archive 只保存历史上下文。

### 5.3 路径是稳定标识，不承担展示职责

目录和文件名统一使用短 ASCII 路径。中文标题和中文链接文字承担展示职责。禁止为了让路径
“看起来双语”而创建 `使用指南-guides`、`架构设计-architecture` 等混合名称。

### 5.4 当前事实与历史事实分开

当前指南和参考不得要求读者先阅读旧方案或审计报告。历史文件必须在开头明确标记
`Historical` 或具体候选版本，且不得被当前文档引用为行为权威。

### 5.5 删除重复源，不保留双轨

内容迁移完成后，应删除旧的汇总文件。不能保留“新指南 + 旧用户手册”“新架构目录 + 旧架构文件”
两套入口，也不能把旧文件改成仅包含一个新链接的兼容页。当前尚未公开发布，迁移应一次完成。

### 5.6 以内容职责决定拆分，不以行数机械拆分

文档很长不必然错误；一份文档同时服务多个独立任务才需要拆分。出现以下任一情况时应拆分：

- 一个页面包含三个以上可以独立完成的用户任务；
- 同时承担 Guide、Reference 和 Architecture 职责；
- 同一配置/API 表在其他页面重复维护；
- 页面标题无法准确概括全部章节；
- 新功能只能继续追加“第 N 节”，而无法进入稳定分类。

根 `README.md` 建议控制在约 300～500 行，但该数字只是审阅信号，不是为了删内容的硬性门槛。

## 6. 目标目录结构

实施完成后，文档目录应收敛为以下结构。只有确有内容的文件才创建；不得为了完全复制树形示例
建立空文件。

```text
docs/
├── README.md
├── roadmap.md
│
├── guides/
│   ├── spring-boot-integration.md
│   ├── browser-integration.md
│   ├── java-model-invocation.md
│   ├── java-tools.md
│   ├── frontend-tools.md
│   ├── global-mcp.md
│   ├── webmcp.md
│   ├── widget-customization.md
│   ├── tools-inspector.md
│   ├── call-trace.md
│   ├── demo-validation.md
│   └── production-readiness.md
│
├── reference/
│   ├── configuration.md
│   ├── http-api.md
│   ├── browser-api.md
│   ├── runtime-contracts.md
│   └── error-codes.md
│
├── architecture/
│   ├── overview.md
│   ├── designs/
│   │   ├── documentation-system.md
│   │   └── java-model-gateway.md
│   └── adr/
│       ├── README.md
│       ├── 0001-provider-neutral-runtime.md
│       ├── 0002-model-state-lifecycle.md
│       └── 0003-browser-runtime-guards.md
│
├── research/
│   └── reasoning-content.md
│
├── releases/
│   └── v0.1/
│       ├── release-notes.md
│       ├── release-checklist.md
│       ├── audits/
│       │   ├── public-contract-and-security.md
│       │   └── source-quality.md
│       └── evidence/
│           └── rc2-preflight.md
│
├── archive/
│   ├── original-design.md
│   └── frontend-architecture-v0.md
│
└── contributing/
    └── documentation.md
```

仓库根目录继续保留以下开源入口，不移动到 `docs/`：

```text
README.md
QUICKSTART.md
AGENTS.md
LICENSE
THIRD_PARTY_NOTICES.md
```

包级 README 继续放在各自 package 根目录，只描述该包的公共入口、最小用法和指向统一文档的链接：

```text
web/packages/agent/README.md
web/packages/widget/README.md
```

第三方静态资源自带的 README 属于上游许可或资源说明，不进入项目文档迁移范围。

## 7. 每类文档的职责

| 位置 | 主要读者 | 只负责什么 | 不负责什么 | 更新方式 |
| --- | --- | --- | --- | --- |
| 根 `README.md` | 第一次了解项目的人 | 定位、价值、核心边界、最小示例、文档导航、许可证 | 完整配置、端点表、详细架构和路线图 | 随项目定位更新，保持简洁 |
| `QUICKSTART.md` | 第一次运行的人 | 从零构建、启动 Demo、完成最短真实链路 | 完整生产配置和全部功能说明 | 命令或最短链路变化时更新 |
| `docs/README.md` | 所有文档使用者 | 中文文档门户，按用户任务导航 | 复制各文档正文 | 每次新增、移动或删除公共文档时更新 |
| `docs/roadmap.md` | 维护者和采用者 | 唯一实施状态、范围、顺序和验收结果 | 详细使用说明和完整架构解释 | 每次需求状态变化时同步 |
| `docs/guides/` | 集成和使用框架的人 | 完成一个具体任务的步骤、边界、验证与 Demo | 完整配置枚举和架构历史 | 相关公共行为变化时更新 |
| `docs/reference/` | 查字段和契约的人 | 配置、API、错误码、公共类型和默认值 | 教学流程和方案讨论 | 公共契约变化时同步 |
| `docs/architecture/overview.md` | 架构师和贡献者 | 当前模块关系、责任、不变量与扩展边界 | 实时进度和历史审计 | 当前架构变化时更新 |
| `docs/architecture/designs/` | 实现者和审查者 | 一项已接受或待实施设计的完整方案 | 替代路线图状态 | 方案变化时更新，完成后保留设计依据 |
| `docs/architecture/adr/` | 架构维护者 | 已决策问题的背景、决策、代价和替代方案 | 使用教程 | 已接受 ADR 原则上不反复改写，决策变化时新增 ADR 并标记替代关系 |
| `docs/research/` | 决策者和 Provider 实现者 | 调研问题、事实来源、结论和对项目的影响 | 声称未落地能力已经实现 | 新证据改变结论时更新 |
| `docs/releases/` | 发布者和版本使用者 | 固定版本的说明、门禁、审计和证据 | 当前路线图 | 版本冻结后只允许修复链接或事实性笔误，不改写原验收结论 |
| `docs/archive/` | 需要追溯历史的人 | 已失效但值得保留的早期设计 | 当前实现说明 | 不持续维护；必须带历史状态提示 |
| `docs/contributing/` | 贡献者和执行型 AI | 如何编写、放置、检查和维护文档 | 产品使用说明 | 文档治理规则变化时更新 |

## 8. 唯一权威来源矩阵

| 事实 | 唯一权威位置 | 其他文档的处理方式 |
| --- | --- | --- |
| 当前完成度、下一阶段、暂缓范围 | `docs/roadmap.md` | 只写一句摘要并链接 |
| 当前总体架构和模块责任 | `docs/architecture/overview.md` | 不复制完整模块表 |
| 架构决策原因 | Accepted ADR | Overview 只总结结论并链接 |
| 首次运行命令 | `QUICKSTART.md` | README 只保留最短入口 |
| 某项功能的操作步骤 | 对应 `docs/guides/*.md` | README、Reference 不复制完整步骤 |
| 完整配置项和默认值 | `docs/reference/configuration.md` | Guide 只列本任务所需最小配置并链接 |
| HTTP/SSE 契约 | `docs/reference/http-api.md` | Guide 只给必要示例 |
| Browser 公共入口与选项 | `docs/reference/browser-api.md` | 包 README 只给最小示例 |
| Runtime 结果、限额和扩展契约 | `docs/reference/runtime-contracts.md` | ADR 解释原因，Guide 说明用法 |
| 稳定错误码 | `docs/reference/error-codes.md` | 排障页按错误码链接 |
| 安全上线和排障 | `docs/guides/production-readiness.md` | README 不维护第二份安全检查表 |
| 某版本交付内容 | 对应 `docs/releases/<version>/release-notes.md` | 路线图只记录状态和链接 |
| 某候选的验收证据 | 对应 `docs/releases/<version>/evidence/` | 不复制到当前指南 |

代码与测试仍是运行行为的最终事实来源。发现文档与代码冲突时，不能在多个文档分别修补；应先
确认正确契约，再修改唯一权威文档及必要引用，并同步路线图。

## 9. 中文写作与技术术语规范

### 9.1 页面和导航语言

- 一级标题、章节标题、文档门户、链接文字和说明正文默认使用中文；
- 文件路径使用英文不影响中文展示，链接必须写成有意义的中文名称；
- 禁止把裸路径直接作为面向用户的导航文字；
- 当前不创建中英文双份文档，不使用自动翻译内容；
- 将来确实维护英文版本时，再引入 `zh-cn/` 与 `en/`，两个语言版本使用相同 ASCII slug。

### 9.2 技术名称

- 公共类名、接口名、方法名、配置项、HTTP 字段、错误码和协议事件保持源码原名并使用反引号；
- 第一次出现抽象概念时采用“中文解释（技术名称）”或“技术名称（中文解释）”；
- 同一概念在全部当前文档中使用同一个中文名称；
- 不强行翻译已经成为公共契约的名称；
- 不在不同页面交替使用“模型网关”“模型门面”“模型入口”等多个中文别名指代同一类型。

建议固定以下核心术语：

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

### 9.3 中文排版

- 中文与英文单词、数字之间保留一个空格，但代码标识两侧按句子可读性处理；
- 使用中文全角标点，代码、命令、路径和协议字面量除外；
- 每个页面只允许一个一级标题，标题层级不得跳级；
- 普通技术名使用反引号，不使用 HTML `<code>` 代替 Markdown 反引号；
- 代码块必须声明语言，Shell 命令使用 `bash`；
- 占位值使用 `<model-name>`、`<api-key>` 等明确形式，不写入真实密钥；
- 不使用“显而易见”“大家都知道”等排斥新读者的表达；
- 结论先于实现细节，步骤必须可以按顺序执行；
- “为什么这样设计”只保留对正确使用有帮助的部分，完整权衡链接到 Architecture 或 ADR。

### 9.4 示例质量

- 示例只能使用当前源码真实存在的公共 API、配置项和事件；
- 示例应包含必要上下文，禁止只展示无法运行的中间片段却声称是完整示例；
- 会产生业务副作用的 Tool 示例必须说明权限、确认和重复执行边界；
- 同一个完整示例只保留一个权威版本，其他页面链接或展示更小的任务片段；
- 修改示例后应使用源码搜索核对名称，不能凭记忆猜测接口。

## 10. 文件与目录命名规范

### 10.1 基本规则

- `docs/` 下目录和文件统一使用 ASCII；
- 普通文件名使用小写 `kebab-case.md`；
- 目录名使用单数还是复数应按本设计固定，不在同级混用近义目录；
- ADR 使用四位编号：`0001-description.md`；
- 版本目录使用 `v0.1` 这种对使用者可识别的版本名；
- 约定俗成的 `README.md` 保持大写；
- 禁止空格、中文路径、括号、外部系统导出 ID 和日期前缀；
- 日期属于文档内容或 Git 历史，不进入普通指南文件名；候选证据需要版本身份时写入明确 slug。

### 10.2 标题规则

- 文件名短，一级标题完整；
- Guide 标题应表示用户任务，例如“接入 Spring Boot 2 Starter”；
- Reference 标题应表示被查询的契约，例如“Starter 配置参考”；
- ADR 标题应表示已作出的决策，而不是模糊主题；
- Research 标题应表示调研问题和范围；
- Release 文件标题必须包含准确版本或候选身份。

## 11. 文档模板

### 11.1 Guide

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

不是所有 Guide 都必须机械保留空章节；没有内容的章节应删除。

### 11.2 Reference

```markdown
# 被查询契约的中文标题

## 适用范围

## 完整契约

## 默认值与约束

## 错误语义

## 示例

## 兼容与版本边界
```

Reference 中的表格必须完整，Guide 中只摘录完成当前任务所必需的最小字段。

### 11.3 Architecture Design

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

### 11.4 ADR

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

Accepted ADR 的核心决策发生变化时，应新增 ADR 并把旧 ADR 标记为 `Superseded`，不能直接把
旧决策改写成从未存在过。

### 11.5 Research

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

### 11.6 Release Evidence

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

## 12. 现有文件迁移映射

### 12.1 可以直接迁移的文档

| 当前路径 | 目标路径 | 处理要求 |
| --- | --- | --- |
| `docs/路线图与当前进度.md` | `docs/roadmap.md` | 保留全部状态与更新记录；同步修改 `AGENTS.md` 的强制路径和全部链接 |
| `docs/架构设计-模块化单体、六边形架构与设计模式.md` | `docs/architecture/overview.md` | 保留当前架构事实，删除被 Guide/Reference 接管的使用细节 |
| `docs/ADR-001-厂商中立-Agent-Runtime-核心契约.md` | `docs/architecture/adr/0001-provider-neutral-runtime.md` | 增加标准 ADR 元数据，保持已接受决策不变 |
| `docs/ADR-002-ModelState-生命周期与会话迁移.md` | `docs/architecture/adr/0002-model-state-lifecycle.md` | 增加标准 ADR 元数据，保留其当前实施状态与路线图关系 |
| `docs/ADR-003-Browser-Agent-Runtime-生产级执行守卫.md` | `docs/architecture/adr/0003-browser-runtime-guards.md` | 增加标准 ADR 元数据，保持 R1.5 已实施结论 |
| `docs/设计-Java 后端单次模型调用 API.md` | `docs/architecture/designs/java-model-gateway.md` | 标明已实施，不再重复完整使用指南 |
| `docs/思考模型 reasoning_content 回传规则调研报告.md` | `docs/research/reasoning-content.md` | 增加 Research 元数据，保留来源与实施结论 |
| `docs/v0.1-版本说明.md` | `docs/releases/v0.1/release-notes.md` | 保留准确版本范围和已知限制 |
| `docs/v0.1-发布检查清单.md` | `docs/releases/v0.1/release-checklist.md` | 更新归档路径和文档 Gate 中的目标路径，不改变门禁语义 |
| `docs/v0.1-公开契约与安全默认值审计.md` | `docs/releases/v0.1/audits/public-contract-and-security.md` | 保留审计发现与闭环事实，修复链接 |
| `docs/v0.1-源码发布收口代码质量审计.md` | `docs/releases/v0.1/audits/source-quality.md` | 保留 Q-01～Q-10 和历史结论，修复链接 |
| `docs/v0.1-源码发布候选预检.md` | `docs/releases/v0.1/evidence/rc2-preflight.md` | 保留候选 Commit、环境和 Gate 结果，不改写历史数字 |
| `面向企业 Web 应用的纯前端 Agent 架构实现方案 3c0bde7d586d81ea9a27f1d0bb547e1f.md` | `docs/archive/original-design.md` | 增加醒目的 Historical 提示，不继续维护为当前方案 |
| `前端架构设计：AgentController + 单一状态源（v0 1）.md` | `docs/archive/frontend-architecture-v0.md` | 增加醒目的 Historical 提示，链接当前架构说明 |

本文已经位于最终目标路径 `docs/architecture/designs/documentation-system.md`，实施时更新状态为
“已实施”，并补充最终迁移日期和验收摘要，不再移动。

### 12.2 `docs/用户手册.md` 的章节迁移

旧用户手册不保留为永久汇总文档。其章节按下表迁移并与功能备忘的对应章节合并：

| 旧章节 | 目标位置 | 处理方式 |
| --- | --- | --- |
| §1 版本状态与适用边界 | `docs/README.md`、`docs/releases/v0.1/release-notes.md`、`docs/roadmap.md` | 文档门户只保留当前摘要；完整版本边界归 Release；状态归 Roadmap |
| §2 架构与责任矩阵 | `docs/architecture/overview.md` | 保留一份完整责任矩阵，其他页面只链接 |
| §3 环境与源码构建 | `QUICKSTART.md` | 合并重复命令，只保留从零成功所需路径 |
| §4 Starter 最小接入 | `docs/guides/spring-boot-integration.md` | 形成可执行 Guide |
| §5 完整配置参考 | `docs/reference/configuration.md` | 保留完整配置表、默认值和模式示例 |
| §6 身份、权限与会话归属 | `docs/guides/production-readiness.md` | 以安全上线任务组织，架构责任链接 Overview |
| §7 Java Tool 开发 | `docs/guides/java-tools.md` | 保留注解、Schema、权限和调用边界 |
| §8 HTTP、SSE 与错误契约 | `docs/reference/http-api.md`、`docs/reference/error-codes.md` | 端点与事件归 HTTP Reference，错误码单独归档 |
| §9 Browser Headless 接入 | `docs/guides/browser-integration.md`、`docs/reference/browser-api.md` | 操作步骤与完整公共选项分开 |
| §10 默认 Widget | `docs/guides/widget-customization.md`、`docs/reference/browser-api.md` | 使用与样式归 Guide，元素/属性/事件归 Reference |
| §11 Browser Local Tool、WebMCP、Inspector、Call Trace | 对应四份独立 Guide | 不再堆在同一总章 |
| §12 Global MCP 与 Admin | `docs/guides/global-mcp.md`、`docs/reference/configuration.md` | 使用流程与配置字段分开 |
| §13 Demo 使用与验收 | `docs/guides/demo-validation.md` | 保留入口和逐项验证路径 |
| §14 已知限制、安全上线、排障与验证 | `docs/guides/production-readiness.md`、Release Notes | 当前操作归生产准备，版本限制归 Release |
| §15 Java 后端单次模型调用 | `docs/guides/java-model-invocation.md` | 合并设计文档中的最小必要边界，保留完整示例 |

迁移完成并确认所有独立信息已有目标后，删除 `docs/用户手册.md`。不得创建同名占位页。

### 12.3 `docs/功能设计与使用备忘.md` 的章节迁移

“功能备忘”不是长期稳定的文档类型。其内容应作为现有 Guide 和 Architecture 的合并来源：

| 旧章节 | 目标位置 |
| --- | --- |
| §1 能力总览 | `docs/README.md`，只保留导航摘要 |
| §2 Unified Tool Registry | `docs/architecture/overview.md` 与 `docs/reference/runtime-contracts.md` |
| §3 纯前端声明 Tool | `docs/guides/frontend-tools.md` |
| §4 WebMCP Adapter | `docs/guides/webmcp.md` |
| §5 Global MCP | `docs/guides/global-mcp.md` |
| §6 Tools Inspector | `docs/guides/tools-inspector.md` |
| §7 Widget 样式 | `docs/guides/widget-customization.md` |
| §8 厂商中立 Agent Runtime | `docs/reference/runtime-contracts.md`、Architecture Overview 和 ADR 链接 |
| §9 Call Trace | `docs/guides/call-trace.md` |
| §10 Demo 验收 | `docs/guides/demo-validation.md` |
| §11 Java 后端单次模型调用 | `docs/guides/java-model-invocation.md` |
| §12 协议参考 | 对应 Reference 或 ADR 的链接区，不另建重复正文 |

每个目标 Guide 应把“为什么这样设计”收敛成帮助正确使用的简短“设计边界”，详细权衡归
Architecture 或 ADR。迁移完成后删除 `docs/功能设计与使用备忘.md`，不保留兼容页。

### 12.4 根 README 与 Quick Start

根 `README.md` 保留：

- 项目定位和两个最高目标；
- 一张最小架构图或责任概览；
- 已实现核心能力摘要；
- 一个真正最小的接入或启动示例；
- 指向 `QUICKSTART.md` 和 `docs/README.md` 的清晰入口；
- 当前版本状态的一句话摘要和 `docs/roadmap.md` 链接；
- 适用/不适用场景、安全原则、Contributing 和 License 的短说明。

根 `README.md` 删除或迁出：

- 完整配置表；
- 完整 HTTP/SSE 契约；
- Tool、MCP、Conversation、Audit、Browser UI 的长篇实现细节；
- 可独立演进的 Roadmap；
- 与 Architecture、Guide 或 Reference 重复的大段正文。

`QUICKSTART.md` 只保留第一次成功所需的环境、构建、Demo 启动和最小宿主接入。完整 Runtime、
Call Trace、Admin 和生产配置应使用短链接指向对应 Guide/Reference，不在 Quick Start 展开。

## 13. 新建文档的内容要求

### 13.1 `docs/README.md`

这是 GitHub 浏览 `docs/` 时的中文文档门户，至少包含：

1. 一句话说明文档默认使用中文；
2. “第一次使用”入口；
3. “按任务查找”入口；
4. Tool 与 MCP 入口；
5. Widget、Inspector 与 Call Trace 入口；
6. Reference 入口；
7. Architecture、ADR 与 Research 入口；
8. Roadmap 与 Release 入口；
9. Historical 文档的明确隔离说明；
10. 文档贡献规范入口。

导航文字必须使用中文，不使用文件路径作为标题。

### 13.2 `docs/architecture/adr/README.md`

至少说明：

- ADR 是什么；
- `Proposed`、`Accepted`、`Superseded` 的含义；
- 何时应新增 ADR；
- 为什么不直接改写已接受决策；
- 当前 ADR 的中文索引和状态。

### 13.3 `docs/contributing/documentation.md`

本文第 5～11 节是长期规范的设计来源。实施时应提炼成维护者真正需要的简明规则，不要把迁移
映射和一次性实施步骤复制进长期规范。至少包含：

- 文档分类与目标目录；
- 中文优先与 ASCII 路径规则；
- 唯一权威来源；
- Guide、Reference、Architecture、ADR、Research、Release 的最小模板；
- 新功能必须同步哪些文档；
- 链接和文件名检查命令；
- 文档审查清单。

## 14. 实施步骤

### 阶段 A：建立基线清单

1. 确认工作区状态，记录并保护与本任务无关的修改和未跟踪文件；
2. 使用 `rg --files` 列出全部 tracked Markdown；
3. 搜索全部文档互链、脚本路径、CI 路径和归档检查路径；
4. 对照本文迁移表检查是否存在基线后新增文档；
5. 不修改产品源码。

### 阶段 B：建立目标骨架

1. 创建实际需要的目标目录；
2. 创建 `docs/README.md`、ADR 索引和长期文档规范；
3. 先不创建没有内容的占位页面；
4. 保持本文处于 `docs/architecture/designs/documentation-system.md`。

### 阶段 C：迁移单一职责文档

1. 按 §12.1 使用 Git 可识别的移动操作迁移架构、ADR、设计、研究、Release 和 Archive；
2. 为 ADR、Research、Design 和 Archive 增加必要状态元数据；
3. 只修复路径和明确的元数据，不改写历史证据；
4. 更新相对链接。

### 阶段 D：拆分混合文档并消除重复

1. 按 §12.2 和 §12.3 建立 Guide/Reference；
2. 同时阅读用户手册和功能备忘的对应章节，合并独立信息；
3. 每个事实选定唯一权威位置；
4. 删除目标文档中与其他权威页面重复的长段落，改为摘要链接；
5. 确认没有信息丢失后，删除旧用户手册和功能备忘；
6. 精简根 README 和 Quick Start。

### 阶段 E：同步治理入口

1. 将路线图移动为 `docs/roadmap.md`；
2. 修改 `AGENTS.md`，把强制 SSOT 路径更新为 `docs/roadmap.md`；
3. 在 `AGENTS.md` 增加文档规范入口 `docs/contributing/documentation.md`；
4. 在路线图中更新 R1.6 状态、范围、验收结果、最近更新时间和更新记录；
5. 更新根 README、Quick Start、包 README、Release 检查清单和全部文档链接；
6. 不在 README 建立第二份路线图。

### 阶段 F：验证和审查

1. 验证全部 tracked Markdown 链接；
2. 验证 `docs/` 下不存在中文、空格或外部导出 ID 路径；
3. 验证不存在指向旧路径的有效 Markdown 链接；
4. 验证根 README、Quick Start、Docs Index、Guide、Reference、Architecture 和 Roadmap 的职责没有重新重叠；
5. 验证 Release 历史数字、候选 Commit 和审计结论没有被改写；
6. 验证工作区中没有纳入无关文件；
7. 只有全部条件满足后，才能把路线图 R1.6 标记为完成。

## 15. 必须更新的引用范围

执行者不能只搜索 `docs/`。至少检查：

- 根 `README.md`；
- 根 `QUICKSTART.md`；
- 根 `AGENTS.md`；
- `web/packages/*/README.md`；
- `.github/workflows/`；
- 发布归档或检查脚本；
- `docs/releases/v0.1/release-checklist.md` 内写死的归档条目；
- 全部 tracked Markdown；
- 可能引用文档路径的 Shell、JavaScript、YAML 和 XML 文件。

应使用 `rg` 搜索旧文件名和 Markdown 链接，不能只依靠编辑器的自动重命名。

## 16. 验收条件

### 16.1 结构验收

- `docs/` 根目录只保留 `README.md`、`roadmap.md` 和已定义分类目录；
- `docs/` 下 tracked 路径全部为 ASCII，普通文件使用小写 `kebab-case.md`；
- 两份早期根目录设计已经进入 `docs/archive/`；
- ADR、Research、Release、Archive 与当前 Guide/Reference 物理隔离；
- 不存在空目录、空模板或无内容的导航占位页；
- 不存在旧路径兼容文件或同内容双份文档。

### 16.2 内容验收

- `docs/README.md` 提供完整、中文、按任务组织的入口；
- 根 README 不再承担用户手册和完整架构说明；
- Quick Start 可以独立完成第一次成功，但不展开完整 Reference；
- 每个面向用户的当前能力都有明确 Guide 或准确的 Reference 入口；
- 每个 Guide 说明目标、步骤、验证方式、安全边界和相关 Demo/底层验证；
- 配置、HTTP、Browser API、Runtime 契约和错误码各只有一份权威 Reference；
- 当前架构与历史设计不混用；
- 调研报告明确区分事实调研、实施结论和未落地能力；
- 发布审计和候选证据的原始事实保持不变；
- `docs/用户手册.md` 和 `docs/功能设计与使用备忘.md` 已在内容迁移后删除；
- 本文状态已更新为“已实施”，并记录最终验收摘要。

### 16.3 治理验收

- `AGENTS.md` 的路线图路径准确指向 `docs/roadmap.md`；
- `AGENTS.md` 明确要求遵守 `docs/contributing/documentation.md`；
- `docs/roadmap.md` 仍是唯一进度可信来源；
- 路线图包含 R1.6 的最终范围、验收结果和更新记录；
- 长期文档规范不包含一次性迁移细节；
- 新功能文档同步规则能明确回答“这次应该改哪几份文档”。

### 16.4 自动检查

至少执行以下命令，并记录结果：

```bash
git diff --check

git ls-files -z '*.md' \
  | xargs -0 lychee --offline --include-fragments --no-progress

git ls-files docs \
  | rg '[^\x00-\x7F]| '

git grep -nE '\]\([^)]*(路线图与当前进度|用户手册|功能设计与使用备忘|ADR-00[1-3])' \
  -- '*.md'
```

后两条命令的预期结果为零匹配，因此 `rg`/`git grep` 返回 1 属于成功的“未发现”，执行脚本时
应明确处理该退出码，不得把它误报为检查失败。

还应对全部 tracked 文件搜索旧路径。允许本文在迁移映射表的代码字面量中记录旧路径，但不允许
任何有效链接、脚本参数或配置继续依赖旧路径。

如果实施只修改 Markdown、文档路径和相应链接，不需要为了“测试而测试”重复执行全部 Java/Web
单元测试；但必须核对 README/Quick Start 中出现的 Maven、npm 和模块名称与当前工程一致。
如果修改了构建脚本、归档脚本、CI 或产品源码，则必须执行与该修改范围对应的真实验证。

## 17. 提交前人工审查清单

- [ ] 一个第一次访问仓库的人能在两次点击内找到 Quick Start；
- [ ] Spring Boot、Browser、Tool、MCP、Widget、Inspector、Call Trace 和 Java 模型调用都有明确入口；
- [ ] 使用者不需要阅读 ADR、审计或 Archive 才能完成接入；
- [ ] 根 README 没有完整复制配置表、端点表或路线图；
- [ ] Guide 与 Reference 的内容职责没有混合；
- [ ] 当前架构只由 Overview 和 Accepted ADR 解释；
- [ ] Roadmap 仍是唯一实施状态来源；
- [ ] 历史文件开头能立即看出它不代表当前行为；
- [ ] 所有页面标题和导航文字对中文读者友好；
- [ ] 所有路径适合 Git、Shell、外部链接和未来文档站；
- [ ] 所有代码和配置示例都能在当前源码中找到对应契约；
- [ ] 文档链接检查和路径检查通过；
- [ ] 工作区中的无关修改和未跟踪文件没有被纳入。

## 18. 完成定义

本任务不是“创建几个目录”或“把文件名改成英文”。只有同时满足以下结果才算完成：

1. 信息架构已经真实落地；
2. 中文用户拥有清晰入口和统一术语；
3. 路径已经标准化；
4. 混合文档已经按职责拆分；
5. 重复事实已经合并为唯一权威来源；
6. 历史、研究、发布证据和当前使用文档已经隔离；
7. `AGENTS.md`、路线图和全部仓库链接已经同步；
8. 自动检查和人工审查清单全部通过；
9. 没有通过兼容文件、重复副本或静默遗漏掩盖未完成迁移。

本次实施已满足上述全部条件，路线图中的 R1.6 已标记为完成；后续文档变更仍必须遵守本文定义的分类、唯一权威来源和检查规则。

## 19. 实施结果（R1.6 完成，2026-08-24）

- `docs/README.md` 中文文档门户已建立；
- `docs/guides/` 下已落地 13 份任务导向 Guide；
- `docs/reference/` 下已落地 5 份精确契约 Reference；
- 架构说明迁移为 `docs/architecture/overview.md`，ADR 迁移为 `0001/0002/0003`，Java 模型调用设计迁移为 `docs/architecture/designs/java-model-gateway.md`；
- 调研报告迁移为 `docs/research/reasoning-content.md`；
- v0.1 Release、审计与候选证据迁移至 `docs/releases/v0.1/`；
- 早期设计迁移至 `docs/archive/`，并保留 Historical 提示；
- 长期文档规范落地为 `docs/contributing/documentation.md`；
- `docs/用户手册.md` 与 `docs/功能设计与使用备忘.md` 已完成章节拆分并删除；
- 路线图迁移为 `docs/roadmap.md`，`AGENTS.md`、README、QUICKSTART、包 README 与发布检查清单引用已同步；
- 全量本地 Markdown 链接检查、`docs/` ASCII 路径检查和旧路径扫描通过。

实施原则：所有原文档中的事实、边界、示例与验证路径均保留在目标文件或对应 Reference/Archive 中；重构只改变组织方式，不改变产品行为。
