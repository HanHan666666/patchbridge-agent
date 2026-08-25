/**
 * 宿主 HTTP 传输注入测试：确保 Model、Tool、Conversation 三类基础设施请求
 * 共用同一 HttpTransport。该边界使企业宿主能够统一复用鉴权、CSRF 和拦截链。
 */
import { describe, expect, it, vi } from 'vitest';
import { HttpConversationClient } from '../src/clients/conversationClient';
import type { HttpTransport } from '../src/clients/http';
import { HttpModel, type ModelStreamEvent } from '../src/clients/modelClient';
import { HttpToolClient } from '../src/clients/toolClient';

/** 构造只包含框架 message-stop 的合法模型流。 */
function completedSseResponse(): Response {
  const event: ModelStreamEvent = {
    type: 'message-stop',
    stopReason: 'end-turn',
    usage: null,
    modelState: null,
  };
  return new Response(`data: ${JSON.stringify(event)}\n\n`, { status: 200 });
}

describe('HttpTransport 统一注入', () => {
  it('工具、会话和结构化模型流全部经过宿主传输层', async () => {
    const request = vi.fn(async (url: string, init: RequestInit): Promise<Response> => {
      if (url === '/gateway/tools') {
        return new Response('{"tools":[]}');
      }
      if (url === '/gateway/conversations/c-1' && init.method === 'DELETE') {
        return new Response(null, { status: 204 });
      }
      if (url === '/gateway/model/stream') {
        return completedSseResponse();
      }
      throw new Error(`未预期的请求: ${init.method ?? 'GET'} ${url}`);
    });
    const transport: HttpTransport = { request };

    await expect(new HttpToolClient('/gateway/', transport).list()).resolves.toEqual([]);
    await expect(
      new HttpConversationClient('/gateway/', transport).delete('c-1'),
    ).resolves.toBeUndefined();
    const events: ModelStreamEvent[] = [];
    for await (const event of new HttpModel('/gateway/', transport).stream(
      {
        messages: [],
        modelState: null,
        responseMessageId: 'message-1',
        tools: [],
      },
      { traceId: 'trace-1', conversationId: null },
      new AbortController().signal,
    )) {
      events.push(event);
    }

    expect(request).toHaveBeenCalledTimes(3);
    expect(request.mock.calls.map(call => call[0])).toEqual([
      '/gateway/tools',
      '/gateway/conversations/c-1',
      '/gateway/model/stream',
    ]);
    expect(request.mock.calls.every(call => call[1].credentials == null)).toBe(true);
    expect(events).toEqual([{
      type: 'message-stop',
      stopReason: 'end-turn',
      usage: null,
      modelState: null,
    }]);

    const modelBody = JSON.parse(String(request.mock.calls[2]?.[1].body)) as Record<string, unknown>;
    expect(modelBody).toMatchObject({ traceId: 'trace-1', conversationId: null });
    expect(modelBody.request).toMatchObject({ responseMessageId: 'message-1' });
  });
});
