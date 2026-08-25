/**
 * WebMCP 浏览器 Adapter 复制脚本：静态 Demo 可选择加载，默认 Widget 不依赖它。
 * 分包保证标准实验能力不会进入所有宿主的核心 bundle。
 */
import { copyFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const source = resolve(here, '../dist/patchbridge-agent-webmcp-adapter.js');
const target = resolve(
  here,
  '../../../../java/patchbridge-agent-spring-boot2-starter/src/main/resources/META-INF/patchbridge-agent/patchbridge-agent-webmcp-adapter.js',
);

mkdirSync(dirname(target), { recursive: true });
copyFileSync(source, target);
console.log(`copied: ${source} -> ${target}`);
