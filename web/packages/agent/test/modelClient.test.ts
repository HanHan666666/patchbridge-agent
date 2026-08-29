/**
 * HttpModel 测试：验证框架结构化 SSE 的严格解析与流式重试边界。
 *
 * <p>这里故意只发送框架事件，不出现任何模型厂商字段。零事件网络失败可以
 * 重试一次；一旦事件交付给 Runtime，续传位置已经不可证明，必须以标准网络错误结束。
 */
import { readFileSync } from 'node:fs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  HttpModel,
  type ModelCallContext,
  type ModelRequest,
  type ModelStreamEvent,
} from '../src/clients/modelClient';

/** 最小模型请求，显式包含消息、状态、响应消息 ID 和 Tool 定义。 */
const REQUEST: ModelRequest = {
  messages: [],
  modelState: null,
  responseMessageId: 'message-1',
  tools: [],
};

/** 测试链路上下文不参与厂商协议，只随网关请求发送。 */
const CONTEXT: ModelCallContext = {
  traceId: 'trace-1',
  conversationId: null,
};

/** 跨 Java Provider 与 Browser Model 共用的厂商中立契约文件。 */
interface ModelProviderContractFixture {
  /** Fixture 结构版本；变化时必须显式升级读取逻辑。 */
  readonly schemaVersion: number;
  /** 按稳定标识组织的合法结构化事件序列。 */
  readonly cases: readonly {
    /** 契约用例的跨语言稳定标识。 */
    readonly id: string;
    /** Provider 应当交付给 Browser 的完整事件序列。 */
    readonly events: readonly ModelStreamEvent[];
  }[];
}

/** 加载仓库级测试契约；该文件不进入任何生产制品。 */
const PROVIDER_CONTRACT = JSON.parse(readFileSync(
  new URL('../../../../test-fixtures/model-provider-contract-v1.json', import.meta.url),
  'utf8',
)) as ModelProviderContractFixture;

/** 按稳定标识读取共享事件，并在测试启动时拒绝缺失或错版 fixture。 */
function contractEvents(id: string): readonly ModelStreamEvent[] {
  if (PROVIDER_CONTRACT.schemaVersion !== 1) {
    throw new Error(`不支持的 Model Provider 契约版本: ${PROVIDER_CONTRACT.schemaVersion}`);
  }
  const contract = PROVIDER_CONTRACT.cases.find(candidate => candidate.id === id);
  if (contract == null) {
    throw new Error(`Model Provider 契约用例不存在: ${id}`);
  }
  return contract.events;
}

/** 一条完整正文消息所需的共享框架事件。 */
const TEXT_EVENTS = contractEvents('text-reasoning-end-turn');

/** Tool 身份与参数均跨厂商 chunk 分片时应得到的共享框架事件。 */
const TOOL_EVENTS = contractEvents('fragmented-tool-use');

/** 把若干 data 载荷编码成可分片读取的 SSE 响应。 */
function sseResponse(dataFrames: readonly string[]): Response {
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const data of dataFrames) {
        controller.enqueue(new TextEncoder().encode(`data: ${data}\n\n`));
      }
      controller.close();
    },
  });
  return new Response(body, { status: 200 });
}

/** 把结构化事件编码为框架 SSE 响应。 */
function frameworkResponse(events: readonly ModelStreamEvent[]): Response {
  return sseResponse(events.map(event => JSON.stringify(event)));
}

/** 完整消费 AsyncIterable，避免测试退回旧式回调协议。 */
async function collectEvents(stream: AsyncIterable<ModelStreamEvent>): Promise<ModelStreamEvent[]> {
  const events: ModelStreamEvent[] = [];
  for await (const event of stream) {
    events.push(event);
  }
  return events;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('HttpModel 结构化流', () => {
  it('逐事件接受共享的文本与分片 Tool Provider 契约', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(frameworkResponse(TEXT_EVENTS))
      .mockResolvedValueOnce(frameworkResponse(TOOL_EVENTS));
    vi.stubGlobal('fetch', fetchMock);
    const model = new HttpModel('/ai');

    await expect(collectEvents(
      model.stream(REQUEST, CONTEXT, new AbortController().signal),
    )).resolves.toEqual(TEXT_EVENTS);
    await expect(collectEvents(
      model.stream(REQUEST, CONTEXT, new AbortController().signal),
    )).resolves.toEqual(TOOL_EVENTS);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('零事件网络失败只重试一次，并交付唯一一份事件序列', async () => {
    const fetchMock = vi
      .fn()
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(frameworkResponse(TEXT_EVENTS));
    vi.stubGlobal('fetch', fetchMock);

    const events = await collectEvents(
      new HttpModel('/ai').stream(REQUEST, CONTEXT, new AbortController().signal),
    );

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(events).toEqual(TEXT_EVENTS);
  });

  it('响应头成功但首事件前正文断流时仍执行单次重连', async () => {
    const brokenBody = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.error(new TypeError('body stream reset'));
      },
    });
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response(brokenBody, { status: 200 }))
      .mockResolvedValueOnce(frameworkResponse(TEXT_EVENTS));
    vi.stubGlobal('fetch', fetchMock);

    const events = await collectEvents(
      new HttpModel('/ai').stream(REQUEST, CONTEXT, new AbortController().signal),
    );

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(events).toEqual(TEXT_EVENTS);
  });

  it('收到半个 SSE 帧但尚未交付框架事件时仍允许单次重连', async () => {
    const partialFrame = `data: ${JSON.stringify(TEXT_EVENTS[0]).slice(0, 24)}`;
    const brokenBody = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new TextEncoder().encode(partialFrame));
        setTimeout(() => controller.error(new TypeError('partial frame reset')), 10);
      },
    });
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response(brokenBody, { status: 200 }))
      .mockResolvedValueOnce(frameworkResponse(TEXT_EVENTS));
    vi.stubGlobal('fetch', fetchMock);

    const events = await collectEvents(
      new HttpModel('/ai').stream(REQUEST, CONTEXT, new AbortController().signal),
    );

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(events).toEqual(TEXT_EVENTS);
  });

  it('第二次网络尝试仍失败时抛标准错误，不再继续请求', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));
    vi.stubGlobal('fetch', fetchMock);

    await expect(collectEvents(
      new HttpModel('/ai').stream(REQUEST, CONTEXT, new AbortController().signal),
    )).rejects.toMatchObject({ code: 'NETWORK_ERROR' });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('已交付事件后读流失败不重试，避免重复产生半截消息', async () => {
    const first = TEXT_EVENTS[0];
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new TextEncoder().encode(
          `data: ${JSON.stringify(first)}\n\n`,
        ));
        setTimeout(() => controller.error(new TypeError('network changed')), 10);
      },
    });
    const fetchMock = vi.fn().mockResolvedValue(new Response(body, { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    const delivered: ModelStreamEvent[] = [];
    const consume = async (): Promise<void> => {
      for await (const event of new HttpModel('/ai').stream(
        REQUEST,
        CONTEXT,
        new AbortController().signal,
      )) {
        delivered.push(event);
      }
    };

    await expect(consume()).rejects.toMatchObject({ code: 'NETWORK_ERROR' });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(delivered).toEqual([first]);
  });

  it('message-stop 主动结束 reader，延迟 EOF 与迟到清理错误不推翻结果', async () => {
    let cancelCalls = 0;
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        for (const event of TEXT_EVENTS) {
          controller.enqueue(new TextEncoder().encode(
            `data: ${JSON.stringify(event)}\n\n`,
          ));
        }
      },
      cancel() {
        cancelCalls += 1;
        return Promise.reject(new TypeError('message-stop 后的迟到网络错误'));
      },
    });
    const fetchMock = vi.fn().mockResolvedValue(new Response(body, { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    const events = await collectEvents(
      new HttpModel('/ai').stream(REQUEST, CONTEXT, new AbortController().signal),
    );

    expect(events).toEqual(TEXT_EVENTS);
    expect(cancelCalls).toBe(1);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('message-stop 不等待永不结束的 reader 取消清理', async () => {
    let cancelCalls = 0;
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        for (const event of TEXT_EVENTS) {
          controller.enqueue(new TextEncoder().encode(
            `data: ${JSON.stringify(event)}\n\n`,
          ));
        }
      },
      cancel() {
        cancelCalls += 1;
        return new Promise<void>(() => {});
      },
    });
    const fetchMock = vi.fn().mockResolvedValue(new Response(body, { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    const events = await collectEvents(
      new HttpModel('/ai').stream(REQUEST, CONTEXT, new AbortController().signal),
    );

    expect(events).toEqual(TEXT_EVENTS);
    expect(cancelCalls).toBe(1);
  });

  it('非法 JSON、未知事件和缺字段的已知事件都显式报 MODEL_PROTOCOL_ERROR', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(sseResponse(['not-json']))
      .mockResolvedValueOnce(sseResponse([JSON.stringify({ type: 'vendor-delta' })]))
      .mockResolvedValueOnce(sseResponse([JSON.stringify({ type: 'block-start' })]));
    vi.stubGlobal('fetch', fetchMock);
    const model = new HttpModel('/ai');

    await expect(collectEvents(
      model.stream(REQUEST, CONTEXT, new AbortController().signal),
    )).rejects.toMatchObject({ code: 'MODEL_PROTOCOL_ERROR', retryable: false });
    await expect(collectEvents(
      model.stream(REQUEST, CONTEXT, new AbortController().signal),
    )).rejects.toMatchObject({ code: 'MODEL_PROTOCOL_ERROR', retryable: false });
    await expect(collectEvents(
      model.stream(REQUEST, CONTEXT, new AbortController().signal),
    )).rejects.toMatchObject({ code: 'MODEL_PROTOCOL_ERROR', retryable: false });
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('顶层与嵌套结构出现额外字段均显式报 MODEL_PROTOCOL_ERROR', async () => {
    // 顶层额外字段：各事件类型的精确 key 集合之外不允许任何 vendor 字段
    const topLevelExtraFrames = [
      { ...TEXT_EVENTS[0], vendor: 'openai' },
      { ...TEXT_EVENTS[1], extra: 1 },
      { ...TEXT_EVENTS[2], extra: 1 },
      { ...TEXT_EVENTS[3], provider: 'anthropic' },
      { type: 'error', error: { code: 'X', message: 'm', retryable: false }, extra: 1 },
    ];
    // 嵌套额外字段：block / delta / usage / modelState / error 同样执行精确校验
    const nestedExtraFrames = [
      { type: 'block-start', index: 0, block: { type: 'text', vendor: 'x' } },
      {
        type: 'block-delta',
        index: 0,
        delta: { type: 'text', text: 'a', vendor: 'x' },
      },
      {
        type: 'message-stop',
        stopReason: 'end-turn',
        usage: { inputTokens: 1, outputTokens: 1, totalTokens: 2, vendorTokens: 1 },
        modelState: null,
      },
      {
        type: 'message-stop',
        stopReason: 'end-turn',
        usage: { inputTokens: 1.5, outputTokens: 1, totalTokens: 2.5 },
        modelState: null,
      },
      {
        type: 'message-stop',
        stopReason: 'end-turn',
        usage: null,
        modelState: { format: 'f', data: null, vendorState: {} },
      },
      {
        type: 'error',
        error: { code: 'X', message: 'm', retryable: false, vendorDetail: 'd' },
      },
    ];
    const frames = [...topLevelExtraFrames, ...nestedExtraFrames];
    const fetchMock = vi.fn();
    for (const frame of frames) {
      fetchMock.mockResolvedValueOnce(sseResponse([JSON.stringify(frame)]));
    }
    vi.stubGlobal('fetch', fetchMock);
    const model = new HttpModel('/ai');

    for (let i = 0; i < frames.length; i += 1) {
      await expect(collectEvents(
        model.stream(REQUEST, CONTEXT, new AbortController().signal),
      )).rejects.toMatchObject({ code: 'MODEL_PROTOCOL_ERROR', retryable: false });
    }
    expect(fetchMock).toHaveBeenCalledTimes(frames.length);
  });

  it('流内 error 事件直接抛出服务端标准错误', async () => {
    const error = {
      code: 'MODEL_RATE_LIMITED',
      message: '模型请求过于频繁',
      retryable: true,
    };
    const fetchMock = vi.fn().mockResolvedValue(sseResponse([
      JSON.stringify({ type: 'error', error }),
    ]));
    vi.stubGlobal('fetch', fetchMock);

    await expect(collectEvents(
      new HttpModel('/ai').stream(REQUEST, CONTEXT, new AbortController().signal),
    )).rejects.toEqual(error);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('HTTP 错误与用户中止都不进入重试', async () => {
    const unauthorized = vi.fn().mockResolvedValue(new Response('{}', { status: 401 }));
    vi.stubGlobal('fetch', unauthorized);
    await expect(collectEvents(
      new HttpModel('/ai').stream(REQUEST, CONTEXT, new AbortController().signal),
    )).rejects.toMatchObject({ code: 'AUTH_REQUIRED' });
    expect(unauthorized).toHaveBeenCalledTimes(1);

    const abortError = new Error('aborted');
    abortError.name = 'AbortError';
    const aborted = vi.fn().mockRejectedValue(abortError);
    vi.stubGlobal('fetch', aborted);
    await expect(collectEvents(
      new HttpModel('/ai').stream(REQUEST, CONTEXT, new AbortController().signal),
    )).rejects.toMatchObject({ name: 'AbortError' });
    expect(aborted).toHaveBeenCalledTimes(1);
  });
});
