# v0.1 发布检查清单（R0 source-only）

- 文档状态：可执行检查清单，不是候选预检结果
- 适用范围：v0.1 源码发布候选，以及从源码构建出的验证性 Starter JAR 和 Demo JAR
- 发布边界：只冻结源码候选，不发布 Maven Central 或 npm 公共包
- 结果归属：最终预检记录必须绑定修复后的不可变候选 commit，由集成人执行并填写
- 当前候选：`v0.1.0-rc.2`（包含二次审计 Q-02～Q-10 整改闭环与最终路线图；`v0.1.0-rc.1` 为整改前历史候选）

本清单把[《路线图与当前进度》](../../roadmap.md)中的 R0 收口要求转换为逐项门禁。
架构行为以[《架构设计：模块化单体、六边形架构与设计模式》](../../architecture/overview.md)
和[《ADR-001：厂商中立 Agent Runtime 核心契约》](../../architecture/adr/0001-provider-neutral-runtime.md)
为准，当前功能范围同时核对[《文档中心》](../../README.md)。

本文不创建或代替最终预检记录。集成人只能在冲突修复、候选 commit 冻结后另行记录实际命令、结果与证据；
不得把本清单中的示例值、基线 commit 或历史测试数复制成候选事实。

## 1. 判定和证据规则

| 结果 | 含义 | 对冻结的影响 |
| --- | --- | --- |
| `PASS` | 同一候选 commit 上完成检查，命令成功且实际结果满足验收条件 | 可以继续 |
| `FAIL` | 命令失败、结果不符合契约、发现未解决冲突或禁止项 | 立即阻断；修复后从新 commit 重跑 |
| `BLOCKED` | 缺少 Java 8、Node、扫描器、真实服务或必要权限，检查没有完成 | 不是通过；阻断冻结 |
| `N/A` | 明确超出 R0 source-only 范围，并记录理由和批准人 | 只允许用于本清单明确标注的后续项 |

测试与构建、四 Bundle、源码归档、架构不变量、安全默认值、秘密扫描、文档本地链接、真实 Demo
和 Git 隔离不得标记 `N/A`。

最终记录每项至少包含：稳定 ID、完整 commit/tree/tag、UTC 时间、执行人、OS/arch、工具版本、实际命令、
exit code、测试数或文件 SHA-256、脱敏证据路径、结果、失败原因或 `N/A` 理由。禁止把 API Key、Token、密码、
MCP 凭据、Cookie、Authorization、URL 查询参数或匹配到的高熵值写入证据；不得启用 `set -x`。

## 2. 隔离环境

`CANDIDATE` 必须由执行人填写完整 40 位 commit，不得默认为 `HEAD`：

~~~bash
set -eu
umask 077

export SOURCE_REPO=/path/to/patchbridge-agent-repository
export CANDIDATE='<修复后的完整 40 位 commit>'
export PREFLIGHT_ROOT="$(mktemp -d /tmp/patchbridge-agent-r0.XXXXXX)"
export CHECKOUT="$PREFLIGHT_ROOT/repository"
export EVIDENCE="$PREFLIGHT_ROOT/evidence"
export ARCHIVE="$PREFLIGHT_ROOT/patchbridge-agent-source.tar"
export ARCHIVE_ROOT="$PREFLIGHT_ROOT/archive"
mkdir -p "$EVIDENCE" "$ARCHIVE_ROOT"

test "$(printf '%s' "$CANDIDATE" | wc -c | tr -d ' ')" -eq 40
git -C "$SOURCE_REPO" cat-file -e "$CANDIDATE^{commit}"
git clone --no-hardlinks --no-checkout "$SOURCE_REPO" "$CHECKOUT"
git -C "$CHECKOUT" checkout --detach "$CANDIDATE"
~~~

只能在 `CHECKOUT`、`ARCHIVE_ROOT`、临时 Maven 仓库和 `EVIDENCE` 中产生文件。不得在主工作树运行
Maven/npm 构建，不得读取主工作树或本机 ignored `application-local.*`，不得读取或执行 `pack.sh`。

## 3. GIT：候选身份与隔离

### GIT-01 固定候选

~~~bash
git -C "$CHECKOUT" rev-parse HEAD | tee "$EVIDENCE/commit.txt"
git -C "$CHECKOUT" rev-parse HEAD^{tree} | tee "$EVIDENCE/tree.txt"
test "$(git -C "$CHECKOUT" rev-parse HEAD)" = "$CANDIDATE"
~~~

- `PASS`：commit 与显式 `CANDIDATE` 一致并记录 tree hash。
- `FAIL`：使用短 hash、分支名、浮动 `HEAD` 或实际 commit 不一致。

### GIT-02 干净 checkout

~~~bash
git -C "$CHECKOUT" status --short --branch | tee "$EVIDENCE/git-status-before.txt"
test -z "$(git -C "$CHECKOUT" status --porcelain=v1)"
git -C "$CHECKOUT" diff --check
git -C "$CHECKOUT" diff --cached --check
test -z "$(git -C "$CHECKOUT" ls-files -u)"
~~~

- `PASS`：无 staged、unstaged、untracked 或冲突项。
- `FAIL`：候选依赖主工作树 index 或需要先清理用户文件。

### GIT-03 pack.sh、本地配置和产物隔离

本项只检查候选路径，不读取文件内容：

~~~bash
git -C "$CHECKOUT" -c core.quotepath=false ls-tree -r --name-only "$CANDIDATE" \
  > "$EVIDENCE/tracked-files.txt"

test -z "$(git -C "$CHECKOUT" ls-tree -r --name-only "$CANDIDATE" -- pack.sh)"
if grep -E '(^|/)application-local\.(yml|yaml|properties)$|(^|/)[^/]+\.local\.yml$' \
    "$EVIDENCE/tracked-files.txt"; then
  exit 1
fi
if grep -E '(^|/)(node_modules|target|dist)(/|$)|\.(jar|class|log|mv\.db|trace\.db)$' \
    "$EVIDENCE/tracked-files.txt"; then
  exit 1
fi
~~~

- `PASS`：`pack.sh`、本地配置、依赖目录和构建产物均不在候选树。
- `FAIL`：任一禁止路径进入 commit；不得读取内容后放行。

## 4. JAVA：Java 8 Reactor

### JAVA-01 工具版本

~~~bash
(
  cd "$CHECKOUT/java"
  java -version 2>&1 | tee "$EVIDENCE/java-version.txt"
  mvn -version 2>&1 | tee "$EVIDENCE/maven-version.txt"
)
grep -Eq 'version "1\.8\.' "$EVIDENCE/java-version.txt"
~~~

- `PASS`：真实 JDK 8；Maven 满足最终文档声明的最低版本。
- `BLOCKED`：只能使用高版本 JDK 或无法确认 Maven 版本。

### JAVA-02 全 Reactor 测试

~~~bash
(
  cd "$CHECKOUT/java"
  mvn --batch-mode --no-transfer-progress \
    -Dmaven.repo.local="$PREFLIGHT_ROOT/m2" \
    clean test
) 2>&1 | tee "$EVIDENCE/maven-clean-test.log"
~~~

- `PASS`：父 POM和 7 个子模块共 8 个 Reactor project 全部 `SUCCESS`，测试零失败、零错误。
- `FAIL`：任一模块跳过、失败或依赖本机旧 SNAPSHOT 才能通过。
- 证据必须记录 Reactor summary、各模块 tests run/failures/errors/skipped 和总耗时。

### JAVA-03 禁止 public Maven publish

R0 禁止执行 `mvn deploy`、中央仓库上传、签名发布或修改远程仓库。验证性 `install` 只能写入
`PREFLIGHT_ROOT` 下的隔离 Maven 本地仓库。

- `PASS`：证据中只有 `test`、`package`、`verify` 或隔离 `install`。
- `FAIL`：执行了任何 public Maven publish/deploy。

## 5. WEB：npm workspaces

### WEB-01 Node/npm 和 workspace

~~~bash
(
  cd "$CHECKOUT/web"
  node --version | tee "$EVIDENCE/node-version.txt"
  npm --version | tee "$EVIDENCE/npm-version.txt"
  node -e "const [major,minor]=process.versions.node.split('.').map(Number); \
    if(major!==22||minor<12)process.exit(1)"
  node -e "const p=require('./package.json');const e=[ \
    'packages/agent','packages/widget','packages/webmcp-adapter', \
    'packages/tool-inspector','packages/call-trace']; \
    if(JSON.stringify(p.workspaces)!==JSON.stringify(e))process.exit(1)"
)
~~~

- `PASS`：Node 22.12+、npm 版本已记录、5 个 workspace 精确匹配。
- `BLOCKED`：没有满足约束的 Node 22 环境。

### WEB-02 锁文件安装和测试

~~~bash
(
  cd "$CHECKOUT/web"
  npm ci
  npm test
) 2>&1 | tee "$EVIDENCE/npm-ci-test.log"
~~~

- `PASS`：使用 tracked lockfile，五包测试全通过并记录每包测试数。
- `FAIL`：改用 `npm install`、lockfile 变化、任一 workspace 未测试或失败。

### WEB-03 五包构建

~~~bash
(
  cd "$CHECKOUT/web"
  npm run build
) 2>&1 | tee "$EVIDENCE/npm-build.log"
~~~

- `PASS`：Agent、WebMCP、Widget、Inspector、Call Trace 全部构建成功。
- `FAIL`：跳过 workspace、依赖已有 `dist` 或只构建单个 Bundle。

### WEB-04 禁止 public npm publish

R0 禁止执行 `npm publish`、修改 registry access、使用发布 Token 或把 workspace 描述成已发布公共包。

- `PASS`：只执行 `npm ci/test/run build`。
- `FAIL`：发生 public npm publish 或 registry 写操作。

## 6. BUNDLE：四个 Starter IIFE

### BUNDLE-01 tracked gate

~~~bash
git -C "$CHECKOUT" diff --exit-code -- \
  java/patchbridge-agent-spring-boot2-starter/src/main/resources/META-INF/patchbridge-agent/patchbridge-agent.js \
  java/patchbridge-agent-spring-boot2-starter/src/main/resources/META-INF/patchbridge-agent/patchbridge-agent-webmcp-adapter.js \
  java/patchbridge-agent-spring-boot2-starter/src/main/resources/META-INF/patchbridge-agent/patchbridge-agent-tool-inspector.js \
  java/patchbridge-agent-spring-boot2-starter/src/main/resources/META-INF/patchbridge-agent/patchbridge-agent-call-trace.js \
  2>&1 | tee "$EVIDENCE/bundle-git-diff.log"
~~~

- `PASS`：完整 Web 构建后四个 tracked 资源无差异。
- `FAIL`：任一文件变化、缺失或通过恢复 tracked 文件掩盖差异。

### BUNDLE-02 dist 与 Starter 逐字节一致

~~~bash
cmp -s "$CHECKOUT/web/packages/widget/dist/patchbridge-agent.js" \
  "$CHECKOUT/java/patchbridge-agent-spring-boot2-starter/src/main/resources/META-INF/patchbridge-agent/patchbridge-agent.js"
cmp -s "$CHECKOUT/web/packages/webmcp-adapter/dist/patchbridge-agent-webmcp-adapter.js" \
  "$CHECKOUT/java/patchbridge-agent-spring-boot2-starter/src/main/resources/META-INF/patchbridge-agent/patchbridge-agent-webmcp-adapter.js"
cmp -s "$CHECKOUT/web/packages/tool-inspector/dist/patchbridge-agent-tool-inspector.js" \
  "$CHECKOUT/java/patchbridge-agent-spring-boot2-starter/src/main/resources/META-INF/patchbridge-agent/patchbridge-agent-tool-inspector.js"
cmp -s "$CHECKOUT/web/packages/call-trace/dist/patchbridge-agent-call-trace.js" \
  "$CHECKOUT/java/patchbridge-agent-spring-boot2-starter/src/main/resources/META-INF/patchbridge-agent/patchbridge-agent-call-trace.js"

shasum -a 256 \
  "$CHECKOUT/java/patchbridge-agent-spring-boot2-starter/src/main/resources/META-INF/patchbridge-agent/"*.js \
  | tee "$EVIDENCE/bundle-sha256.txt"
~~~

- `PASS`：四组 `cmp` 均为 0，并记录四个 Starter 资源 SHA-256。
- `FAIL`：只检查存在、只比较部分 Bundle 或未与 candidate tracked 内容比较。

## 7. JAR：验证性 Starter 制品

### JAR-01 Web 后执行隔离 install

~~~bash
(
  cd "$CHECKOUT/java"
  mvn --batch-mode --no-transfer-progress \
    -Dmaven.repo.local="$PREFLIGHT_ROOT/m2" \
    install -DskipTests
) 2>&1 | tee "$EVIDENCE/maven-install.log"

export STARTER_JAR="$CHECKOUT/java/patchbridge-agent-spring-boot2-starter/target/patchbridge-agent-spring-boot2-starter-0.1.0-SNAPSHOT.jar"
test -f "$STARTER_JAR"
jar tf "$STARTER_JAR" | LC_ALL=C sort > "$EVIDENCE/starter-jar-entries.txt"
~~~

- `PASS`：四 Bundle 验证后构建，隔离仓库 install 成功。
- `FAIL`：JAR 来自 Web 构建前、旧 `target` 或主工作树。

### JAR-02 必需和禁止内容

~~~bash
for entry in \
  META-INF/spring.factories \
  META-INF/patchbridge-agent-admin/index.html \
  META-INF/patchbridge-agent/patchbridge-agent.js \
  META-INF/patchbridge-agent/patchbridge-agent-webmcp-adapter.js \
  META-INF/patchbridge-agent/patchbridge-agent-tool-inspector.js \
  META-INF/patchbridge-agent/patchbridge-agent-call-trace.js
do
  grep -Fxq "$entry" "$EVIDENCE/starter-jar-entries.txt"
done

legacy_dash="$(printf '%s-%s' enterprise agent)"
legacy_compact="$(printf '%s%s' enterprise agent)"
if grep -E "(^BOOT-INF/|application-local|$legacy_dash|com/$legacy_compact|(^|/)test/|Test\.class$)" \
    "$EVIDENCE/starter-jar-entries.txt"; then
  exit 1
fi

javap -verbose -classpath "$STARTER_JAR" \
  io.patchbridge.agent.starter.PatchBridgeAgentAutoConfiguration \
  > "$EVIDENCE/starter-class-version.txt"
grep -Fq 'major version: 52' "$EVIDENCE/starter-class-version.txt"
~~~

- `PASS`：资源齐全；Starter 是 Java 8 thin JAR；没有 Demo、测试、本地配置或旧品牌内容。
- `FAIL`：缺少必需内容、出现 `BOOT-INF`、class 不是 major 52 或命中禁止内容。

### JAR-03 source-only 后续项记录

以下两项必须记录 `PRESENT`/`ABSENT`，但在没有已批准交付需求时结果为 `N/A`；`ABSENT` 不单独导致
R0 source-only 失败：

~~~bash
for optional in META-INF/spring-configuration-metadata.json META-INF/LICENSE; do
  if grep -Fxq "$optional" "$EVIDENCE/starter-jar-entries.txt"; then
    printf '%s PRESENT\n' "$optional"
  else
    printf '%s ABSENT\n' "$optional"
  fi
done | tee "$EVIDENCE/starter-jar-source-only-findings.txt"
~~~

- Spring configuration metadata：发现/后续，不虚构成当前必交付物。
- JAR 内根 LICENSE：发现/后续；R0 强制要求 source archive 含根 LICENSE。
- 若范围增加二进制 JAR 分发，必须重新确认门禁，不能沿用本项 `N/A`。

## 8. ARCHIVE：不可变源码包

### ARCHIVE-01 从 commit 生成

~~~bash
git -C "$CHECKOUT" archive \
  --format=tar \
  --prefix=patchbridge-agent-v0.1/ \
  --output="$ARCHIVE" \
  "$CANDIDATE"

shasum -a 256 "$ARCHIVE" | tee "$EVIDENCE/source-archive-sha256.txt"
tar -tf "$ARCHIVE" > "$EVIDENCE/source-archive-entries.txt"
mkdir -p "$ARCHIVE_ROOT/source"
tar -xf "$ARCHIVE" -C "$ARCHIVE_ROOT/source" --strip-components=1
~~~

- `PASS`：归档只来自完整 commit，记录 SHA-256 和清单。
- `FAIL`：直接 tar 工作目录、混入 index/untracked/ignored 文件或无法解压。

### ARCHIVE-02 内容边界

~~~bash
grep -Fxq 'patchbridge-agent-v0.1/LICENSE' "$EVIDENCE/source-archive-entries.txt"
grep -Fxq 'patchbridge-agent-v0.1/README.md' "$EVIDENCE/source-archive-entries.txt"
grep -Fxq 'patchbridge-agent-v0.1/QUICKSTART.md' "$EVIDENCE/source-archive-entries.txt"
grep -Fxq 'patchbridge-agent-v0.1/THIRD_PARTY_NOTICES.md' "$EVIDENCE/source-archive-entries.txt"
grep -Fxq 'patchbridge-agent-v0.1/docs/README.md' "$EVIDENCE/source-archive-entries.txt"
grep -Fxq 'patchbridge-agent-v0.1/docs/roadmap.md' "$EVIDENCE/source-archive-entries.txt"
grep -Fxq 'patchbridge-agent-v0.1/docs/releases/v0.1/release-notes.md' "$EVIDENCE/source-archive-entries.txt"
grep -Fxq 'patchbridge-agent-v0.1/docs/releases/v0.1/release-checklist.md' "$EVIDENCE/source-archive-entries.txt"
grep -Fxq 'patchbridge-agent-v0.1/docs/releases/v0.1/evidence/rc2-preflight.md' "$EVIDENCE/source-archive-entries.txt"

if grep -E '(^|/)pack\.sh$|(^|/)application-local\.(yml|yaml|properties)$|(^|/)[^/]+\.local\.yml$|(^|/)(node_modules|target|dist)(/|$)|\.(jar|class|log|mv\.db|trace\.db)$' \
    "$EVIDENCE/source-archive-entries.txt"; then
  exit 1
fi
~~~

- `PASS`：源码、构建描述符、用户入口和 LICENSE 齐全，禁止项为零。
- `FAIL`：`pack.sh`、本地秘密配置或构建产物进入 archive。
- `AGENTS.md`、测试、历史/研究文档和 E2E 脚本按最终批准的 archive 清单显式记录；不得临时裁剪。

### ARCHIVE-03 从源码包复建

~~~bash
(
  cd "$ARCHIVE_ROOT/source/web"
  npm ci
  npm test
  npm run build
) 2>&1 | tee "$EVIDENCE/archive-web-build.log"

(
  cd "$ARCHIVE_ROOT/source/java"
  mvn --batch-mode --no-transfer-progress \
    -Dmaven.repo.local="$PREFLIGHT_ROOT/m2-archive" \
    clean install
) 2>&1 | tee "$EVIDENCE/archive-java-install.log"
~~~

- `PASS`：最终 source tar 在无 `.git`、旧 `dist`、旧 `target` 和本机 SNAPSHOT 的环境中可测试、构建并产出 Demo JAR。
- `FAIL`：只验证仓库 checkout、依赖未归档文件或需要修改 archive。

## 9. LICENSE：源码和第三方声明

### LICENSE-01 根 LICENSE

~~~bash
test -f "$ARCHIVE_ROOT/source/LICENSE"
grep -Fq 'MIT License' "$ARCHIVE_ROOT/source/LICENSE"
~~~

- `PASS`：根 MIT LICENSE 在 candidate 和 source archive 中存在，POM/npm 声明一致。
- `FAIL`：缺失、被裁剪或声明不一致。

### LICENSE-02 Demo vendored 资产

~~~bash
test -f "$ARCHIVE_ROOT/source/THIRD_PARTY_NOTICES.md"
export VENDOR="$ARCHIVE_ROOT/source/java/patchbridge-agent-demo/src/main/resources/static/css/vendor"
for file in \
  README.md \
  NES.css-LICENSE.txt \
  PressStart2P-OFL-LICENSE.txt \
  cybercore-LICENSE.txt \
  rpgui/RPGUI-LICENSE.txt
do
  test -f "$VENDOR/$file"
done
~~~

- `PASS`：根级 Third-Party Notices 与四类 vendored 资产的来源、邻接许可证相互对应，archive 未拆散资产和声明。
- `FAIL`：素材存在但来源/许可证缺失。

### LICENSE-03 source-only 后续项记录

Starter JAR 内 LICENSE 和未来 npm tarball 许可证内容必须记录当前 `PRESENT`/`ABSENT`。
未确认二进制/公共包需求时可 `N/A`，不得将其虚构为 R0 必交付物；根级 Third-Party Notices
已经属于 LICENSE-02，不能再标记 `N/A`。

## 10. META：坐标、版本和范围

### META-01 当前坐标和版本

~~~bash
(
  cd "$CHECKOUT/java"
  mvn --batch-mode --no-transfer-progress \
    -Dmaven.repo.local="$PREFLIGHT_ROOT/m2" \
    help:evaluate -Dexpression=project.groupId -q -DforceStdout
  mvn --batch-mode --no-transfer-progress \
    -Dmaven.repo.local="$PREFLIGHT_ROOT/m2" \
    help:evaluate -Dexpression=project.version -q -DforceStdout
) | tee "$EVIDENCE/maven-coordinate.txt"

(
  cd "$CHECKOUT/web"
  node -e "const r=require('./package.json');console.log(r.name,r.version,r.private); \
    for(const p of r.workspaces){const m=require('./'+p+'/package.json'); \
    console.log(m.name,m.version,Boolean(m.private))}"
) | tee "$EVIDENCE/npm-coordinate.txt"
~~~

- `PASS`：版本说明明确 source-only，并如实记录 Java/npm 版本与差异；不宣称公共坐标可用。
- `FAIL`：把 SNAPSHOT/workspace 版本描述成已发布，或候选标签与版本说明矛盾。

### META-02 公共包元数据只记录

Maven project URL/SCM/developers/source/javadoc/signing/deploy，以及 npm repository/homepage/bugs/engines/
packageManager、workspace `files/exports/types` 和包内 LICENSE，均属于公共 Maven/npm 发布准备项。

- R0 source-only：记录实际字段和缺口，可 `N/A`。
- 禁止猜测组织、开发者、仓库 URL、发布坐标或兼容承诺来制造 `PASS`。
- 后续进入公共包发布时必须重新立项，不能沿用 R0 `N/A`。

### META-03 发布禁令

- Maven Central publish：禁止。
- npm public publish：禁止。
- 发布签名、registry token、Central token：不得索取或使用。
- `PASS`：最终记录明确 `source-only=true`、`mavenPublish=N/A`、`npmPublish=N/A`。
- `FAIL`：执行 public publish，或把本地可构建等同于公共包已发布。

## 11. SCAN：品牌、秘密和禁止资产

### SCAN-01 旧品牌

~~~bash
legacy_dash="$(printf '%s-%s' enterprise agent)"
legacy_underscore="$(printf '%s_%s' enterprise agent)"
legacy_compact="$(printf '%s%s' enterprise agent)"
legacy_repo="$(printf '%s-%s-%s' enterprise web agent)"
legacy_pattern="($legacy_dash|$legacy_underscore|$legacy_compact|$legacy_repo|com\.$legacy_compact|@$legacy_dash|<$legacy_dash)"

# .gitleaksignore 例外：历史 gitleaks 指纹必须逐字引用改名前的历史路径才能生效，
# 其中出现的旧品牌字符串是 Git 历史坐标，不是存活标识；除此之外仍要求零命中。
if git -C "$CHECKOUT" grep -n -I -i -E "$legacy_pattern" "$CANDIDATE" -- ':!.gitleaksignore'; then
  exit 1
fi
if grep -i -E "$legacy_pattern" "$EVIDENCE/tracked-files.txt" | grep -v '^\.gitleaksignore$'; then
  exit 1
fi
~~~

- `PASS`：candidate 内容和路径的旧公开标识零命中。
- `FAIL`：旧 package、配置、环境变量、资源、组件、事件、CSS、npm scope 或 bundle 名仍存在。

### SCAN-02 敏感路径

~~~bash
if grep -E '(^|/)(\.env($|\.)|id_rsa|id_ed25519|credentials|secrets?\.(yml|yaml|json|properties)|application-local\.)|\.(pem|key|p12|pfx|jks|keystore)$' \
    "$EVIDENCE/tracked-files.txt"; then
  exit 1
fi
~~~

- `PASS`：candidate tree 无敏感文件名。
- `FAIL`：疑似凭据文件进入 commit；不得读取后放行。

### SCAN-03 专用秘密扫描

使用批准且固定版本的扫描器；缺少工具时为 `BLOCKED`，不得以宽泛 grep 替代：

~~~bash
command -v gitleaks
gitleaks version | tee "$EVIDENCE/gitleaks-version.txt"
gitleaks git "$CHECKOUT" --log-opts='--all' --redact \
  --report-format=json --report-path="$EVIDENCE/gitleaks-history.json"
gitleaks dir "$ARCHIVE_ROOT/source" --redact \
  --report-format=json --report-path="$EVIDENCE/gitleaks-source.json"
~~~

- `PASS`：source archive 和完整 Git 历史无未处置 finding。
- `FAIL`：真实凭据、私钥、无法证明为示例的高熵值，或报告泄露匹配值。
- 允许分类只记录 rule/path/line/reason，不记录值。
- 禁止扫描主工作树，从而不读取 ignored 本地配置。

### SCAN-04 Mock 和内部环境依赖

~~~bash
if git -C "$CHECKOUT" grep -n -I -E '(Mock AI|Mock MCP|mock model server|mock mcp server)' \
    "$CANDIDATE" -- 'java/*/src/main/**' 'web/packages/*/src/**' \
    ':!**/src/test/**' ':!**/test/**'; then
  exit 1
fi
~~~

- `PASS`：生产源码和 Demo 不内置 Mock AI/MCP；测试专用 Mock 不计入。
- `FAIL`：Demo 依赖未公开内部 endpoint、硬编码本机路径或 Mock 协议服务。

## 12. DOC：文档、链接和冲突

### DOC-01 本地链接和 anchor

使用固定版本离线链接检查器；缺少工具时为 `BLOCKED`：

~~~bash
command -v lychee
lychee --version | tee "$EVIDENCE/lychee-version.txt"
(
  cd "$CHECKOUT"
  git ls-files -z '*.md' | xargs -0 lychee --offline --include-fragments --no-progress
) 2>&1 | tee "$EVIDENCE/markdown-links.log"
~~~

- `PASS`：全部 tracked Markdown 的相对文件链接和 anchor 有效。
- `FAIL`：链接只在主工作树、ignored 文件或 build output 中存在。
- 外部 HTTP 链接另记；因网络政策不可访问时可 `N/A`，不能掩盖本地链接失败。

### DOC-02 权威文档一致性

逐项核对路线图、架构文档、ADR、文档中心、Guide/Reference、`README.md`、`QUICKSTART.md` 和版本说明：

- 路线图负责唯一实现状态；架构/ADR 负责实现边界；
- 已实现、宿主责任、未实现和暂缓能力必须一致；
- 预留 enum/type、未来 Adapter、历史方案不得描述成当前能力；
- 文档中的命令、坐标、配置、端点、组件、事件和 Bundle 路径必须来自同一 candidate。

任一冲突被文字解释但未在源码/文档中修复为单一事实，结果为 `FAIL`。

### DOC-03 已知冲突清零

| ID | 必须核对的冲突 |
| --- | --- |
| `C-ARCH-01` | Java 模块允许依赖表与 POM 直接依赖一致 |
| `C-ARCH-02` | MCP Port 的文档归属与源码模块归属一致 |
| `C-ARCH-03` | 权威 Browser 包清单包含当前 Call Trace 边界 |
| `C-DOC-01` | OpenAPI 不再被文档中心/Guide 描述成当前已实现来源 |
| `C-SEC-01` | MCP 仅 properties/jdbc、JDBC key 规则与自定义 Store 行为形成唯一契约 |
| `C-NOFALLBACK-01` | 自定义 Tool snapshot 冻结契约与 Runtime 行为/注释一致 |
| `C-DOC-02` | Strands 当前路线与源码注释、公开文档一致 |

每项必须引用修复后的 candidate commit。不得用“可理解为”“可能属于”“为了兼容”关闭冲突；任一 `OPEN`
直接 `FAIL`。

### DOC-04 从零命令

在 ARCHIVE-03 的 source archive 中执行最终 README、Quickstart 和文档中心/Guide/Reference 中的命令。必须使用 `npm ci`、
Java 8 和隔离 Maven 仓库，不依赖已有 `node_modules`、`target` 或 SNAPSHOT。

- `PASS`：陌生使用者只读公开文档即可复现。
- `FAIL`：需要未写明步骤、内部知识或修改源码。

## 13. ARCH：架构不变量

### ARCH-01 Java 模块方向

~~~bash
(
  cd "$CHECKOUT/java"
  mvn --batch-mode --no-transfer-progress \
    -Dmaven.repo.local="$PREFLIGHT_ROOT/m2" dependency:tree
) 2>&1 | tee "$EVIDENCE/maven-dependency-tree.log"
~~~

核对：annotations 不依赖 Spring；core 不依赖 Spring/Jackson/JDBC/OkHttp/WebFlux/厂商 SDK；Adapter 指向稳定
Port；Starter 只做 Composition Root；框架不依赖 Demo；DOC-03 的模块与 MCP Port 冲突已解决。
反向依赖、未声明依赖或环均为 `FAIL`。

### ARCH-02 Browser 方向和单一状态源

~~~bash
if git -C "$CHECKOUT" grep -n -E \
  "from ['\"]@patchbridge-agent/(widget|webmcp-adapter|tool-inspector|call-trace)['\"]" \
  "$CANDIDATE" -- 'web/packages/agent/src/**'; then
  exit 1
fi
if git -C "$CHECKOUT" grep -n -E \
  'new DefaultAgentController|createAgentController|new DefaultAgentRuntime|new DefaultToolRegistry' \
  "$CANDIDATE" -- 'web/packages/tool-inspector/src/**' 'web/packages/call-trace/src/**'; then
  exit 1
fi
~~~

并确认：Controller 状态只经具名事件/reducer；模型定义与 Tool 调用使用同一 `ToolRegistrySnapshot`；
Widget/Inspector/Call Trace 无第二份消息、Registry 或 Runtime 状态。任一额外状态源或反向依赖为 `FAIL`。

### ARCH-03 Provider 协议隔离

~~~bash
if git -C "$CHECKOUT" grep -n -I -E '(choices|tool_calls|reasoning_content|\[DONE\])' \
  "$CANDIDATE" -- 'java/patchbridge-agent-core/src/main/**' \
  'web/packages/agent/src/runtime.ts' 'web/packages/agent/src/controller.ts'; then
  exit 1
fi
~~~

- `PASS`：厂商字段只存在于 Provider Adapter。
- `FAIL`：Runtime、Controller 或 Java Core 解析厂商 chunk，或 Browser 接受双轨 SSE。

### ARCH-04 后端无跨请求 Agent 状态

~~~bash
# 资源目录例外：Starter 内置的四个 IIFE 是浏览器端 bundle，其前端状态字段
# （如 pendingConfirmation）不属于 Java 后端；本不变量只针对后端 Java 源码。
if git -C "$CHECKOUT" grep -n -I -E \
  '(AgentExecution|AgentRuntime|currentRun|pendingConfirmation|AgentCheckpoint|@SessionAttributes|sessionScope)' \
  "$CANDIDATE" -- 'java/*/src/main/**' ':!**/META-INF/patchbridge-agent/**'; then
  exit 1
fi
~~~

动态使用相同数据库启动两个节点，让 Model/Tool/Conversation 请求交替命中；请求间重启节点；确认 Browser 显式携带
Context、Conversation 从 revision 恢复、刷新不恢复执行一半的 HITL/Agent Loop。

- `PASS`：无跨请求 Agent Execution；请求级 SSE 对象完成后释放。
- `FAIL`：依赖同一 JVM current run、等待确认对象、线程、Checkpoint 或内存会话。

### ARCH-05 原子上下文和取消

必须有测试证明：`messages + modelState` 同 revision/事务；Tool 参数验证完成后才稳定；取消后无迟到事件/消息/保存；
每次 start 返回独立 Execution；Registry 更新只影响下一轮。缺任一成功、失败、取消或并发证据为 `FAIL`。

## 14. SECURITY：安全默认值和无 fallback

### SECURITY-01 默认值快照

| 配置/Port | R0 预期 |
| --- | --- |
| Starter enabled | `true` |
| Admin enabled | `false` |
| MCP enabled/source/servers | `true` / `properties` / empty |
| Audit enabled/payload | `true` / `metadata-only` |
| ToolAccessPolicy | 仅认证用户可发现和调用 |
| ConversationRepository | JDBC 或宿主显式实现；无内存替代 |
| 默认 MCP JDBC key | 必须是 32-byte Base64 |
| 未知配置/DTO/SSE 字段 | 明确失败 |

- `PASS`：实际默认值与最终文档一致，错误配置 fail fast。
- `FAIL`：缺失 Bean 时出现 allow-all、内存存储、明文凭据、静默修正或替代实现。

### SECURITY-02 身份、权限和 owner

验证：UserContext 只来自服务端；Browser 身份/权限/risk 不改变可信事实；list 用 `canDiscover` 且每次 call
重新 `canInvoke`；ownerKey 进入每条 Conversation SQL；跨 owner 与不存在统一 404；Admin 每条路由执行
`AdminAccessPolicy`，未知路由 fail closed。任一覆盖、发现即授权、越权枚举或 allow-all 为 `FAIL`。

### SECURITY-03 MCP 凭据和出站

验证：默认 JDBC Store 使用 AES-256-GCM/独立 IV/AAD；缺失、非 Base64、非 32-byte key 失败；Admin 不回显秘密；
Browser Cookie/Authorization 不透传；静态 Header 不能覆盖受保护 Header；SSRF 出口责任明确；properties/JDBC
不合并、不失败切换。`C-SEC-01` 未解决时直接 `FAIL`。

### SECURITY-04 错误和审计脱敏

验证：模型非 2xx body、Jackson parser detail、凭据、ModelState data 和完整 payload 不进入 Browser/普通日志/Audit；
默认 Audit 仅 metadata；错误保留稳定 code/status，不以成功空值掩盖失败。任一越界为 `FAIL`。

### SECURITY-05 唯一契约和无 fallback

确认：无旧 DTO/alias/deprecated API/数据库双写/自动迁移；ModelState format mismatch 失败；MCP 单一协议；
Tool 不重试；Model 只在零框架事件网络失败时单次重连；Call Trace 损坏数据整体拒绝且不修复旧 v1；
properties/JDBC、默认/自定义实现不因运行失败切换。

以下显式失败行为必须分别有测试和用户可见错误：

1. Inspector 加载失败显示错误并保留上次成功 snapshot；
2. Call Trace 采集默认关闭（无 call-trace 属性时不创建采集 Hook、不访问 localStorage）；开启 persistent 后 localStorage 不可用时只保留本页内存并显示保存错误；
3. Model 零事件网络失败的单次重连。

除最终契约明确保留的行为外，新增 fallback/兼容路径即 `FAIL`。`C-NOFALLBACK-01` 未解决时直接 `FAIL`。

## 15. DEMO：真实接入

### DEMO-01 隔离启动

必须从 source archive 启动，不使用本机 local profile。秘密由批准的外部注入提供，只记录变量名：

~~~bash
test -n "$PATCHBRIDGE_AGENT_MODEL_BASE_URL"
test -n "$PATCHBRIDGE_AGENT_MODEL"
export PATCHBRIDGE_AGENT_MODEL_BASE_URL PATCHBRIDGE_AGENT_MODEL
if test -n "${PATCHBRIDGE_AGENT_MODEL_API_KEY:-}"; then
  export PATCHBRIDGE_AGENT_MODEL_API_KEY
fi
export PATCHBRIDGE_AGENT_MCP_ENCRYPTION_KEY="$(openssl rand -base64 32)"
export SPRING_DATASOURCE_URL='jdbc:h2:mem:r0preflight;DB_CLOSE_DELAY=-1'
export SERVER_PORT=8080
export DEMO_JAR="$ARCHIVE_ROOT/source/java/patchbridge-agent-demo/target/patchbridge-agent-demo-0.1.0-SNAPSHOT.jar"

test -f "$DEMO_JAR"
java -jar "$DEMO_JAR" > "$EVIDENCE/demo.log" 2>&1 &
export DEMO_PID=$!
trap 'kill "$DEMO_PID" 2>/dev/null || true' EXIT
for attempt in $(seq 1 60); do
  if curl --silent --show-error --fail http://127.0.0.1:8080/login > /dev/null; then
    break
  fi
  kill -0 "$DEMO_PID"
  sleep 1
done
curl --silent --show-error --fail http://127.0.0.1:8080/login > /dev/null
~~~

- `PASS`：不读取 `application-local.*`，使用临时数据库/key 和显式模型配置启动；API Key 仅在目标网关要求时注入。
- `BLOCKED`：无批准的真实 Model/MCP；不得改用 Mock 记为通过。

### DEMO-02 HTTP、认证和资源

记录：首页/登录页；匿名 `/ai/**` 401；四个 `/ai/assets/patchbridge-agent*.js` 200；旧资源 404；Demo 角色 Tool/Admin
权限；Conversation CRUD/revision/cross-owner；Admin Audit 授权。可用 `curl --fail-with-body`，证据不得保存 Cookie、
Authorization 或秘密响应字段。

### DEMO-03 真实 Model、Tool、MCP 和 Browser

~~~bash
(
  cd "$ARCHIVE_ROOT/source/web"
  node tools/e2e-api-test.mjs
  node tools/e2e-real-llm-test.mjs
) 2>&1 | tee "$EVIDENCE/demo-e2e.log"
~~~

人工验证：真实模型结构化 SSE、Tool 决策和取消；真实 Global MCP CRUD/测试/刷新；Inspector snapshot revision；
Call Trace 耗时/token/首 token/速度/恢复/清空/错误说明；WebMCP 仅真实支持时启用；Demo 不内置 Mock。
任一能力以 Mock、静态假数据或内部不可访问服务代替为 `FAIL`。

## 16. FINAL：冻结

### FINAL-01 汇总条件

只有以下条件全部成立才能冻结：

- GIT、JAVA、WEB、BUNDLE、JAR 必需项、ARCHIVE、LICENSE-01/02、SCAN、DOC、ARCH、SECURITY、DEMO 全部 `PASS`；
- 没有 `BLOCKED`；DOC-03 全部冲突 `RESOLVED` 且绑定同一 candidate；
- `N/A` 仅用于 source-only 后续项，并有理由和批准人；
- 测试后 tracked tree 干净，四 Bundle 无差异；
- `pack.sh`、本地配置和秘密未进入 candidate/archive/evidence；
- 文档中心、Guide/Reference、版本说明、已知限制、源码接入路径和路线图与 candidate 一致；
- 明确未执行 Maven Central/npm public publish。

### FINAL-02 Git 复核

~~~bash
git -C "$CHECKOUT" status --short --branch | tee "$EVIDENCE/git-status-after.txt"
test -z "$(git -C "$CHECKOUT" status --porcelain=v1 --untracked-files=no)"
test "$(git -C "$CHECKOUT" rev-parse HEAD)" = "$CANDIDATE"
git -C "$CHECKOUT" diff --check
git -C "$CHECKOUT" diff --cached --check
test -z "$(git -C "$CHECKOUT" ls-files -u)"
~~~

- `PASS`：commit 未漂移，除 ignored build 目录外无 tracked 变化。
- `FAIL`：测试生成 tracked 差异、证据对应旧 commit 或过程中修改 candidate。

### FINAL-03 集成人责任

本清单所在提交不是最终预检记录。全部修复合并后，集成人必须：

1. 选择新的完整 candidate commit；
2. 从头执行本清单，不继承旧 commit 的 PASS；
3. 创建绑定 commit/tree/archive SHA-256 的最终预检记录；
4. 同步路线图并审阅全部证据和 `N/A`；
5. 再决定是否创建源码候选 tag。

即使 FINAL 为 `PASS`，R0 仍禁止 Maven Central 和 npm public publish。公共包发布只能在后续里程碑重新确认
元数据、许可证、签名、SBOM、发布流水线和回滚方案后执行。
