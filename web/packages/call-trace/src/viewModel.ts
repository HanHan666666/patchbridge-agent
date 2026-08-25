/**
 * 调用轨迹展示模型：把 CallTraceSnapshot 转成稳定、只读的 UI 行数据。
 *
 * <p>该层不依赖 DOM：时间与 token 的格式化、记录摘要、详情分段和状态标签
 * 全部在此收敛并可独立测试，Custom Element 只负责布局与交互，
 * 避免渲染分支散落在组件里。
 */
import type {
  CallTraceRecord,
  CallTraceSnapshot,
  ExecutionTrace,
  ModelCallTraceRecord,
  ModelUsage,
  UserInputTraceRecord,
  ConfirmationTraceRecord,
} from '@patchbridge-agent/agent';

/** 账本里单条记录的展示行。 */
export interface TraceRecordRow {
  /** 由 traceId 与记录稳定身份编码出的选择键，不依赖列表下标或分隔符约定。 */
  readonly key: string;
  /** 详情使用的原始只读记录；避免组件再按平行数组下标反查。 */
  readonly record: CallTraceRecord;
  /** 记录种类判别字段，供宿主按类型着色。 */
  readonly kind: CallTraceRecord['type'];
  /** 种类的中文标签。 */
  readonly kindLabel: string;
  /** 单行摘要；超长由 CSS 省略。 */
  readonly summary: string;
  /** 开始时刻标签（HH:mm:ss）。 */
  readonly timeLabel: string;
  /** 耗时标签；未知或进行中为 '—' / '进行中'。 */
  readonly durationLabel: string;
  /** token 用量标签（↑输入 ↓输出）；无用量为空串。 */
  readonly tokenLabel: string;
  /** 首个非空内容增量延迟；非模型调用或未完成为空串。 */
  readonly firstTokenLabel: string;
  /** 首 token 后的平均输出速度；缺少 usage 或有效输出耗时时为空串。 */
  readonly tokenSpeedLabel: string;
  /** 是否以失败呈现（Tool 业务错误）。 */
  readonly isError: boolean;
  /** 是否进行中（缺少完成时刻）。 */
  readonly running: boolean;
}

/** 单次执行（轨迹）的展示分组。 */
export interface TraceGroup {
  /** 跨刷新稳定的选择键：traceId。 */
  readonly key: string;
  /** 与服务端审计串联的链路标识。 */
  readonly traceId: string;
  /** 分组标题：序号 + 开始时刻。 */
  readonly title: string;
  /** 执行状态标签。 */
  readonly statusLabel: string;
  /** 执行总耗时标签。 */
  readonly durationLabel: string;
  /** 调用汇总标签（模型/Tool 次数）。 */
  readonly summaryLabel: string;
  /** 组内记录行。 */
  readonly rows: readonly TraceRecordRow[];
}

/** 详情面板中的一个分段：标题 + 纯文本内容。 */
export interface TraceDetailSection {
  /** 分段标题。 */
  readonly title: string;
  /** 分段内容；空串时分段不渲染。 */
  readonly text: string;
}

/** 把快照投影为时间正序的展示分组；空快照返回空数组。 */
export function toTraceGroups(snapshot: CallTraceSnapshot): readonly TraceGroup[] {
  return snapshot.traces.map((trace, traceIndex) => ({
    key: trace.traceId,
    traceId: trace.traceId,
    title: `#${traceIndex + 1} · ${formatClock(trace.startedAt)}`,
    statusLabel: traceStatusLabel(trace),
    durationLabel: formatDuration(trace.startedAt, trace.endedAt),
    summaryLabel: traceSummaryLabel(trace),
    rows: trace.records.map(record => toRecordRow(trace, record)),
  }));
}

/** 单行转换：摘要与标签按记录种类收敛在此，组件不做分支。 */
export function toRecordRow(
  trace: ExecutionTrace,
  record: CallTraceRecord,
): TraceRecordRow {
  const base = {
    key: JSON.stringify([trace.traceId, recordIdentity(record)]),
    record,
    kind: record.type,
    timeLabel: formatClock(recordStartAt(record)),
  };
  switch (record.type) {
    case 'user-input':
      return {
        ...base,
        kindLabel: '用户输入',
        summary: userInputSummary(record),
        durationLabel: '—',
        tokenLabel: '',
        firstTokenLabel: '',
        tokenSpeedLabel: '',
        isError: false,
        running: false,
      };
    case 'model-call':
      return {
        ...base,
        kindLabel: `模型调用 #${record.callIndex}`,
        summary: modelCallSummary(record),
        durationLabel: record.endedAt == null ? '进行中' : formatDuration(record.startedAt, record.endedAt),
        tokenLabel: formatUsage(record.usage),
        firstTokenLabel: formatFirstTokenLatency(record.firstTokenLatencyMs),
        tokenSpeedLabel: formatTokenSpeed(record.usage, record.outputDurationMs),
        isError: false,
        running: record.endedAt == null,
      };
    case 'tool-call':
      return {
        ...base,
        kindLabel: 'Tool 调用',
        summary: record.toolName,
        durationLabel: record.endedAt == null ? '进行中' : formatDuration(record.startedAt, record.endedAt),
        tokenLabel: '',
        firstTokenLabel: '',
        tokenSpeedLabel: '',
        isError: record.isError === true,
        running: record.endedAt == null,
      };
    case 'confirmation':
      return {
        ...base,
        kindLabel: '人工确认',
        summary: confirmationSummary(record),
        durationLabel: record.resolvedAt == null
          ? '未响应'
          : formatDuration(record.requestedAt, record.resolvedAt),
        tokenLabel: '',
        firstTokenLabel: '',
        tokenSpeedLabel: '',
        isError: record.approved === false,
        running: false,
      };
  }
}

/** 详情分段：按记录种类给出完整字段，空文本分段由组件跳过。 */
export function toDetailSections(record: CallTraceRecord): readonly TraceDetailSection[] {
  switch (record.type) {
    case 'user-input':
      return [
        { title: '输入内容', text: record.text },
        { title: '图片附件', text: record.imageCount > 0 ? `${record.imageCount} 张` : '' },
      ];
    case 'model-call':
      return [
        { title: '停止原因', text: record.stopReason ?? '—' },
        { title: 'token 用量', text: record.usage == null ? '（厂商未提供）' : usageDetail(record.usage) },
        { title: '首 token 延迟', text: compactDuration(record.firstTokenLatencyMs) },
        { title: '平均输出速度', text: tokenSpeedValue(record.usage, record.outputDurationMs) },
        { title: '思考内容', text: record.reasoning },
        { title: '回复正文', text: record.text },
        { title: '发起的 Tool 调用', text: record.toolCallIds.length > 0 ? record.toolCallIds.join('\n') : '' },
      ];
    case 'tool-call':
      return [
        { title: '参数', text: record.argumentsText },
        {
          title: '结果',
          text: record.resultText.length > 0
            ? record.resultText
            : (record.endedAt == null ? '（进行中）' : ''),
        },
      ];
    case 'confirmation':
      return [
        { title: '确认结果', text: confirmationSummary(record) },
        { title: '请求时间', text: formatClock(record.requestedAt) },
      ];
  }
}

/** 执行状态标签；进行中优先，失败附带错误码，非异常终态按统一 Outcome 展示。 */
export function traceStatusLabel(trace: ExecutionTrace): string {
  if (trace.endedAt == null) {
    return '进行中';
  }
  if (trace.failed != null) {
    return `失败（${trace.failed.code}）`;
  }
  switch (trace.outcome?.type) {
    case 'completed':
      return '已完成';
    case 'max-tokens':
      return '输出已截断';
    case 'cancelled':
      return '已取消';
    case undefined:
      throw new Error(`轨迹 ${trace.traceId} 已结束但缺少终态`);
  }
}

/** 模型与 Tool 次数汇总；无调用时给出占位说明。 */
export function traceSummaryLabel(trace: ExecutionTrace): string {
  const modelCalls = trace.records.filter(record => record.type === 'model-call').length;
  const toolCalls = trace.records.filter(record => record.type === 'tool-call').length;
  const confirmations = trace.records.filter(record => record.type === 'confirmation').length;
  const parts = [`模型 ${modelCalls} 次`, `Tool ${toolCalls} 次`];
  if (confirmations > 0) {
    parts.push(`确认 ${confirmations} 次`);
  }
  return parts.join(' · ');
}

/** epoch 毫秒 → HH:mm:ss；空值给 '—'。 */
export function formatClock(epochMillis: number | null): string {
  if (epochMillis == null) {
    return '—';
  }
  const date = new Date(epochMillis);
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

/** 起止毫秒 → 千分位耗时标签；起止缺失给 '—'。 */
export function formatDuration(
  startedAt: number | null,
  endedAt: number | null,
): string {
  if (startedAt == null || endedAt == null) {
    return '—';
  }
  return `${(endedAt - startedAt).toLocaleString('en-US')} ms`;
}

/** 用量单行标签：↑ 输入 ↓ 输出；无用量为空串（区别于“0”）。 */
export function formatUsage(usage: ModelUsage | null): string {
  if (usage == null) {
    return '';
  }
  return `↑${usage.inputTokens.toLocaleString('en-US')} ↓${usage.outputTokens.toLocaleString('en-US')}`;
}

/** 首 token 延迟单行标签；未完成时为空串。 */
export function formatFirstTokenLatency(latencyMs: number | null): string {
  const value = compactDuration(latencyMs);
  return value.length === 0 ? '' : `首 token ${value}`;
}

/**
 * 首 token 后的平均输出速度标签。
 *
 * <p>速度使用 Provider 报告的 outputTokens 除以首个非空增量到流结束的耗时；
 * 未提供 usage 或耗时为 0 时不显示，避免产生无穷大或伪造数值。
 */
export function formatTokenSpeed(
  usage: ModelUsage | null,
  outputDurationMs: number | null,
): string {
  const value = tokenSpeedValue(usage, outputDurationMs);
  return value.length === 0 ? '' : `平均 ${value}`;
}

/** 详情里的完整用量文本（含总量）。 */
export function usageDetail(usage: ModelUsage): string {
  return [
    `输入 ${usage.inputTokens.toLocaleString('en-US')}`,
    `输出 ${usage.outputTokens.toLocaleString('en-US')}`,
    `总计 ${usage.totalTokens.toLocaleString('en-US')}`,
  ].join(' · ');
}

/** 性能指标耗时：不足一秒显示毫秒，否则显示最多一位小数秒。 */
function compactDuration(durationMs: number | null): string {
  if (durationMs == null || !Number.isFinite(durationMs) || durationMs < 0) {
    return '';
  }
  if (durationMs < 1000) {
    return `${Math.round(durationMs).toLocaleString('en-US')} ms`;
  }
  return `${(durationMs / 1000).toLocaleString('en-US', {
    maximumFractionDigits: 1,
  })} s`;
}

/** 平均输出速度纯数值；行标签和详情共同复用。 */
function tokenSpeedValue(
  usage: ModelUsage | null,
  outputDurationMs: number | null,
): string {
  if (
    usage == null
    || outputDurationMs == null
    || !Number.isFinite(outputDurationMs)
    || outputDurationMs <= 0
  ) {
    return '';
  }
  const tokensPerSecond = usage.outputTokens * 1000 / outputDurationMs;
  return `${tokensPerSecond.toLocaleString('en-US', {
    maximumFractionDigits: tokensPerSecond < 1 ? 2 : 1,
  })} tok/s`;
}

/** 记录稳定身份：优先使用消息/调用 ID，用户输入与确认用时间补足唯一性。 */
function recordIdentity(record: CallTraceRecord): string {
  switch (record.type) {
    case 'user-input':
      return `user:${record.at}`;
    case 'model-call':
      return `model:${record.responseMessageId}`;
    case 'tool-call':
      return `tool:${record.callId}`;
    case 'confirmation':
      return `confirmation:${record.callId}:${record.requestedAt}`;
  }
}

/** 各记录种类的开始时刻：确认记录用请求时刻，其余用 at/startedAt。 */
function recordStartAt(record: CallTraceRecord): number {
  if (record.type === 'confirmation') {
    return record.requestedAt;
  }
  if (record.type === 'user-input') {
    return record.at;
  }
  return record.startedAt;
}

/** 用户输入摘要：文本截断 + 图片数。 */
function userInputSummary(record: UserInputTraceRecord): string {
  const text = record.text.length > 0 ? compact(record.text, 60) : '（无文本）';
  return record.imageCount > 0 ? `${text} + ${record.imageCount} 张图片` : text;
}

/** 模型调用摘要：优先展示正文片段，其次停止原因。 */
function modelCallSummary(record: ModelCallTraceRecord): string {
  if (record.text.length > 0) {
    return compact(record.text, 60);
  }
  if (record.reasoning.length > 0) {
    return `思考中：${compact(record.reasoning, 50)}`;
  }
  if (record.toolCallIds.length > 0) {
    return `发起 ${record.toolCallIds.length} 个 Tool 调用`;
  }
  return record.endedAt == null ? '流式生成中…' : `停止原因：${record.stopReason ?? '—'}`;
}

/** 确认摘要：批准/拒绝/未响应。 */
function confirmationSummary(record: ConfirmationTraceRecord): string {
  if (record.approved == null) {
    return `${record.toolName} · 未响应`;
  }
  return `${record.toolName} · ${record.approved ? '已批准' : '已拒绝'}`;
}

/** 单行压缩：合并空白并按宽度截断。 */
function compact(text: string, width: number): string {
  const normalized = text.replace(/\s+/g, ' ').trim();
  return normalized.length > width ? `${normalized.slice(0, width)}…` : normalized;
}
