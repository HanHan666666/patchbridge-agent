/**
 * SSE 解析器单元测试：跨 chunk 分帧是流式协议最容易出错的边界，
 * 必须覆盖事件被网络任意切分的场景（设计文档第 21 节“测试作为架构约束”）。
 */
import { describe, expect, it } from 'vitest';
import { SseParser } from '../src/clients/sse';

describe('SseParser', () => {
  it('完整帧一次到达时正确提取 data', () => {
    const parser = new SseParser();
    expect(parser.feed('data: {"a":1}\n\n')).toEqual(['{"a":1}']);
  });

  it('事件跨 chunk 拆分时等待拼接完成', () => {
    const parser = new SseParser();
    expect(parser.feed('data: {"con')).toEqual([]);
    expect(parser.feed('tent":"你')).toEqual([]);
    expect(parser.feed('好"}\n')).toEqual([]);
    expect(parser.feed('\n')).toEqual(['{"content":"你好"}']);
  });

  it('一个 chunk 包含多个事件时全部产出且保持顺序', () => {
    const parser = new SseParser();
    const events = parser.feed('data: one\n\ndata: two\n\ndata: [DONE]\n\n');
    expect(events).toEqual(['one', 'two', '[DONE]']);
  });

  it('CRLF 行结束符与冒号后空格均按规范处理', () => {
    const parser = new SseParser();
    const events = parser.feed('event: message\r\ndata: {"x":true}\r\n\r\n');
    expect(events).toEqual(['{"x":true}']);
  });

  it('非 data 行（注释 / 事件名）被忽略，无 data 的事件不产出', () => {
    const parser = new SseParser();
    expect(parser.feed(': keep-alive\n\n')).toEqual([]);
    expect(parser.feed('event: ping\n\n')).toEqual([]);
    expect(parser.feed('data: real\n\n')).toEqual(['real']);
  });
});
