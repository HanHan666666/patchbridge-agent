/**
 * WebMCP Adapter 契约测试：以浏览器 ModelContext 的最小假实现验证发现、执行、
 * toolchange 同步、错误上报与生命周期清理，不使用 polyfill 或真实浏览器实验开关。
 */
import { describe, expect, it, vi } from 'vitest';
import { DefaultToolRegistry } from '@patchbridge-agent/agent';
import type { JsonObject } from '@patchbridge-agent/agent';
import {
  connectDocumentWebMcp,
  WebMcpAdapter,
  WebMcpUnavailableError,
} from '../src/index';
import type {
  WebMcpModelContext,
  WebMcpRegisteredTool,
} from '../src/index';

/** 可变 ModelContext，保留浏览器 RegisteredTool 对象身份以验证执行路由。
 *  executeTool 按 Chrome 预览实现的字符串方言实现：入参是 JSON 字符串。 */
class FakeModelContext extends EventTarget implements WebMcpModelContext {
  public tools: readonly WebMcpRegisteredTool[] = [];
  public failure: Error | null = null;
  public readonly executions: Array<{
    tool: WebMcpRegisteredTool;
    input: string;
    signal?: AbortSignal;
  }> = [];

  async getTools(): Promise<readonly WebMcpRegisteredTool[]> {
    if (this.failure != null) {
      throw this.failure;
    }
    return this.tools;
  }

  async executeTool(
    tool: WebMcpRegisteredTool,
    input: string = '{}',
    options?: { signal?: AbortSignal },
  ): Promise<string> {
    this.executions.push({ tool, input, signal: options?.signal });
    return JSON.stringify({ selected: (JSON.parse(input) as JsonObject)['id'] });
  }
}

/** 创建符合当前 WebMCP 草案的 RegisteredTool。 */
function registeredTool(
  name: string,
  readOnlyHint: boolean,
): WebMcpRegisteredTool {
  return {
    name,
    title: '选择设备',
    description: '在当前页面选择指定设备',
    inputSchema: {
      type: 'object',
      properties: { id: { type: 'string' } },
      required: ['id'],
    },
    window: {},
    origin: 'https://example.test',
    annotations: { readOnlyHint, untrustedContentHint: true },
  };
}

/** Tool 调用的最小链路上下文。 */
const callContext = {
  traceId: 'trace-webmcp',
  conversationId: null,
  toolCallId: 'call-webmcp',
};

describe('WebMcpAdapter', () => {
  it('发现结果进入统一 Registry，执行时回传原 RegisteredTool 与 AbortSignal', async () => {
    const registry = new DefaultToolRegistry();
    const modelContext = new FakeModelContext();
    const sourceTool = registeredTool('page.select_device', false);
    modelContext.tools = [sourceTool];
    const adapter = new WebMcpAdapter(registry, modelContext, {
      onRefreshError: cause => { throw cause; },
    });

    const snapshot = await adapter.start();
    expect(snapshot.tools).toEqual([expect.objectContaining({
      name: 'page.select_device',
      source: 'WEBMCP',
      annotations: expect.objectContaining({
        requireConfirmation: true,
        untrustedContentHint: true,
      }),
    })]);

    const abort = new AbortController();
    await expect(snapshot.invoke(
      'page.select_device',
      { id: 'DEV-7' },
      callContext,
      abort.signal,
    )).resolves.toEqual({
      toolCallId: 'call-webmcp',
      content: JSON.stringify({ selected: 'DEV-7' }),
      isError: false,
    });
    expect(modelContext.executions[0]).toEqual({
      tool: sourceTool,
      input: '{"id":"DEV-7"}',
      signal: abort.signal,
    });
    adapter.stop();
  });

  it('Chrome 字符串方言：schema 字符串还原为对象，执行参数序列化后传出', async () => {
    const registry = new DefaultToolRegistry();
    const modelContext = new FakeModelContext();
    modelContext.tools = [{
      ...registeredTool('page.open_tools_tab', false),
      inputSchema: '{"type":"object","additionalProperties":false}',
    }];
    const adapter = new WebMcpAdapter(registry, modelContext, {
      onRefreshError: cause => { throw cause; },
    });

    const snapshot = await adapter.start();
    // 必须还原成真实 Schema 对象；直接透传字符串会被展开成字符索引对象
    expect(snapshot.tools[0]?.inputSchema)
      .toEqual({ type: 'object', additionalProperties: false });

    await snapshot.invoke('page.open_tools_tab', {}, callContext);
    expect(modelContext.executions[0]?.input).toBe('{}');
    adapter.stop();
  });

  it('inputSchema 字符串不是合法 JSON 时按发现错误失败，不静默兜底', async () => {
    const registry = new DefaultToolRegistry();
    const modelContext = new FakeModelContext();
    modelContext.tools = [{
      ...registeredTool('page.broken', true),
      inputSchema: '{"type":"object"',
    }];
    const errors: unknown[] = [];
    const adapter = new WebMcpAdapter(registry, modelContext, {
      onRefreshError: cause => errors.push(cause),
    });

    await expect(adapter.start()).rejects.toThrow(/inputSchema/);
    expect(registry.snapshot().tools).toEqual([]);
    adapter.stop();
  });

  it('toolchange 自动刷新同一 Provider，stop 后事件不再改变目录', async () => {
    const registry = new DefaultToolRegistry();
    const modelContext = new FakeModelContext();
    modelContext.tools = [registeredTool('page.old', true)];
    const errors: unknown[] = [];
    const adapter = new WebMcpAdapter(registry, modelContext, {
      onRefreshError: cause => errors.push(cause),
    });
    await adapter.start();

    modelContext.tools = [registeredTool('page.new', true)];
    modelContext.dispatchEvent(new Event('toolchange'));
    await vi.waitFor(() => {
      expect(registry.snapshot().tools.map(tool => tool.name)).toEqual(['page.new']);
    });

    adapter.stop();
    modelContext.tools = [registeredTool('page.after_stop', true)];
    modelContext.dispatchEvent(new Event('toolchange'));
    await Promise.resolve();
    expect(registry.snapshot().tools).toEqual([]);
    expect(errors).toEqual([]);
  });

  it('toolchange 刷新错误交给宿主且保留上一次成功目录', async () => {
    const registry = new DefaultToolRegistry();
    const modelContext = new FakeModelContext();
    modelContext.tools = [registeredTool('page.stable', true)];
    const errors: unknown[] = [];
    const adapter = new WebMcpAdapter(registry, modelContext, {
      onRefreshError: cause => errors.push(cause),
    });
    const stable = await adapter.start();

    const failure = new Error('WebMCP discovery failed');
    modelContext.failure = failure;
    modelContext.dispatchEvent(new Event('toolchange'));
    await vi.waitFor(() => expect(errors).toEqual([failure]));
    expect(registry.snapshot()).toBe(stable);
    adapter.stop();
  });

  it('Document 不支持 WebMCP 时明确失败，不创建空 Adapter 冒充成功', async () => {
    await expect(connectDocumentWebMcp(
      new DefaultToolRegistry(),
      {},
      { onRefreshError: cause => { throw cause; } },
    )).rejects.toBeInstanceOf(WebMcpUnavailableError);
  });
});
