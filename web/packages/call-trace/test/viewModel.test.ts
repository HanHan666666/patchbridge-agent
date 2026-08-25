/** 调用轨迹展示模型测试：分组投影、格式化与详情分段不依赖 DOM。 */
import { describe, expect, it } from 'vitest';
import {
  formatClock,
  formatDuration,
  formatFirstTokenLatency,
  formatTokenSpeed,
  formatUsage,
  toDetailSections,
  toTraceGroups,
  traceStatusLabel,
  traceSummaryLabel,
} from '../src/viewModel';
import type { CallTraceSnapshot, ExecutionTrace } from '@patchbridge-agent/agent';

/** 构造最小可渲染的用户输入 + 模型调用 + Tool 调用轨迹。 */
function sampleTrace(overrides: Partial<ExecutionTrace> = {}): ExecutionTrace {
  return Object.freeze({
    traceId: 'web-t1',
    conversationId: 'c1',
    startedAt: 1_700_000_000_000,
    endedAt: 1_700_000_002_500,
    outcome: { type: 'completed', stopReason: 'end-turn' },
    failed: null,
    records: Object.freeze([
      Object.freeze({
        type: 'user-input',
        at: 1_700_000_000_000,
        text: '看看注塑车间的设备',
        imageCount: 0,
      }),
      Object.freeze({
        type: 'model-call',
        callIndex: 1,
        responseMessageId: 'message-1',
        startedAt: 1_700_000_000_100,
        endedAt: 1_700_000_001_200,
        stopReason: 'tool-use',
        usage: { inputTokens: 1200, outputTokens: 48, totalTokens: 1248 },
        firstTokenLatencyMs: 100,
        outputDurationMs: 400,
        text: '我先查询设备。',
        reasoning: '',
        toolCallIds: Object.freeze(['call-1']),
      }),
      Object.freeze({
        type: 'tool-call',
        callId: 'call-1',
        toolName: 'local.device_list',
        startedAt: 1_700_000_001_300,
        endedAt: 1_700_000_002_400,
        argumentsText: '{ "workshop": "注塑" }',
        resultText: '共 3 台设备',
        isError: false,
      }),
    ]),
    ...overrides,
  });
}

/** 空白会话（conversationId 为 null）的最小快照。 */
function snapshotOf(traces: readonly ExecutionTrace[]): CallTraceSnapshot {
  return Object.freeze({
    conversationId: 'c1',
    traces,
    persistenceError: null,
  });
}

describe('调用轨迹展示模型', () => {
  it('按快照顺序生成分组，行携带选择键与种类标签', () => {
    const groups = toTraceGroups(snapshotOf([sampleTrace()]));

    expect(groups).toHaveLength(1);
    const group = groups[0];
    expect(group?.traceId).toBe('web-t1');
    expect(group?.statusLabel).toBe('已完成');
    expect(group?.summaryLabel).toBe('模型 1 次 · Tool 1 次');
    expect(group?.rows.map(row => row.kindLabel)).toEqual([
      '用户输入',
      '模型调用 #1',
      'Tool 调用',
    ]);
    expect(group?.rows[1]?.tokenLabel).toBe('↑1,200 ↓48');
    expect(group?.rows[1]?.firstTokenLabel).toBe('首 token 100 ms');
    expect(group?.rows[1]?.tokenSpeedLabel).toBe('平均 120 tok/s');
    expect(group?.rows[1]?.durationLabel).toBe('1,100 ms');
    expect(group?.rows[2]?.durationLabel).toBe('1,100 ms');
  });

  it('执行状态标签覆盖进行中、自然完成、输出截断、取消与失败', () => {
    expect(traceStatusLabel(sampleTrace({ endedAt: null }))).toBe('进行中');
    expect(traceStatusLabel(sampleTrace({ outcome: { type: 'completed', stopReason: 'other' } })))
      .toBe('已完成');
    expect(traceStatusLabel(sampleTrace({ outcome: { type: 'max-tokens' } })))
      .toBe('输出已截断');
    expect(traceStatusLabel(sampleTrace({ outcome: { type: 'cancelled' } }))).toBe('已取消');
    expect(traceStatusLabel(sampleTrace({
      outcome: null,
      failed: { code: 'MODEL_PROTOCOL_ERROR', message: '坏帧' },
    }))).toBe('失败（MODEL_PROTOCOL_ERROR）');
  });

  it('时间与耗时格式化：空值给占位、数值千分位', () => {
    expect(formatClock(1_700_000_000_000)).toMatch(/^\d{2}:\d{2}:\d{2}$/);
    expect(formatClock(null)).toBe('—');
    expect(formatDuration(0, 1234)).toBe('1,234 ms');
    expect(formatDuration(null, 100)).toBe('—');
    expect(formatDuration(100, null)).toBe('—');
  });

  it('token 标签区分"无用量"与具体数值', () => {
    expect(formatUsage(null)).toBe('');
    expect(formatUsage({ inputTokens: 0, outputTokens: 25, totalTokens: 25 })).toBe('↑0 ↓25');
  });

  it('首 token 与平均速度格式化使用紧凑单位且不伪造缺失值', () => {
    const usage = { inputTokens: 894, outputTokens: 102, totalTokens: 996 };

    expect(formatFirstTokenLatency(40_600)).toBe('首 token 40.6 s');
    expect(formatFirstTokenLatency(406)).toBe('首 token 406 ms');
    expect(formatFirstTokenLatency(null)).toBe('');
    expect(formatTokenSpeed(usage, 1000)).toBe('平均 102 tok/s');
    expect(formatTokenSpeed({ ...usage, outputTokens: 144 }, 4105))
      .toBe('平均 35.1 tok/s');
    expect(formatTokenSpeed(null, 1000)).toBe('');
    expect(formatTokenSpeed(usage, 0)).toBe('');
  });

  it('详情分段保留原始字段，空内容分段由组件跳过', () => {
    const trace = sampleTrace();
    const modelCall = trace.records[1];
    expect(modelCall?.type).toBe('model-call');
    if (modelCall?.type !== 'model-call') {
      throw new Error('测试数据构造错误');
    }
    const sections = toDetailSections(modelCall);
    expect(sections.map(section => section.title)).toEqual([
      '停止原因',
      'token 用量',
      '首 token 延迟',
      '平均输出速度',
      '思考内容',
      '回复正文',
      '发起的 Tool 调用',
    ]);
    expect(sections.find(section => section.title === '思考内容')?.text).toBe('');
    expect(sections.find(section => section.title === '停止原因')?.text).toBe('tool-use');
    expect(sections.find(section => section.title === '首 token 延迟')?.text).toBe('100 ms');
    expect(sections.find(section => section.title === '平均输出速度')?.text).toBe('120 tok/s');
    expect(sections.find(section => section.title === '发起的 Tool 调用')?.text).toBe('call-1');
  });

  it('汇总标签包含确认次数，无调用时仍显示零计数', () => {
    const empty = sampleTrace({ records: Object.freeze([]) });
    expect(traceSummaryLabel(empty)).toBe('模型 0 次 · Tool 0 次');
  });

  it('记录选择键由语义身份生成，前面插入记录不会改变既有模型行的 key', () => {
    const original = sampleTrace();
    const originalModelKey = toTraceGroups(snapshotOf([original]))[0]?.rows[1]?.key;
    const prepended = sampleTrace({
      records: Object.freeze([
        Object.freeze({
          type: 'user-input',
          at: 1_699_999_999_999,
          text: '前一条输入',
          imageCount: 0,
        }),
        ...original.records,
      ]),
    });
    const shiftedModelKey = toTraceGroups(snapshotOf([prepended]))[0]?.rows[2]?.key;

    expect(shiftedModelKey).toBe(originalModelKey);
    expect(originalModelKey).toContain('message-1');
  });

  it('拒绝确认与 Tool 业务错误使用错误态，运行中记录不伪造耗时', () => {
    const trace = sampleTrace({
      records: Object.freeze([
        Object.freeze({
          type: 'confirmation',
          callId: 'call-1',
          toolName: 'local.device_restart',
          requestedAt: 100,
          resolvedAt: 180,
          approved: false,
        }),
        Object.freeze({
          type: 'tool-call',
          callId: 'call-2',
          toolName: 'local.device_restart',
          startedAt: 200,
          endedAt: 260,
          argumentsText: '{}',
          resultText: '重启失败',
          isError: true,
        }),
        Object.freeze({
          type: 'model-call',
          callIndex: 2,
          responseMessageId: 'message-running',
          startedAt: 300,
          endedAt: null,
          stopReason: null,
          usage: null,
          firstTokenLatencyMs: null,
          outputDurationMs: null,
          text: '',
          reasoning: '',
          toolCallIds: Object.freeze([]),
        }),
      ]),
    });
    const rows = toTraceGroups(snapshotOf([trace]))[0]?.rows ?? [];

    expect(rows[0]).toMatchObject({ isError: true, summary: 'local.device_restart · 已拒绝' });
    expect(rows[1]).toMatchObject({ isError: true, summary: 'local.device_restart' });
    expect(rows[2]).toMatchObject({
      running: true,
      durationLabel: '进行中',
      firstTokenLabel: '',
      tokenSpeedLabel: '',
    });
  });

  it('用户输入摘要合并空白并限制为单行长度', () => {
    const trace = sampleTrace({
      records: Object.freeze([Object.freeze({
        type: 'user-input',
        at: 100,
        text: `第一行\n${'很长内容'.repeat(30)}`,
        imageCount: 2,
      })]),
    });
    const summary = toTraceGroups(snapshotOf([trace]))[0]?.rows[0]?.summary ?? '';

    expect(summary).not.toContain('\n');
    expect(summary).toContain('… + 2 张图片');
  });
});
