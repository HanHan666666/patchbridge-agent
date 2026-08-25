/**
 * 调用轨迹组件源码契约测试：无需 DOM 模拟器即可锁定生命周期、关联方式与可访问性边界。
 *
 * <p>组件的视觉行为由浏览器验证，单元测试只约束最容易在重构中回归的结构事实：
 * 断开重连不丢数据源、详情不靠平行数组断言、说明弹窗跨重绘保持打开并恢复焦点、
 * 性能指标允许换行、暗色有默认值。
 */
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

/** 当前组件源码；读取真实交付入口，避免复制一份测试夹具。 */
const source = readFileSync(
  new URL('../src/call-trace-element.ts', import.meta.url),
  'utf8',
);

describe('调用轨迹组件契约', () => {
  it('断开时只释放订阅，重连时恢复既有 source', () => {
    expect(source).toContain('connectedCallback(): void');
    expect(source).toContain('this.bindSource();');
    const disconnected = source.match(/disconnectedCallback\(\): void \{([\s\S]*?)\n  \}/)?.[1] ?? '';
    expect(disconnected).toContain('this.unbindSource();');
    expect(disconnected).not.toContain('this.sourceValue = null');
  });

  it('详情关联不使用平行数组下标或类型断言', () => {
    expect(source).toContain('toDetailSections(row.record)');
    expect(source).not.toContain('as ExecutionTrace');
    expect(source).not.toContain('as CallTraceRecord');
    expect(source).not.toContain('sourceValue?.snapshot()');
  });

  it('存储说明 dialog 具备可访问名称、跨重绘保持打开并恢复焦点', () => {
    expect(source).toContain("dialog.setAttribute('aria-labelledby'");
    expect(source).toContain('[data-dialog-close]');
    expect(source).toContain("dialog.addEventListener('close', onClosed)");
    expect(source).toContain('private infoDialogOpen = false');
    expect(source).toContain('if (this.infoDialogOpen)');
    expect(source).toContain('this.showInfoDialog();');
  });

  it('模型性能指标在窄桌面可换行，移动端落到摘要列', () => {
    expect(source).toContain('display: flex; flex-wrap: wrap;');
    expect(source).toContain('.time, .tokens, .first-token, .token-speed { grid-column: 2; }');
  });

  it('输出截断终态使用警告色而不冒充失败', () => {
    expect(source).toContain('.status[data-state="输出已截断"]');
    expect(source).toContain('--patchbridge-agent-trace-warning-color');
    expect(source).toContain('--patchbridge-agent-trace-warning-background');
  });

  it('dialog 有显式背景并提供系统暗色默认适配', () => {
    expect(source).toContain('background: var(--patchbridge-agent-trace-background, #fff)');
    expect(source).toContain('@media (prefers-color-scheme: dark)');
    expect(source).toContain('background: var(--patchbridge-agent-trace-background, #1f2937)');
  });
});
