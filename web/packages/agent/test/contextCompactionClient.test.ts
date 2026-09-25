/**
 * 上下文压缩 HTTP Adapter 契约测试。
 *
 * <p>配置和摘要结果都会直接驱动模型上下文，因此不可信响应必须精确校验，POST
 * 还必须保留链路字段与取消信号且绝不由公共 HTTP 层重试。
 */
import { describe, expect, it, vi } from 'vitest';
import { HttpContextCompactionGateway } from '../src/clients/contextCompactionClient';
import type { HttpTransport } from '../src/clients/http';
import { TEST_TARGET } from './testContext';

/** 服务端目录里的当前目标窗口。 */
function catalog(configuration: unknown): unknown {
  return { targets: [{ ref: TEST_TARGET, configuration }] };
}

/** 返回固定 JSON 并可观察请求的测试传输。 */
function transportWith(body: unknown): HttpTransport & { request: ReturnType<typeof vi.fn> } {
  return {
    request: vi.fn(async () => new Response(JSON.stringify(body), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })),
  };
}

describe('HttpContextCompactionGateway', () => {
  it('读取服务端唯一窗口配置并校验 80% 派生值的边界关系', async () => {
    const transport = transportWith(catalog({
      contextWindowTokens: 128_000,
      automaticThresholdTokens: 102_400,
      keepRecentTokens: 20_000,
      reservedOutputTokens: 12_800,
    }));
    const gateway = new HttpContextCompactionGateway('/ai/', transport);

    await expect(gateway.configuration(TEST_TARGET)).resolves.toEqual({
      contextWindowTokens: 128_000,
      automaticThresholdTokens: 102_400,
      keepRecentTokens: 20_000,
      reservedOutputTokens: 12_800,
    });
    expect(transport.request).toHaveBeenCalledWith('/ai/model/targets',
      expect.objectContaining({ method: 'GET' }));
  });

  it('拒绝缺字段、额外字段和越过自动阈值的近期预算', async () => {
    const invalidBodies = [
      { contextWindowTokens: 1_000, automaticThresholdTokens: 800 },
      {
        contextWindowTokens: 1_000,
        automaticThresholdTokens: 800,
        keepRecentTokens: 200,
        thresholdRatio: 0.8,
      },
      {
        contextWindowTokens: 1_000,
        automaticThresholdTokens: 800,
        keepRecentTokens: 800,
      },
      {
        contextWindowTokens: 1_000,
        automaticThresholdTokens: 800,
        keepRecentTokens: 200,
        reservedOutputTokens: 300,
      },
    ];

    for (const body of invalidBodies) {
      const gateway = new HttpContextCompactionGateway('/ai', transportWith(catalog(body)));
      await expect(gateway.configuration(TEST_TARGET), JSON.stringify(body)).rejects.toThrow();
    }
  });

  it('POST 传递精确压缩信封并严格校验摘要、usage 与 ModelState', async () => {
    const transport = transportWith({
      summary: '上下文摘要',
      usage: { inputTokens: 40, outputTokens: 10, totalTokens: 50 },
      modelState: { format: 'provider/v1', data: { retained: true } },
    });
    const gateway = new HttpContextCompactionGateway('/ai', transport);
    const abortController = new AbortController();

    await expect(gateway.compact({
      modelTarget: TEST_TARGET,
      trigger: 'manual',
      messagesToSummarize: [],
      retainedMessages: [],
      previousSummary: null,
      modelState: null,
      responseMessageId: 'summary-1',
      splitTurn: false,
    }, {
      traceId: 'trace-1',
      conversationId: 'conversation-1',
    }, abortController.signal)).resolves.toEqual({
      summary: '上下文摘要',
      usage: { inputTokens: 40, outputTokens: 10, totalTokens: 50 },
      modelState: { format: 'provider/v1', data: { retained: true } },
    });

    const init = transport.request.mock.calls[0]?.[1] as RequestInit;
    expect(transport.request.mock.calls[0]?.[0]).toBe('/ai/model/compact');
    expect(init.method).toBe('POST');
    expect(init.signal).toBe(abortController.signal);
    expect(JSON.parse(String(init.body))).toMatchObject({
      traceId: 'trace-1',
      conversationId: 'conversation-1',
      request: { trigger: 'manual', responseMessageId: 'summary-1' },
    });
  });
});
