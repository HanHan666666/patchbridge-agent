# v0.1 源码发布候选预检记录（rc.2）

- 状态：**预检完成（FINAL-01 满足，含一项集成人明示接受的记录偏差）**
- 预检日期：2026-08-23
- 集成人：R0 集成收口执行人（本会话）+ 宿主（凭据授权与验收决策）
- 执行依据：[《v0.1 发布检查清单》](../release-checklist.md)（候选内版本）
- 前序记录：rc.1 候选 `09e598d` 的预检记录见本文件 Git 历史（2026-08-22 提交）；二次代码质量审计后候选更换，按 FINAL-03 从头重跑，不继承旧 PASS

## 1. 候选绑定

| 项 | 值 |
| --- | --- |
| 代码与文档候选 commit | `f4c7be492adcbc044474d1aee81750d205a1084f`（tag `v0.1.0-rc.2` 所在提交） |
| 候选 tree | `63eb6ac01dfa03d9949e8f68eac1dcf1cdc6fef0` |
| 预检证据归档 tar（自候选生成） | SHA-256 `119635fa84fb61f48e84ef7c3cc01fd9124c33ef1d2ae08a63d0561d6cf73012` |
| 本记录提交 | 仅追加本文件与路线图更新；被检代码/构建产物与上表候选逐字节一致（`git diff --stat` 仅有 docs 变更可复核） |

候选历史要点：`e2dc4f8`（二次审计采纳）→ Q-02～Q-10 修复链 `a0f2d5e/2fda35a/7f0d327/0a5c2d3/87dc103/8ecf8b0/031c3e4/df1f177/964b04d` + Bundle 重建 `3504a9f` + 审计闭环 `3742cf2` → `f4c7be4`（并入宿主 ADR-002 采纳 `404228a`）。

## 2. 预检环境与工具链

| 项 | 值 |
| --- | --- |
| 操作系统 | macOS（本机，arm64） |
| JDK | Corretto `1.8.0_462`（真实 Java 8，`JAVA_HOME` 显式指定） |
| Maven | 3.9.9，隔离仓库 `-Dmaven.repo.local=/tmp/patchbridge-agent-r0.preflight/maven-repo`（52MB 全新下载，不依赖本机缓存） |
| Node / npm | `v24.19.0` / `11.17.0`（满足“源码构建至少 22.12”要求；rc.1 曾用 22.12.0 锁定复验） |
| gitleaks | 8.30.1（brew 安装，git + dir 双模式） |
| lychee | 0.24.2（仅离线模式） |
| 干净源 | 全部 gate 在 `git clone --no-hardlinks` 新目录执行；归档复建在 tar 解包目录（无 `.git`）执行 |

## 3. Gate 结果

### GIT / JAVA / WEB / BUNDLE

| Gate | 结果 | 关键证据 |
| --- | --- | --- |
| GIT-01 固定候选 | PASS | commit/tree 见上表；克隆 checkout 后 `rev-parse` 比对一致 |
| GIT-02 干净 checkout | PASS | porcelain 为空、无冲突 |
| GIT-03 隔离 | PASS | 366 个 tracked 文件；pack.sh / application-local / node_modules / target / dist / jar / class 零命中 |
| JAVA-01 工具版本 | PASS | `java -version` = `1.8.0_462`（JAVA_HOME 显式指定后重跑） |
| JAVA-02 全 Reactor | PASS | 9/9 模块 SUCCESS（含新内核 patchbridge-agent-model-openai）；173 tests，0 failures，0 errors（37 份 surefire 报告） |
| JAVA-03 禁止 publish | PASS | 仅执行 `clean test` 与隔离仓库 `install` |
| WEB-01 版本/workspace | PASS | npm 11.17.0；5 个 workspace 精确匹配 |
| WEB-02 锁文件安装测试 | PASS | `npm ci`；Web 144 tests 全过（agent 97 / webmcp 6 / widget 20 / inspector 2 / call-trace 19） |
| WEB-03 五包构建 | PASS | 五个 workspace 全部构建成功 |
| WEB-04 禁止 publish | PASS | 未执行任何 npm registry 写操作 |
| BUNDLE-01 tracked | PASS | 完整 Web 构建后四个 tracked 资源零差异（`3504a9f` 重建后基线） |
| BUNDLE-02 逐字节一致 | PASS | 四组 `cmp` 为 0；SHA-256：agent `7676c8f9…` / webmcp `93b2269f…` / inspector `99faf757…` / call-trace `c4690b71…` |

### JAR / ARCHIVE / LICENSE / META

| Gate | 结果 | 关键证据 |
| --- | --- | --- |
| JAR-01 隔离 install | PASS | Web 构建后隔离仓库 install BUILD SUCCESS |
| JAR-02 内容 | PASS | 四个 bundle 齐全；application-local/pack.sh/test 类禁止项零命中；内核 OpenAiChatProtocol 位于独立 jar，Starter 经 Maven 依赖引用 |
| JAR-03 source-only 记录 | N/A | 同 rc.1：metadata=PRESENT；JAR 内根 LICENSE=ABSENT（R0 source-only） |
| ARCHIVE-01 从 commit 生成 | PASS | tar 仅来自候选 commit；SHA-256 见绑定表 |
| ARCHIVE-02 内容边界 | PASS | target/node_modules/dist/.env*/application-local*/pack.sh/demo-db* 零命中 |
| ARCHIVE-03 从源码包复建 | PASS | 归档目录（无 .git）内：Web `npm ci`→五包构建；Java 隔离仓库 package，Demo JAR 产出 |
| LICENSE-01 根 LICENSE | PASS | MIT License 存在于候选与归档 |
| LICENSE-02 vendored 资产 | PASS | 同 rc.1 基线（THIRD_PARTY_NOTICES + 资产邻接许可证），本轮 diff 未触及 |
| LICENSE-03 source-only 记录 | N/A | 同 rc.1 |
| META-01 坐标版本 | PASS | `io.patchbridge.agent` / `0.1.0-SNAPSHOT`；npm workspace 0.1.0 |
| META-02 公共包元数据 | N/A | source-only；未准备也未宣称 |
| META-03 发布禁令 | PASS | 未执行任何 publish/deploy/签名/Token 操作 |

### SCAN / DOC / ARCH

| Gate | 结果 | 关键证据 |
| --- | --- | --- |
| SCAN-01 旧品牌 | PASS | 内容与路径零命中（例外仅 `.gitleaksignore` 历史指纹，同 rc.1） |
| SCAN-02 敏感路径 | PASS | 无敏感文件名 |
| SCAN-03 专用秘密扫描 | PASS | gitleaks git（全历史 44 commits，3.68MB）零 finding；gitleaks dir（源码归档 2.6MB）零 finding |
| SCAN-04 Mock/内部依赖 | PASS | 生产源码与 Demo 无 Mock AI/MCP |
| 秘密卫生（附加） | PASS | 模型 api-key 在 demo 日志/证据目录/归档源码中零命中（grep 按值扫描） |
| DOC-01 本地链接 | PASS | lychee 离线全量 tracked Markdown：60 unique 链接 0 错误 |
| DOC-02 权威文档一致性 | PASS | 架构表/手册/README 已含 model-openai 内核行（Q-10 同步）；路线图与候选状态一致 |
| DOC-03 已知冲突清零 | PASS | C-ARCH-01（POM 依赖方向，含新内核）逐项复核；其余同 rc.1 基线且本轮 diff 未触及 |
| DOC-04 从零命令 | PASS | QUICKSTART 序列（npm ci→test→build；mvn test/install）与隔离环境实际执行序列一致 |
| ARCH-01 模块方向 | PASS | 8 模块 POM 直接依赖与架构表逐一核对：内核=Core+Jackson 无传输/Spring；webflux=Core+内核+WebFlux；Starter=Composition Root；无反向/环 |
| ARCH-02 Browser 方向 | PASS | agent 包对其他四包 import 零命中（唯一命中为 JSDoc 注释文字） |
| ARCH-03 Provider 协议隔离 | PASS | Q-10 后协议唯一实现在内核模块，OkHttp/WebFlux Adapter 无第二套实现（净删约 500 行） |
| ARCH-04 后端无跨请求状态 | PASS | 同 rc.1 基线；Q-09 锁为请求内串行化，不引入跨请求状态 |
| ARCH-05 原子上下文和取消 | PASS | Q-09 新增管线/会话串行化并发测试（cancellationTest/StreamSessionTest）随 JAVA-02 通过 |

### SECURITY / DEMO / FINAL

| Gate | 结果 | 关键证据 |
| --- | --- | --- |
| SECURITY-01 默认值快照 | PASS | Q-02 后 call-trace 默认 `off`、非法值抛错（widget element 源码核对）；Demo 显式 `persistent`；其余默认值同 rc.1 基线 |
| SECURITY-02 身份权限 owner | PASS | e2e 复验：匿名 401、user 越权 403、跨 owner 隔离用例通过 |
| SECURITY-03 MCP 凭据出站 | PASS | 同 rc.1 基线（AES-256-GCM + key 校验用例）随 JAVA-02 通过；本轮 diff 未触及加密路径 |
| SECURITY-04 错误审计脱敏 | PASS | 模型错误以结构化 MODEL_FAILED 信封返回，上游正文不入日志；api-key 零泄漏（秘密卫生附加扫描） |
| SECURITY-05 唯一契约无 fallback | PASS | call-trace 非法值直接抛错（无静默回落，本轮源码核对）；其余同 rc.1 基线 |
| DEMO-01 隔离启动 | PASS | 归档复建 Demo JAR；环境变量注入（变量名记录：PATCHBRIDGE_AGENT_MODEL_BASE_URL/MODEL/API_KEY、PATCHBRIDGE_AGENT_MCP_ENCRYPTION_KEY 一次性随机、SPRING_DATASOURCE_URL 内存 H2、SERVER_PORT）；不读取 application-local |
| DEMO-02 HTTP/认证/资源 | PASS | /login 200；匿名 /ai 401；四个 /ai/assets/patchbridge-agent*.js 200；旧资源 404；e2e 深链：RBAC 过滤、MCP JDBC 配置边界、工具调用与 403、会话 CRUD+乐观锁 409、Admin 审计与 403 |
| DEMO-03 真实链路 | PASS（含记录偏差） | 宿主批准真实网关配置经环境变量注入（值不入证据；验后由宿主择时吊销）：e2e-api 17/17（含真实结构化 SSE）；e2e-real-llm 10/10（真实流式、自主调用 device_list×2、流式中止恢复、多模态识图“红色”、图片持久化往返）。**偏差**：Global MCP 真实 endpoint CRUD/测试/刷新链路本轮未执行（未提供真实 Server），集成人明示接受以 JDBC 配置边界测试 + 20 项 MCP 单元/集成测试（覆盖 Q-04 严格解析/Q-06 版本）替代；人工浏览器目视项以 e2e-real-llm 的 Widget 级断言（输入框恢复/消息气泡/图片持久化）代替 |
| FINAL-01 汇总条件 | PASS | 全部 gate PASS 或 N/A（source-only 后续项，理由如上）；DEMO-03 的 MCP endpoint 偏差由集成人明示接受并记录 |
| FINAL-02 Git 复核 | PASS | 测试后 tracked tree 干净、commit 未漂移 |

## 4. 冻结结论

1. 预检完成：GIT/JAVA/WEB/BUNDLE/JAR/ARCHIVE/LICENSE/META/SCAN/DOC/ARCH/SECURITY/DEMO 全部 PASS（source-only 后续项 N/A 已附理由）；FINAL-01 满足。
2. 候选 `f4c7be4` 已打 annotated tag `v0.1.0-rc.2`；`v0.1.0-rc.1`→`a6efdc2` 保持不动。本记录提交为候选之后的纯文档增量。
3. DEMO-03 记录偏差：Global MCP 真实 endpoint 链路未执行，集成人明示接受替代覆盖；后续如需补齐，在任意含真实 Server 的环境重跑 e2e 管理页配置链路即可，不影响候选代码。
4. rc.1 曾做的 deepin 容器异地复验本轮未重复（可选项，不在检查清单 gate 内）。
5. R0 全程未执行 Maven Central / npm public publish、签名或 Token 操作；模型凭据仅经环境变量注入预检进程，证据文件按值扫描零命中，宿主将在验收后吊销该密钥。