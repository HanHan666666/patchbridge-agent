/**
 * Widget 公共样式契约测试。
 *
 * 该测试只验证宿主可依赖的 CSS Variables、Shadow Parts 与无主题边界，
 * 不绑定内部 class 或具体 DOM 层级，避免测试反过来阻止正常的 View 重构。
 */
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

/** 组件源码；Node 测试环境没有浏览器 HTMLElement，因此直接检查公开静态契约。 */
const SOURCE = readFileSync(
  new URL('../src/patchbridge-agent-element.ts', import.meta.url),
  'utf8',
);

/** 从模板字面量中收集全部公开 part 名称。 */
function publicParts(): Set<string> {
  return new Set(
    Array.from(SOURCE.matchAll(/\bpart="([^"]+)"/g))
      .flatMap(match => match[1].split(/\s+/)),
  );
}

describe('Widget 公共样式契约', () => {
  it('暴露约定的稳定语义 parts', () => {
    const parts = publicParts();
    const requiredParts = [
      'panel',
      'header',
      'conversation-list',
      'messages',
      'message',
      'status',
      'max-tokens-notice',
      'composer',
      'input',
      'send-button',
    ];

    for (const part of requiredParts) {
      expect(parts.has(part), `缺少 part="${part}"`).toBe(true);
    }
  });

  it('把 max-tokens 作为可定制的非错误终态提示', () => {
    expect(SOURCE).toContain("state.runOutcome?.type !== 'max-tokens'");
    expect(SOURCE).toContain('本次回答达到模型输出上限，内容可能不完整');
    expect(SOURCE).toContain('part="max-tokens-notice"');
    expect(SOURCE).toContain('var(--patchbridge-agent-color-warning-background)');
  });

  it('提供颜色、字体、间距、圆角与面板尺寸设计令牌', () => {
    const requiredTokens = [
      '--patchbridge-agent-color-primary',
      '--patchbridge-agent-color-background',
      '--patchbridge-agent-color-text',
      '--patchbridge-agent-color-border',
      '--patchbridge-agent-font-family',
      '--patchbridge-agent-font-size',
      '--patchbridge-agent-spacing-md',
      '--patchbridge-agent-radius-panel',
      '--patchbridge-agent-panel-width',
      '--patchbridge-agent-panel-height',
      '--patchbridge-agent-panel-min-height',
    ];

    for (const token of requiredTokens) {
      expect(SOURCE).toContain(`${token}:`);
    }
  });

  it('theme="none" 不注入参考主题的颜色和字体默认值', () => {
    const referenceTheme = SOURCE.match(
      /:host\(:not\(\[theme="none"\]\)\) \{([\s\S]*?)\n\}/,
    );
    expect(referenceTheme).not.toBeNull();
    expect(referenceTheme?.[1]).toContain('--patchbridge-agent-color-primary:');
    expect(referenceTheme?.[1]).toContain('--patchbridge-agent-font-family:');

    const sourceWithoutReferenceTheme = SOURCE.replace(referenceTheme?.[0] ?? '', '');
    expect(sourceWithoutReferenceTheme).not.toMatch(/#[0-9a-fA-F]{3,8}\b/);
    expect(sourceWithoutReferenceTheme).not.toMatch(/rgba?\(/);
  });
});
