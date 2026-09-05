/**
 * Unified Tool Registry 契约测试：覆盖页面 Tool 生命周期、来源冲突、原子刷新与
 * 执行快照隔离。这里验证的是 Agent 能力边界，不依赖 DOM 或真实后端。
 */
import { describe, expect, it } from 'vitest';
import {
  BackendToolProvider,
  DefaultToolRegistry,
  ToolAlreadyRegisteredError,
} from '../src/toolRegistry';
import { HttpToolClient } from '../src/clients/toolClient';
import type { ToolClient, ToolCallContext } from '../src/clients/toolClient';
import type { HttpTransport } from '../src/clients/http';
import type {
  BrowserToolProvider,
  ExecutableTool,
} from '../src/toolRegistry';
import type { ToolSource } from '../src/types';

/** 创建可观察的 Provider Tool，便于区分不同 revision 绑定的执行器。 */
function executable(
  name: string,
  content: string,
  source: ToolSource = 'MCP',
): ExecutableTool {
  return {
    definition: {
      name,
      title: name,
      description: `${name} 的测试定义`,
      inputSchema: { type: 'object' },
      annotations: null,
      source,
      permissions: [],
    },
    invoke: async (_arguments, context) => ({
      toolCallId: context.toolCallId,
      content,
      isError: false,
    }),
  };
}

/** 可变 Provider 只用于验证一次完整 load 的原子替换语义。 */
class MutableProvider implements BrowserToolProvider {
  public tools: readonly ExecutableTool[] = [];
  public failure: Error | null = null;

  constructor(public readonly id: string) {}

  async load(): Promise<readonly ExecutableTool[]> {
    if (this.failure != null) {
      throw this.failure;
    }
    return this.tools;
  }
}

/** 受控 Provider 允许测试主动决定两次并发刷新完成的先后顺序。 */
class DeferredProvider implements BrowserToolProvider {
  public readonly id = 'deferred';
  public readonly resolvers: Array<(tools: readonly ExecutableTool[]) => void> = [];

  load(): Promise<readonly ExecutableTool[]> {
    return new Promise(resolve => this.resolvers.push(resolve));
  }
}

/** Tool 调用的最小可信链路上下文。 */
const context = {
  traceId: 'trace-1',
  conversationId: 'conversation-1',
  toolCallId: 'call-1',
};

describe('DefaultToolRegistry', () => {
  it('页面 Tool 注册、执行与注销都通过同一份可观察目录', async () => {
    const registry = new DefaultToolRegistry();
    const revisions: number[] = [];
    const unsubscribe = registry.subscribe(snapshot => revisions.push(snapshot.revision));
    let executions = 0;
    const registration = registry.register({
      name: 'frontend.current_page',
      title: '当前页面',
      description: '读取当前页面业务上下文',
      inputSchema: {
        type: 'object',
        additionalProperties: false,
        properties: {
          section: { type: 'string', enum: ['devices', 'sensors'] },
          limit: { type: ['integer', 'null'] },
        },
        required: ['section'],
      },
      execute: async (arguments_, executionContext) => {
        executions += 1;
        return {
          arguments: arguments_,
          traceId: executionContext.traceId,
          aborted: executionContext.signal?.aborted ?? false,
        };
      },
    });

    const snapshot = registry.snapshot();
    expect(snapshot.tools).toEqual([expect.objectContaining({
      name: 'frontend.current_page',
      source: 'FRONTEND_LOCAL',
      permissions: [],
      annotations: expect.objectContaining({ requireConfirmation: true }),
    })]);
    expect(Object.isFrozen(snapshot.tools[0]?.inputSchema)).toBe(true);
    expect(Object.isFrozen(snapshot.tools[0]?.permissions)).toBe(true);
    await expect(snapshot.invoke(
      'frontend.current_page',
      { section: 'devices' },
      context,
      new AbortController().signal,
    )).resolves.toEqual({
      toolCallId: 'call-1',
      content: JSON.stringify({
        arguments: { section: 'devices' },
        traceId: 'trace-1',
        aborted: false,
      }),
      isError: false,
    });
    expect(executions).toBe(1);

    registration.dispose();
    registration.dispose();
    expect(registry.snapshot().tools).toEqual([]);
    expect(revisions).toEqual([0, 1, 2]);
    unsubscribe();
  });

  it('页面 Tool 仅声明只读即可免确认，未声明只读的写能力默认需要确认', () => {
    const registry = new DefaultToolRegistry();
    registry.register({
      name: 'frontend.read',
      description: '只读页面状态',
      inputSchema: { type: 'object' },
      annotations: { readOnlyHint: true },
      execute: () => 'read',
    });
    registry.register({
      name: 'frontend.write',
      description: '修改页面状态',
      inputSchema: { type: 'object' },
      execute: () => 'write',
    });

    const byName = new Map(registry.snapshot().tools.map(tool => [tool.name, tool]));
    expect(byName.get('frontend.read')?.annotations?.requireConfirmation).toBe(false);
    expect(byName.get('frontend.write')?.annotations?.requireConfirmation).toBe(true);
  });

  it('非法参数在执行边界被拒绝，execute 不被调用', async () => {
    const registry = new DefaultToolRegistry();
    let executions = 0;
    registry.register({
      name: 'frontend.device_restart',
      description: '重启指定设备',
      inputSchema: {
        type: 'object',
        additionalProperties: false,
        properties: {
          serial: { type: 'string' },
          force: { type: 'boolean' },
          tags: { type: 'array', items: { type: 'string' } },
          target: {
            type: 'object',
            properties: { rack: { type: 'string' } },
            required: ['rack'],
            additionalProperties: false,
          },
          retries: { type: ['integer', 'null'] },
        },
        required: ['serial'],
      },
      execute: () => {
        executions += 1;
        return 'ok';
      },
    });
    const snapshot = registry.snapshot();

    const cases: ReadonlyArray<readonly [string, Record<string, unknown>, RegExp]> = [
      ['缺少必填参数', { force: true }, /缺少必填参数/],
      ['类型不匹配', { serial: 108, force: true }, /类型必须是 string/],
      ['枚举外取值', { serial: 'DEV-1', force: 'yes' }, /类型必须是 boolean/],
      ['未声明参数', { serial: 'DEV-1', extra: 1 }, /未声明的参数/],
      ['数组元素类型', { serial: 'DEV-1', tags: ['a', 2] }, /类型必须是 string/],
      ['嵌套对象必填', { serial: 'DEV-1', target: {} }, /缺少必填参数/],
      ['嵌套对象额外参数', { serial: 'DEV-1', target: { rack: 'A', slot: 3 } }, /未声明的参数/],
      ['integer 不接受小数', { serial: 'DEV-1', retries: 1.5 }, /类型必须是 integer \| null/],
    ];
    for (const [caseName, arguments_, pattern] of cases) {
      const result = await snapshot.invoke(
        'frontend.device_restart',
        arguments_,
        context,
      );
      expect(result.isError, caseName).toBe(true);
      expect(result.content, caseName).toMatch(/参数校验失败/);
      expect(result.content, caseName).toMatch(pattern);
    }
    expect(executions).toBe(0);

    // 合法参数（含联合类型 null 与嵌套对象）正常执行。
    await expect(snapshot.invoke(
      'frontend.device_restart',
      { serial: 'DEV-1', retries: null, tags: ['a'], target: { rack: 'A' } },
      context,
    )).resolves.toMatchObject({ isError: false, content: 'ok' });
    expect(executions).toBe(1);
  });

  it('未声明 additionalProperties 的 Schema 允许额外参数（JSON Schema 默认语义）', async () => {
    const registry = new DefaultToolRegistry();
    let executed = false;
    registry.register({
      name: 'frontend.flexible',
      description: '允许扩展参数的页面 Tool',
      inputSchema: {
        type: 'object',
        properties: { keyword: { type: 'string' } },
        required: ['keyword'],
      },
      execute: arguments_ => JSON.stringify(arguments_),
    });
    const snapshot = registry.snapshot();
    const result = await snapshot.invoke(
      'frontend.flexible',
      { keyword: 'temp', extra: 'forwarded' },
      context,
    );
    expect(result.isError).toBe(false);
    expect(result.content).toContain('extra');
    expect(executed).toBe(false);
    executed = true;
    expect(executed).toBe(true);
  });

  it('注册期拒绝不受支持的 Schema 结构', () => {
    const registry = new DefaultToolRegistry();
    const tool = (inputSchema: Record<string, unknown>) => ({
      name: 'frontend.bad_schema',
      description: '非法 Schema 示例',
      inputSchema,
      execute: () => 'never',
    });
    expect(() => registry.register(tool({ type: 'object', properties: { a: { type: 'string', pattern: '^a' } } })))
      .toThrow(/不受支持的关键字 "pattern"/);
    expect(() => registry.register(tool({ type: 'string' })))
      .toThrow(/根类型必须是 object/);
    expect(() => registry.register(tool({ type: 'object', properties: { a: { enum: ['x'] } } })))
      .toThrow(/缺少 "type" 声明/);
    expect(() => registry.register(tool({ type: 'object', additionalProperties: 'strict' })))
      .toThrow(/additionalProperties 只支持布尔值/);
    expect(() => registry.register(tool({ type: 'object', properties: { a: { type: 'string', enum: [] } } })))
      .toThrow(/enum 必须是非空数组/);
    expect(() => registry.register(tool({ type: 'object', properties: { a: { type: 'tuple' } } })))
      .toThrow(/type 取值不受支持/);
    expect(() => registry.register(tool({ type: 'object', items: { type: 'string' } })))
      .toThrow(/声明了 "items"，但类型不包含 array/);
    expect(() => registry.register(tool({ type: 'array', items: { type: 'string' } })))
      .toThrow(/根类型必须是 object/);
    expect(registry.snapshot().tools).toEqual([]);
  });

  it('拒绝不符合 Function Calling 约束的页面 Tool 名称', () => {
    const registry = new DefaultToolRegistry();
    expect(() => registry.register({
      name: 'frontend invalid name',
      description: '名称包含空格',
      inputSchema: { type: 'object' },
      execute: () => 'never',
    })).toThrow('Tool name 必须是 1-128 位');
    expect(registry.snapshot().tools).toEqual([]);
  });

  it('跨来源重名必须显式失败，Provider 刷新失败时旧快照保持不变', async () => {
    const registry = new DefaultToolRegistry();
    const provider = new MutableProvider('mcp');
    const mounted = registry.addProvider(provider);
    provider.tools = [executable('inventory.lookup', 'v1')];
    const stable = await mounted.refresh();

    expect(() => registry.register({
      name: 'inventory.lookup',
      description: '与 MCP 重名的页面 Tool',
      inputSchema: { type: 'object' },
      execute: () => 'duplicate',
    })).toThrow(ToolAlreadyRegisteredError);

    registry.register({
      name: 'frontend.selected_device',
      description: '读取前端选中设备',
      inputSchema: { type: 'object' },
      execute: () => 'DEV-001',
    });
    const beforeConflict = registry.snapshot();
    provider.tools = [executable('frontend.selected_device', '冲突')];
    await expect(mounted.refresh()).rejects.toBeInstanceOf(ToolAlreadyRegisteredError);
    expect(registry.snapshot()).toBe(beforeConflict);

    provider.failure = new Error('MCP discovery failed');
    await expect(mounted.refresh()).rejects.toThrow('MCP discovery failed');
    expect(registry.snapshot()).toBe(beforeConflict);
    expect(stable.tools.map(tool => tool.name)).toEqual(['inventory.lookup']);
  });

  it('旧 revision 永远绑定旧执行器，同名 Tool 重新注册不会改变在途调用', async () => {
    const registry = new DefaultToolRegistry();
    const oldRegistration = registry.register({
      name: 'frontend.versioned',
      description: '返回当前页面版本',
      inputSchema: { type: 'object' },
      execute: () => 'old',
    });
    const oldSnapshot = registry.snapshot();

    oldRegistration.dispose();
    registry.register({
      name: 'frontend.versioned',
      description: '返回当前页面版本',
      inputSchema: { type: 'object' },
      execute: () => 'new',
    });

    await expect(oldSnapshot.invoke('frontend.versioned', {}, context))
      .resolves.toMatchObject({ content: 'old' });
    await expect(registry.snapshot().invoke('frontend.versioned', {}, context))
      .resolves.toMatchObject({ content: 'new' });
  });

  it('同一 Provider 并发刷新采用 latest-request-wins，慢结果不能覆盖新目录', async () => {
    const registry = new DefaultToolRegistry();
    const provider = new DeferredProvider();
    const mounted = registry.addProvider(provider);
    const slow = mounted.refresh();
    const latest = mounted.refresh();

    provider.resolvers[1]?.([executable('mcp.latest', 'latest')]);
    const latestSnapshot = await latest;
    provider.resolvers[0]?.([executable('mcp.stale', 'stale')]);
    const staleResult = await slow;

    expect(latestSnapshot.tools.map(tool => tool.name)).toEqual(['mcp.latest']);
    expect(staleResult).toBe(latestSnapshot);
    expect(registry.snapshot()).toBe(latestSnapshot);
  });

  it('Provider 释放后不再发布其 Tool，空 Provider 挂载与释放不制造假 revision', async () => {
    const registry = new DefaultToolRegistry();
    const empty = new MutableProvider('empty');
    const emptyMount = registry.addProvider(empty);
    expect(registry.snapshot().revision).toBe(0);
    emptyMount.dispose();
    expect(registry.snapshot().revision).toBe(0);

    const provider = new MutableProvider('openapi');
    provider.tools = [executable('orders.get', 'ok', 'OPENAPI')];
    const mounted = registry.addProvider(provider);
    await mounted.refresh();
    mounted.dispose();
    expect(registry.snapshot().tools).toEqual([]);
  });

  it('VA-01：BackendToolProvider 把发现时的版本引用贯穿到调用请求', async () => {
    const calls: Array<{
      name: string;
      version: string | null;
      context: ToolCallContext;
    }> = [];
    const definition = {
      name: 'mcp.srv.action',
      title: 'action',
      description: '远端动作',
      inputSchema: { type: 'object' } as const,
      annotations: null,
      source: 'MCP' as ToolSource,
      permissions: [] as readonly string[],
      version: 'route-v1',
    };
    const client: ToolClient = {
      list: async () => [definition],
      call: async (name, definitionVersion, _arguments, context) => {
        calls.push({ name, version: definitionVersion, context });
        return {
          toolCallId: context.toolCallId,
          content: 'ok',
          isError: false,
        };
      },
    };
    const registry = new DefaultToolRegistry([new BackendToolProvider(client)]);
    const firstSnapshot = await registry.refresh();
    const loadedVersion = firstSnapshot.tools[0]?.version;
    expect(loadedVersion).toBe('route-v1');

    const invocation = firstSnapshot.invoke('mcp.srv.action', {}, {
      traceId: 'trace-1',
      conversationId: null,
      toolCallId: 'call-1',
    });
    await expect(invocation).resolves.toMatchObject({ content: 'ok' });
    expect(calls[0]).toMatchObject({ name: 'mcp.srv.action', version: 'route-v1' });

    // 快照闭包绑定的是发现时刻的版本：即使 Registry 随后刷新出新版本，
    // 已开始的调用仍携带旧版本，由服务端判定一致性而不是本地改写。
    definition.version = 'route-v2';
    const secondSnapshot = await registry.refresh();
    expect(secondSnapshot.tools[0]?.version).toBe('route-v2');
    await expect(firstSnapshot.invoke('mcp.srv.action', {}, {
      traceId: 'trace-1',
      conversationId: null,
      toolCallId: 'call-2',
    })).resolves.toMatchObject({ content: 'ok' });
    expect(calls[1]?.version).toBe('route-v1');
  });

  it('VA-01：HttpToolClient 调用请求体携带版本引用字段', async () => {
    const bodies: unknown[] = [];
    const transport: HttpTransport = {
      request: async (_url, init) => {
        bodies.push(JSON.parse(String(init.body)));
        return new Response(JSON.stringify({
          toolCallId: 'call-1',
          content: 'ok',
          isError: false,
        }), { status: 200, headers: { 'Content-Type': 'application/json' } });
      },
    };
    const client = new HttpToolClient('/ai', transport);
    await client.call('mcp.srv.action', 'route-v1', {}, {
      traceId: 'trace-1',
      conversationId: null,
      toolCallId: 'call-1',
    });

    expect(bodies[0]).toMatchObject({
      name: 'mcp.srv.action',
      version: 'route-v1',
      toolCallId: 'call-1',
    });
  });
});
