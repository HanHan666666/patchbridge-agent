/**
 * Runtime 扩展链测试：验证 Model/Tool Interceptor 的洋葱顺序，以及 next 的
 * 单次消费约束。重复调用 next 会造成重复计费或副作用，必须在扩展边界阻止。
 */
import { describe, expect, it } from 'vitest';
import type { ModelRequest, ModelStreamEvent } from '../src/clients/modelClient';
import {
  composeModelInterceptors,
  composeToolInterceptors,
  type BrowserToolInvocation,
  type ModelInterceptor,
  type ToolInterceptor,
} from '../src/extensions';
import type { ToolDefinition } from '../src/types';

/** 最小 Model 请求，用于调用扩展链而不引入 Runtime 行为。 */
const MODEL_REQUEST: ModelRequest = {
  messages: [],
  modelState: null,
  responseMessageId: 'message-1',
  tools: [],
};

/** 创建只读测试 Tool 定义。 */
function toolDefinition(): ToolDefinition {
  return {
    name: 'local.device_get',
    title: null,
    description: '读取设备',
    inputSchema: { type: 'object' },
    annotations: {
      readOnlyHint: true,
      destructiveHint: false,
      idempotentHint: true,
      requireConfirmation: false,
    },
    source: 'LOCAL',
    permissions: [],
  };
}

/** 创建完整 Tool 调用数据，保证 Interceptor 获得的是稳定快照定义。 */
function toolInvocation(): BrowserToolInvocation {
  return {
    tool: toolDefinition(),
    arguments: { serial: 'DEV-1' },
    context: {
      traceId: 'trace-1',
      conversationId: 'conversation-1',
      toolCallId: 'call-1',
    },
    signal: new AbortController().signal,
  };
}

/** 完整消费模型扩展链返回的流。 */
async function consume(stream: AsyncIterable<ModelStreamEvent>): Promise<void> {
  for await (const _event of stream) {
    // 事件内容由其他测试验证；这里消费流是为了触发拦截器退出阶段。
  }
}

describe('Runtime Interceptor 扩展链', () => {
  it('Model Interceptor 按注册顺序进入、反序退出', async () => {
    const order: string[] = [];
    const interceptor = (name: string): ModelInterceptor => ({
      intercept: (request, context, signal, next) => (async function* () {
        order.push(`${name}-in`);
        for await (const event of next(request, context, signal)) {
          yield event;
        }
        order.push(`${name}-out`);
      })(),
    });
    const invoke = composeModelInterceptors(
      [interceptor('first'), interceptor('second')],
      () => (async function* () {
        order.push('model');
        yield {
          type: 'message-stop',
          stopReason: 'end-turn',
          usage: null,
          modelState: null,
        } as const;
      })(),
    );

    await consume(invoke(
      MODEL_REQUEST,
      { traceId: 'trace-1', conversationId: null },
      new AbortController().signal,
    ));

    expect(order).toEqual([
      'first-in',
      'second-in',
      'model',
      'second-out',
      'first-out',
    ]);
  });

  it('Tool Interceptor 按注册顺序进入、反序退出', async () => {
    const order: string[] = [];
    const interceptor = (name: string): ToolInterceptor => ({
      intercept: async (invocation, next) => {
        order.push(`${name}-in`);
        const result = await next(invocation);
        order.push(`${name}-out`);
        return result;
      },
    });
    const invoke = composeToolInterceptors(
      [interceptor('first'), interceptor('second')],
      async invocation => {
        order.push('tool');
        return {
          toolCallId: invocation.context.toolCallId,
          content: 'ok',
          isError: false,
        };
      },
    );

    await expect(invoke(toolInvocation())).resolves.toMatchObject({ content: 'ok' });
    expect(order).toEqual([
      'first-in',
      'second-in',
      'tool',
      'second-out',
      'first-out',
    ]);
  });

  it('Model 和 Tool Interceptor 重复调用 next 都明确失败', async () => {
    const duplicateModelNext: ModelInterceptor = {
      intercept(request, context, signal, next) {
        const first = next(request, context, signal);
        next(request, context, signal);
        return first;
      },
    };
    const invokeModel = composeModelInterceptors(
      [duplicateModelNext],
      () => (async function* () {
        yield {
          type: 'message-stop',
          stopReason: 'end-turn',
          usage: null,
          modelState: null,
        } as const;
      })(),
    );
    expect(() => invokeModel(
      MODEL_REQUEST,
      { traceId: 'trace-1', conversationId: null },
      new AbortController().signal,
    )).toThrow('ModelInterceptor.next 只能调用一次');

    const duplicateToolNext: ToolInterceptor = {
      async intercept(invocation, next) {
        await next(invocation);
        return next(invocation);
      },
    };
    const invokeTool = composeToolInterceptors(
      [duplicateToolNext],
      async invocation => ({
        toolCallId: invocation.context.toolCallId,
        content: 'ok',
        isError: false,
      }),
    );
    await expect(invokeTool(toolInvocation()))
      .rejects.toThrow('ToolInterceptor.next 只能调用一次');
  });
});
