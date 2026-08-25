/**
 * requestJson 传输级重试单元测试：核心约束是“只有幂等的 GET 允许重试一次”。
 * 重试背景（浏览器中止 SSE 后连接池派发死 socket）见 http.ts 注释；
 * 策略一旦越界（重试非幂等请求）会造成工具重复执行等副作用。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  normalizeEndpoint,
  requestJson,
  requireArrayField,
} from '../src/clients/http';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('requestJson 传输级重试', () => {
  it('默认传输使用 same-origin 凭据策略', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('{"ok":true}'));
    vi.stubGlobal('fetch', fetchMock);

    await requestJson('/ai/tools', { method: 'GET' });

    expect(fetchMock).toHaveBeenCalledWith('/ai/tools', expect.objectContaining({
      credentials: 'same-origin',
    }));
  });

  it('GET 网络失败重试一次成功', async () => {
    const fetchMock = vi
      .fn()
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(new Response('{"ok":true}'));
    vi.stubGlobal('fetch', fetchMock);

    await expect(requestJson('/ai/tools', { method: 'GET' })).resolves.toEqual({ ok: true });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('缺省 method 视为 GET，同样重试', async () => {
    const fetchMock = vi
      .fn()
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(new Response('[]'));
    vi.stubGlobal('fetch', fetchMock);

    await expect(requestJson('/ai/conversations', {})).resolves.toEqual([]);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('POST 网络失败不重试（无法证明服务端未执行）', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));
    vi.stubGlobal('fetch', fetchMock);

    await expect(
      requestJson('/ai/tools/call', { method: 'POST', body: '{}' }),
    ).rejects.toMatchObject({ code: 'NETWORK_ERROR' });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('GET 的 HTTP 状态错误不属于传输失败，不重试', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('{}', { status: 500 }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(requestJson('/ai/tools', { method: 'GET' }))
      .rejects.toMatchObject({ code: 'MODEL_FAILED' });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('204 成功响应不解析 JSON，并按 void 返回', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(requestJson<void>('/ai/conversations/c-1', {
      method: 'DELETE',
    })).resolves.toBeUndefined();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

describe('HTTP 协议边界校验', () => {
  it('统一移除 API 根路径尾斜杠', () => {
    expect(normalizeEndpoint('/ai///')).toBe('/ai');
    expect(normalizeEndpoint('/')).toBe('');
  });

  it('列表字段缺失时显式失败，不伪造空数据', () => {
    expect(() => requireArrayField(undefined, 'tools'))
      .toThrow('服务端响应缺少数组字段 tools');
  });
});
