/**
 * 调用轨迹构建产物复制脚本：与 Widget / Inspector 产物并列放入 Starter 资源目录，
 * 仅在宿主显式加载该脚本时启用轨迹视图，保持可插拔边界。
 */
import { copyFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const source = resolve(here, '../dist/patchbridge-agent-call-trace.js');
const target = resolve(
  here,
  '../../../../java/patchbridge-agent-spring-boot2-starter/src/main/resources/META-INF/patchbridge-agent/patchbridge-agent-call-trace.js',
);

mkdirSync(dirname(target), { recursive: true });
copyFileSync(source, target);
console.log(`copied: ${source} -> ${target}`);
