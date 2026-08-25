# 从源码构建

## 环境要求与源码构建

### 工具版本

| 工具 | 要求 | 说明 |
| --- | --- | --- |
| JDK | 8+ | 当前验证方向为 Java 8 |
| Maven | 3.6+ | Java Reactor 构建 |
| Node.js | 22.12+ | 仅重新构建 Browser workspace 时需要；锁文件由该版本生成 |
| 数据库 | H2 或 MySQL | Demo 默认 H2 文件库；宿主可提供自定义 Repository |

宿主只消费已本地安装的 Starter JAR 和其内置 Bundle 时，不需要 Node.js。

### 从源码构建 Browser 与 Java

在仓库根目录执行：

~~~bash
cd web
npm ci
npm run test
npm run build

cd ../java
mvn clean install
~~~

<code>npm run build</code> 会构建五个 workspace，并把四个 IIFE 复制到 Starter 的 <code>META-INF/patchbridge-agent/</code> 资源目录。随后必须从 <code>java/</code> Reactor 根目录重新构建 Starter，不能停留在 <code>web/</code> 目录执行 Maven。

只重新安装 Starter 及其 Reactor 依赖：

~~~bash
cd java
mvn install -pl patchbridge-agent-spring-boot2-starter -am
~~~

仓库包含锁文件，Browser 依赖安装使用 <code>npm ci</code>，保证与 CI 一致的可复现安装。

### source-only 依赖方式

先在框架仓库执行 <code>mvn install</code>，再在宿主项目引用本机 Maven 仓库中的 SNAPSHOT。该坐标当前不能从 Maven Central 解析。