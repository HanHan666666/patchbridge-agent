/**
 * ModelMessageAssembler 契约测试：锁定单次模型响应进入 Runtime 前的最终协议边界。
 *
 * <p>这里直接驱动结构化事件，验证 stopReason 决策、同响应 Tool Call ID 唯一性和
 * 聚合字符预算；Tool 调度与 Execution 生命周期属于 runtime.test.ts，不在本文件重复。
 */
import { describe, expect, it } from 'vitest';
import type {
  ModelStopReason,
  ModelStreamEvent,
} from '../src/clients/modelClient';
import { ModelMessageAssembler } from '../src/modelMessageAssembler';

/** 创建一条完整正文响应，停止原因由测试显式给出。 */
function textEvents(
  stopReason: ModelStopReason,
  text = '稳定回答',
): readonly ModelStreamEvent[] {
  return [
    { type: 'block-start', index: 0, block: { type: 'text' } },
    { type: 'block-delta', index: 0, delta: { type: 'text', text } },
    { type: 'block-stop', index: 0 },
    { type: 'message-stop', stopReason, usage: null, modelState: null },
  ];
}

/** 创建一条包含一个完整 Tool Call 的响应。 */
function toolEvents(
  stopReason: ModelStopReason,
  callId = 'call-1',
): readonly ModelStreamEvent[] {
  return [
    {
      type: 'block-start',
      index: 0,
      block: { type: 'tool-call', callId, name: 'local.device_get' },
    },
    {
      type: 'block-delta',
      index: 0,
      delta: { type: 'tool-call', argumentsDelta: '{"serial":"DEV-1"}' },
    },
    { type: 'block-stop', index: 0 },
    { type: 'message-stop', stopReason, usage: null, modelState: null },
  ];
}

/** 按顺序消费完整事件脚本。 */
function consumeAll(
  assembler: ModelMessageAssembler,
  events: readonly ModelStreamEvent[],
): void {
  for (const event of events) {
    assembler.consume(event);
  }
}

/** 捕获对象形态的 AgentError，避免把领域错误误判为必须继承 Error 的异常类。 */
function captureFailure(action: () => void): unknown {
  try {
    action();
  } catch (cause) {
    return cause;
  }
  throw new Error('测试动作没有按预期失败');
}

describe('ModelMessageAssembler', () => {
  it.each([0, -1, 1.5, Number.POSITIVE_INFINITY, Number.NaN])(
    '构造期拒绝非法模型输出字符上限 %s',
    limit => {
      expect(() => new ModelMessageAssembler(limit))
        .toThrow('maxModelOutputCharacters 必须是有限正整数');
    },
  );

  it('正文、展示思考和 Tool 参数共享同一字符预算，达到上限仍可完成', () => {
    const text = 'ok';
    const reasoning = 'why';
    const argumentsText = '{}';
    const assembler = new ModelMessageAssembler(
      text.length + reasoning.length + argumentsText.length,
    );
    consumeAll(assembler, [
      { type: 'block-start', index: 0, block: { type: 'reasoning' } },
      { type: 'block-delta', index: 0, delta: { type: 'reasoning', text: reasoning } },
      { type: 'block-stop', index: 0 },
      { type: 'block-start', index: 1, block: { type: 'text' } },
      { type: 'block-delta', index: 1, delta: { type: 'text', text } },
      { type: 'block-stop', index: 1 },
      {
        type: 'block-start',
        index: 2,
        block: { type: 'tool-call', callId: 'call-1', name: 'local.device_get' },
      },
      {
        type: 'block-delta',
        index: 2,
        delta: { type: 'tool-call', argumentsDelta: argumentsText },
      },
      { type: 'block-stop', index: 2 },
      { type: 'message-stop', stopReason: 'tool-use', usage: null, modelState: null },
    ]);

    expect(assembler.assemble('assistant-1').message.blocks).toEqual([
      { type: 'reasoning', text: reasoning },
      { type: 'text', text },
      { type: 'tool-call', callId: 'call-1', name: 'local.device_get', input: {} },
    ]);
  });

  it.each([
    ['text', { type: 'text', text: 'abc' }],
    ['reasoning', { type: 'reasoning', text: 'abc' }],
    ['tool-call', { type: 'tool-call', argumentsDelta: 'abc' }],
  ] as const)('在追加 %s 增量前以稳定错误拒绝字符越界', (type, delta) => {
    const assembler = new ModelMessageAssembler(2);
    assembler.consume({
      type: 'block-start',
      index: 0,
      block: type === 'tool-call'
        ? { type: 'tool-call', callId: 'call-1', name: 'local.device_get' }
        : { type },
    });

    const failure = captureFailure(() => assembler.consume({
      type: 'block-delta',
      index: 0,
      delta,
    }));

    expect(failure).toMatchObject({
      code: 'MODEL_OUTPUT_LIMIT_EXCEEDED',
      retryable: false,
    });
  });

  it.each(['end-turn', 'stop-sequence', 'other'] as const)(
    '%s 且没有 Tool Call 时形成自然结束的稳定消息',
    stopReason => {
      const assembler = new ModelMessageAssembler(100);
      consumeAll(assembler, textEvents(stopReason));

      expect(assembler.assemble('assistant-1')).toMatchObject({
        stopReason,
        message: { blocks: [{ type: 'text', text: '稳定回答' }] },
      });
    },
  );

  it('max-tokens 且没有 Tool Call 时保留完整消息和 ModelState', () => {
    const assembler = new ModelMessageAssembler(100);
    const modelState = { format: 'provider-state/v1', data: { cursor: 'next' } } as const;
    consumeAll(assembler, [
      { type: 'block-start', index: 0, block: { type: 'text' } },
      { type: 'block-delta', index: 0, delta: { type: 'text', text: '可能未完成' } },
      { type: 'block-stop', index: 0 },
      { type: 'message-stop', stopReason: 'max-tokens', usage: null, modelState },
    ]);

    expect(assembler.assemble('assistant-1')).toEqual({
      message: {
        id: 'assistant-1',
        role: 'assistant',
        blocks: [{ type: 'text', text: '可能未完成' }],
      },
      modelState,
      stopReason: 'max-tokens',
      usage: null,
    });
  });

  it('tool-use 且至少有一个 Tool Call 时形成稳定消息', () => {
    const assembler = new ModelMessageAssembler(100);
    consumeAll(assembler, toolEvents('tool-use'));

    expect(assembler.assemble('assistant-1')).toMatchObject({
      stopReason: 'tool-use',
      message: {
        blocks: [{
          type: 'tool-call',
          callId: 'call-1',
          name: 'local.device_get',
          input: { serial: 'DEV-1' },
        }],
      },
    });
  });

  it('tool-use 却没有 Tool Call 时以 MODEL_PROTOCOL_ERROR 拒绝', () => {
    const assembler = new ModelMessageAssembler(100);
    consumeAll(assembler, textEvents('tool-use'));

    expect(captureFailure(() => assembler.assemble('assistant-1'))).toMatchObject({
      code: 'MODEL_PROTOCOL_ERROR',
      retryable: false,
    });
  });

  it.each(['end-turn', 'stop-sequence', 'other', 'max-tokens'] as const)(
    '%s 携带 Tool Call 时以 MODEL_PROTOCOL_ERROR 拒绝整条响应',
    stopReason => {
      const assembler = new ModelMessageAssembler(100);
      consumeAll(assembler, toolEvents(stopReason));

      expect(captureFailure(() => assembler.assemble('assistant-1'))).toMatchObject({
        code: 'MODEL_PROTOCOL_ERROR',
        retryable: false,
      });
    },
  );

  it('同一响应的 Tool Call ID 必须非空且唯一', () => {
    const emptyId = new ModelMessageAssembler(100);
    expect(captureFailure(() => emptyId.consume({
      type: 'block-start',
      index: 0,
      block: { type: 'tool-call', callId: '', name: 'local.device_get' },
    }))).toMatchObject({ code: 'MODEL_PROTOCOL_ERROR' });

    const duplicateId = new ModelMessageAssembler(100);
    duplicateId.consume({
      type: 'block-start',
      index: 0,
      block: { type: 'tool-call', callId: 'call-1', name: 'local.device_get' },
    });
    expect(captureFailure(() => duplicateId.consume({
      type: 'block-start',
      index: 1,
      block: { type: 'tool-call', callId: 'call-1', name: 'local.device_restart' },
    }))).toMatchObject({
      code: 'MODEL_PROTOCOL_ERROR',
      retryable: false,
    });
  });
});
