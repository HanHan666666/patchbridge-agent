/**
 * Inspector 构建产物复制脚本：仅在宿主显式加载第二个脚本时启用调试列表，
 * 与默认聊天 Widget 分包，保证生产环境可以不携带、不加载这项调试能力。
 */
import { copyFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const source = resolve(here, '../dist/patchbridge-agent-tool-inspector.js');
const target = resolve(
  here,
  '../../../../java/patchbridge-agent-spring-boot2-starter/src/main/resources/META-INF/patchbridge-agent/patchbridge-agent-tool-inspector.js',
);

mkdirSync(dirname(target), { recursive: true });
copyFileSync(source, target);
console.log(`copied: ${source} -> ${target}`);
