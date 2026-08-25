/**
 * Markdown 子集渲染单元测试：验证结构转换与安全转义。
 * 该函数直接处理模型输出（不可信输入），任何标签都必须被转义消灭。
 */
import { describe, expect, it } from 'vitest';
import { renderMarkdown } from '../src/markdown';

describe('renderMarkdown', () => {
  it('纯文本渲染为段落，HTML 标签被转义消灭', () => {
    expect(renderMarkdown('你好')).toBe('<p>你好</p>');
    expect(renderMarkdown('<script>alert(1)</script>'))
      .toBe('<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>');
  });

  it('连续文本行合并为一段，行间以 <br> 保留软换行', () => {
    expect(renderMarkdown('第一行\n第二行')).toBe('<p>第一行<br>第二行</p>');
    expect(renderMarkdown('第一段\n\n第二段')).toBe('<p>第一段</p><p>第二段</p>');
  });

  it('标题与分隔线', () => {
    expect(renderMarkdown('## 标题')).toBe('<h2>标题</h2>');
    expect(renderMarkdown('---')).toBe('<hr>');
  });

  it('加粗与行内代码；行内代码内的加粗标记不转换', () => {
    expect(renderMarkdown('**重点**')).toBe('<p><strong>重点</strong></p>');
    expect(renderMarkdown('运行 `npm **test**`')).toBe('<p>运行 <code>npm **test**</code></p>');
  });

  it('连续同类列表项归入同一个列表，与段落互斥', () => {
    expect(renderMarkdown('- 甲\n- 乙')).toBe('<ul><li>甲</li><li>乙</li></ul>');
    expect(renderMarkdown('1. 甲\n2. 乙')).toBe('<ol><li>甲</li><li>乙</li></ol>');
    expect(renderMarkdown('前文\n- 甲')).toBe('<p>前文</p><ul><li>甲</li></ul>');
  });

  it('围栏代码块：内容原样保留且转义，不做行内转换', () => {
    expect(renderMarkdown('```\n<div>**x**\n```'))
      .toBe('<pre><code>&lt;div&gt;**x**</code></pre>');
  });

  it('未闭合代码块（流式中间态）按已有内容渲染', () => {
    expect(renderMarkdown('```\npartial')).toBe('<pre><code>partial</code></pre>');
  });

  it('引用块（转义后的 &gt; 前缀）', () => {
    expect(renderMarkdown('> 引用内容')).toBe('<blockquote>引用内容</blockquote>');
  });

  it('空输入返回空字符串', () => {
    expect(renderMarkdown('')).toBe('');
  });
});
