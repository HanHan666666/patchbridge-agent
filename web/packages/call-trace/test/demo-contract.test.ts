/**
 * 调用轨迹 Demo 装配契约测试：锁定可选 bundle、ready 数据源、页签键盘与主题映射。
 *
 * <p>真实浏览器验收在 Demo 启动后执行；该测试保证 CI 在未启动服务时也能发现
 * 静态脚本顺序或主题文件遗漏。
 */
import { existsSync, readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

/** 从仓库根目录读取一个 UTF-8 文件。 */
function readRoot(relativePath: string): string {
  return readFileSync(new URL(`../../../../${relativePath}`, import.meta.url), 'utf8');
}

/** Demo 首页。 */
const index = readRoot('java/patchbridge-agent-demo/src/main/resources/static/index.html');
/** 默认主题。 */
const baseTheme = readRoot(
  'java/patchbridge-agent-demo/src/main/resources/static/css/base-theme.css',
);
/** 七个可选主题文件。 */
const optionalThemes = [
  'theme-teal.css',
  'theme-dark.css',
  'theme-contrast.css',
  'theme-cyberpunk.css',
  'theme-win98.css',
  'theme-rpg.css',
  'theme-nes.css',
].map(name => readRoot(
  `java/patchbridge-agent-demo/src/main/resources/static/css/${name}`,
));

describe('调用轨迹 Demo 装配契约', () => {
  it('可选 Call Trace bundle 在 Widget 之前加载，ready 监听器不会丢事件', () => {
    const traceScript = index.indexOf(
      '<script src="/ai/assets/patchbridge-agent-call-trace.js"></script>',
    );
    const readyListener = index.indexOf(
      "document.addEventListener('patchbridge-agent-ready'",
    );
    const widgetScript = index.indexOf(
      '<script src="/ai/assets/patchbridge-agent.js"></script>',
    );

    expect(traceScript).toBeGreaterThan(0);
    expect(traceScript).toBeLessThan(widgetScript);
    expect(readyListener).toBeLessThan(widgetScript);
    expect(index).toContain('callTrace.traceSource = event.detail.callTraceSource');
  });

  it('Demo 提供三个 ARIA 页签并支持常用键盘选择键', () => {
    expect(index).toContain('id="chat-tab"');
    expect(index).toContain('id="tools-tab"');
    expect(index).toContain('id="trace-tab"');
    expect(index).toContain('id="trace-panel"');
    for (const key of ['ArrowLeft', 'ArrowRight', 'Home', 'End']) {
      expect(index).toContain(`event.key === '${key}'`);
    }
  });

  it('默认与七个可选主题都直接命中 Call Trace 元素', () => {
    expect(baseTheme).toContain('.tab-panel patchbridge-agent-call-trace');
    for (const theme of optionalThemes) {
      expect(theme).toContain('.tab-panel patchbridge-agent-call-trace');
    }
  });

  it('Starter 资源目录包含构建后的 Call Trace bundle', () => {
    const asset = new URL(
      '../../../../java/patchbridge-agent-spring-boot2-starter/src/main/resources/META-INF/patchbridge-agent/patchbridge-agent-call-trace.js',
      import.meta.url,
    );
    expect(existsSync(asset)).toBe(true);
  });
});
