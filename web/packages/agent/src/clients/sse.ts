/**
 * SSE 流解析器：把 fetch 返回的字节流切分为 data 事件。
 *
 * <p>单独成文件的原因：跨 chunk 分帧是流式协议最容易出错的边界
 * （一个 JSON 事件可能被拆在多个网络 chunk 中），必须独立单元测试覆盖。
 * 遵循 W3C event-stream 语法中浏览器所需的子集：以空行分隔事件，
 * 只提取 data: 行内容。模型网关输出的是框架结构化事件，Parser 不解释 JSON。
 */

/** 有状态的 SSE 增量解析器：feed 追加字节，逐个吐出完整的 data 载荷。 */
export class SseParser {
  private buffer = '';

  /**
   * 喂入一段已解码文本，返回其中所有完整事件的 data 内容（不含 "data:" 前缀）。
   * 不完整的事件保留在内部缓冲区等待下一个 chunk。
   */
  feed(chunk: string): string[] {
    this.buffer += chunk;
    const events: string[] = [];
    for (;;) {
      const boundary = this.findEventBoundary();
      if (boundary == null) {
        return events;
      }
      const rawEvent = this.buffer.slice(0, boundary.index);
      this.buffer = this.buffer.slice(boundary.end);
      const data = extractDataLines(rawEvent);
      if (data != null) {
        events.push(data);
      }
    }
  }

  /**
   * 流关闭时提交最后一个未以空行结尾的事件。
   *
   * <p>正常网关应使用标准空行分隔；该方法只负责完成 SSE 字节语义，
   * 返回的 data 仍会由上层严格校验，不能掩盖残缺 JSON 或未知框架事件。
   */
  end(chunk = ''): string[] {
    const events = this.feed(chunk);
    if (this.buffer.length === 0) {
      return events;
    }
    const data = extractDataLines(this.buffer);
    this.buffer = '';
    return data == null ? events : [...events, data];
  }

  /** 定位下一处分帧边界；同时支持 CRLF 和 LF。 */
  private findEventBoundary(): { index: number; end: number } | null {
    const rn = this.buffer.indexOf('\r\n\r\n');
    const n = this.buffer.indexOf('\n\n');
    if (rn < 0 && n < 0) {
      return null;
    }
    if (rn >= 0 && (n < 0 || rn + 1 <= n)) {
      // \r\n\r\n 优先（其起始位置不晚于 \n\n 时按 CRLF 切）
      return { index: rn, end: rn + 4 };
    }
    return { index: n, end: n + 2 };
  }
}

/** 提取一个事件块里的 data 行并按规范用 \n 连接；非 data 行忽略。 */
function extractDataLines(rawEvent: string): string | null {
  const lines = rawEvent.split(/\r?\n/);
  const dataLines: string[] = [];
  for (const line of lines) {
    if (line.startsWith('data:')) {
      // spec：冒号后允许一个可选空格
      dataLines.push(line.slice(5).replace(/^ /, ''));
    }
  }
  return dataLines.length > 0 ? dataLines.join('\n') : null;
}
