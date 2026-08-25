/**
 * 轻量 Markdown 子集渲染：把模型输出的常见 Markdown 转成安全的 HTML 片段。
 *
 * <p>设计原因：聊天类模型几乎总以 Markdown 组织回答（标题 / 列表 / 加粗 / 代码），
 * 原样平铺会让界面不可读；而引入完整 Markdown 库会破坏 widget 零依赖的定位。
 * 这里只覆盖模型实际会输出的子集（标题、分隔线、无序/有序列表、引用块、
 * 围栏代码块、行内代码、加粗），其余内容按普通段落处理。
 *
 * <p>安全策略：先对全文做 HTML 转义再做结构转换——转换过程只注入本文件
 * 产生的一等 HTML 标签，用户与模型输入中的任何标签都不可能存活。
 * 不支持链接与图片（模型输出的 URL 无法审计，展示纯文本已足够）。
 */

/** 渲染 Markdown 子集为 HTML 片段；空输入返回空字符串。 */
export function renderMarkdown(text: string): string {
  const lines = escapeHtml(text).split('\n');
  const html: string[] = [];
  /** 普通文本行缓冲：连续行合并为一个段落（软换行按 <br> 保留）。 */
  let paragraph: string[] = [];
  /** 列表分组状态：'ul' | 'ol' | null；跨行连续同类项归入同一个列表。 */
  let listOpen: 'ul' | 'ol' | null = null;
  /** 围栏代码块内容；非 null 表示当前正处于代码块内。 */
  let codeLines: string[] | null = null;

  const flushParagraph = (): void => {
    if (paragraph.length > 0) {
      html.push(`<p>${paragraph.join('<br>')}</p>`);
      paragraph = [];
    }
  };
  const closeList = (): void => {
    if (listOpen != null) {
      html.push(`</${listOpen}>`);
      listOpen = null;
    }
  };

  for (const line of lines) {
    if (/^```/.test(line)) {
      if (codeLines != null) {
        html.push(`<pre><code>${codeLines.join('\n')}</code></pre>`);
        codeLines = null;
      } else {
        flushParagraph();
        closeList();
        codeLines = [];
      }
      continue;
    }
    if (codeLines != null) {
      codeLines.push(line);
      continue;
    }

    if (line.trim().length === 0) {
      flushParagraph();
      closeList();
      continue;
    }
    const heading = line.match(/^(#{1,6})\s+(.*)$/);
    if (heading != null) {
      flushParagraph();
      closeList();
      const level = heading[1]!.length;
      html.push(`<h${level}>${inline(heading[2]!)}</h${level}>`);
      continue;
    }
    if (/^(-{3,}|_{3,}|\*{3,})$/.test(line.trim())) {
      flushParagraph();
      closeList();
      html.push('<hr>');
      continue;
    }
    const bullet = line.match(/^[-*]\s+(.*)$/);
    if (bullet != null) {
      flushParagraph();
      if (listOpen !== 'ul') {
        closeList();
        html.push('<ul>');
        listOpen = 'ul';
      }
      html.push(`<li>${inline(bullet[1]!)}</li>`);
      continue;
    }
    const ordered = line.match(/^\d+[.、]\s+(.*)$/);
    if (ordered != null) {
      flushParagraph();
      if (listOpen !== 'ol') {
        closeList();
        html.push('<ol>');
        listOpen = 'ol';
      }
      html.push(`<li>${inline(ordered[1]!)}</li>`);
      continue;
    }
    const quote = line.match(/^&gt;\s?(.*)$/);
    if (quote != null) {
      flushParagraph();
      closeList();
      html.push(`<blockquote>${inline(quote[1]!)}</blockquote>`);
      continue;
    }
    closeList();
    paragraph.push(inline(line));
  }
  if (codeLines != null) {
    // 未闭合的代码块（流式输出的中间态）：按已有内容渲染，等下一帧补全
    html.push(`<pre><code>${codeLines.join('\n')}</code></pre>`);
  }
  flushParagraph();
  closeList();
  return html.join('');
}

/**
 * 行内元素转换：行内代码优先（其内容不再做加粗处理），段内其余部分处理加粗。
 * 输入必须已经过 HTML 转义。
 */
function inline(text: string): string {
  return text
    .split(/(`[^`]+`)/)
    .map(part => {
      const code = part.match(/^`([^`]+)`$/);
      if (code != null) {
        return `<code>${code[1]}</code>`;
      }
      return part.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    })
    .join('');
}

/** HTML 转义：所有进入 innerHTML 的动态文本必须经过此函数。 */
export function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}
