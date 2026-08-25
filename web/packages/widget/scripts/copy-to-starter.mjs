/**
 * 构建产物复制脚本：widget bundle 产出到 starter 的静态资源目录，
 * 由 Spring Boot 打进 jar 并通过 /ai/assets/** 对外提供。
 *
 * <p>前端产物进 jar 而不是让宿主再引 npm 包，是为了维持“两行接入”承诺：
 * 宿主页面只需 <script src="/ai/assets/patchbridge-agent.js">。
 */
import { copyFileSync, mkdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const source = resolve(here, '../dist/patchbridge-agent.js');
// packages/widget/scripts → 上四级回到项目根，再进入 java 工程
const target = resolve(
  here,
  '../../../../java/patchbridge-agent-spring-boot2-starter/src/main/resources/META-INF/patchbridge-agent/patchbridge-agent.js',
);

mkdirSync(dirname(target), { recursive: true });
copyFileSync(source, target);
console.log(`copied: ${source} -> ${target}`);
