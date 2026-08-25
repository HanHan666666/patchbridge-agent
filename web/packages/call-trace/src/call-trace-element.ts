/**
 * patchbridge-agent-call-trace：可插拔只读调用轨迹视图。
 *
 * <p>组件只接受宿主传入的 CallTraceSource（Widget ready 事件或宿主显式赋值），
 * 不自行创建 Controller 或第二份状态。账本按执行分组展示用户输入、模型调用、
 * Tool 调用与人工确认记录；点击记录行就地展开详情（耗时、token、参数、结果）。
 *
 * <p>本地保留说明：轨迹是执行过程元数据，不属于模型上下文，按架构不写入服务端
 * 会话存储；组件头部提供 ℹ️ 弹窗向用户解释这一取舍（为什么只存在本浏览器），
 * 弹窗默认关闭、点开才出现，不占用页面常驻区域。
 */
import type {
  CallTraceSnapshot,
  CallTraceSource,
} from '@patchbridge-agent/agent';
import { CALL_TRACE_MAX_TRACES_PER_CONVERSATION } from '@patchbridge-agent/agent';
import {
  toDetailSections,
  toTraceGroups,
  type TraceGroup,
  type TraceRecordRow,
} from './viewModel';

/** 轨迹自定义元素名称。 */
export const CALL_TRACE_ELEMENT_NAME = 'patchbridge-agent-call-trace';

/** 可插拔只读调用轨迹视图。 */
export class PatchBridgeAgentCallTraceElement extends HTMLElement {
  /** 封装参考视图的 Shadow Root。 */
  private readonly shadow: ShadowRoot;
  /** 宿主传入且当前正在展示的轨迹数据源。 */
  private sourceValue: CallTraceSource | null = null;
  /** 当前数据源订阅的释放函数。 */
  private unsubscribe: (() => void) | null = null;
  /** 绑定代次用于丢弃旧数据源的迟到快照。 */
  private bindingGeneration = 0;
  /** 当前展开详情的记录选择键；空串表示全部收起。 */
  private selectedKey = '';
  /** 最近一次订阅快照；本地交互重绘必须使用同一份数据，不能再次读取数据源。 */
  private snapshotValue: CallTraceSnapshot | null = null;
  /** 最近渲染的说明按钮；关闭 dialog 后用于恢复键盘焦点。 */
  private infoButton: HTMLButtonElement | null = null;
  /** 存储说明是否由用户打开；轨迹快照重绘时保持该本地交互状态。 */
  private infoDialogOpen = false;

  /** 创建尚未绑定数据源的空视图。 */
  constructor() {
    super();
    this.shadow = this.attachShadow({ mode: 'open' });
    this.render(null);
  }

  /** 当前展示使用的数据源；必须由 Widget ready 事件或宿主显式赋值。 */
  get traceSource(): CallTraceSource | null {
    return this.sourceValue;
  }

  /** 切换数据源时完整清理旧订阅；未连接元素只保存源，连接后再订阅。 */
  set traceSource(source: CallTraceSource | null) {
    if (source === this.sourceValue) {
      return;
    }
    this.unbindSource();
    this.sourceValue = source;
    this.selectedKey = '';
    this.snapshotValue = source?.snapshot() ?? null;
    if (this.isConnected) {
      this.bindSource();
    } else {
      this.render(this.snapshotValue);
    }
  }

  /** 元素连接或重连时恢复对既有数据源的订阅。 */
  connectedCallback(): void {
    this.bindSource();
  }

  /** 元素从 DOM 移除后只释放订阅，保留 sourceValue 以便重连。 */
  disconnectedCallback(): void {
    this.unbindSource();
  }

  /** 建立当前数据源订阅；subscribe 立即推送最新快照。 */
  private bindSource(): void {
    if (this.unsubscribe != null) {
      return;
    }
    const source = this.sourceValue;
    if (source == null) {
      this.snapshotValue = null;
      this.render(null);
      return;
    }
    const generation = ++this.bindingGeneration;
    this.unsubscribe = source.subscribe(snapshot => {
      if (generation !== this.bindingGeneration) {
        return;
      }
      this.snapshotValue = snapshot;
      this.render(snapshot);
    });
  }

  /** 释放当前订阅并推进代次，丢弃旧源的迟到快照。 */
  private unbindSource(): void {
    this.bindingGeneration += 1;
    this.unsubscribe?.();
    this.unsubscribe = null;
  }

  /** 重建账本视图；所有动态内容通过 textContent 写入，避免 HTML 注入。 */
  private render(snapshot: CallTraceSnapshot | null): void {
    this.shadow.replaceChildren(createStyle());
    const panel = document.createElement('section');
    panel.className = 'panel';
    panel.part.add('panel');

    panel.append(this.createHeader(snapshot));

    if (snapshot?.persistenceError != null) {
      const warning = document.createElement('p');
      warning.className = 'warning';
      warning.part.add('warning');
      warning.textContent = `轨迹本地保存失败：${snapshot.persistenceError}（不影响本次页面内的轨迹展示）`;
      panel.append(warning);
    }

    const groups = snapshot == null ? [] : toTraceGroups(snapshot);
    if (
      this.selectedKey.length > 0
      && !groups.some(group => group.rows.some(row => row.key === this.selectedKey))
    ) {
      this.selectedKey = '';
    }
    if (groups.length === 0) {
      panel.append(createEmpty(snapshot != null));
    } else {
      panel.append(createLedger(groups, this.selectedKey, key => {
        // 就地展开/收起详情：选择状态属于视图，重绘必须使用本次订阅保存的同一快照。
        this.selectedKey = this.selectedKey === key ? '' : key;
        this.render(this.snapshotValue);
      }));
    }

    panel.append(createInfoDialog(() => {
      this.infoDialogOpen = false;
      this.infoButton?.focus();
    }));
    this.shadow.append(panel);
    if (this.infoDialogOpen) {
      this.showInfoDialog();
    }
  }

  /** 头部：标题、会话元信息与说明/清空操作。 */
  private createHeader(snapshot: CallTraceSnapshot | null): HTMLElement {
    const header = document.createElement('header');
    header.className = 'header';
    header.part.add('header');

    const titleBlock = document.createElement('div');
    const title = document.createElement('h2');
    title.textContent = '调用轨迹';
    const meta = document.createElement('span');
    meta.className = 'meta';
    meta.part.add('meta');
    meta.textContent = snapshot == null
      ? '未连接'
      : snapshot.conversationId == null
        ? '空白会话 · 尚未保存'
        : `本地保留最近 ${CALL_TRACE_MAX_TRACES_PER_CONVERSATION} 次执行`;
    titleBlock.append(title, meta);

    const actions = document.createElement('div');
    actions.className = 'actions';
    const info = document.createElement('button');
    info.type = 'button';
    info.className = 'ghost';
    info.part.add('info-button');
    info.title = '轨迹数据保存在哪里？为什么不在服务端？';
    info.textContent = 'ℹ️ 存储说明';
    this.infoButton = info;
    info.addEventListener('click', () => this.openInfoDialog());
    const clear = document.createElement('button');
    clear.type = 'button';
    clear.className = 'ghost';
    clear.part.add('clear-button');
    clear.textContent = '清空本会话轨迹';
    clear.disabled = snapshot == null || snapshot.traces.length === 0;
    clear.addEventListener('click', () => {
      if (window.confirm('确定清空当前会话在本浏览器保存的全部调用轨迹？')) {
        this.sourceValue?.clear();
      }
    });
    actions.append(info, clear);

    header.append(titleBlock, actions);
    return header;
  }

  /** 用户打开本地保留说明；随后快照重绘必须保持该状态。 */
  private openInfoDialog(): void {
    this.infoDialogOpen = true;
    this.showInfoDialog();
  }

  /** 显示当前渲染的 dialog，并把键盘焦点放入弹窗。 */
  private showInfoDialog(): void {
    const dialog = this.shadow.querySelector('dialog');
    if (dialog != null && !dialog.open) {
      dialog.showModal();
      dialog.querySelector<HTMLButtonElement>('[data-dialog-close]')?.focus();
    }
  }
}

/** 账本：TraceGroup 已携带行与原始记录，不再按快照下标反查。 */
function createLedger(
  groups: readonly TraceGroup[],
  selectedKey: string,
  onToggle: (key: string) => void,
): HTMLElement {
  const list = document.createElement('div');
  list.className = 'list';
  list.part.add('list');
  for (const group of groups) {
    list.append(createTraceSection(group, selectedKey, onToggle));
  }
  return list;
}

/** 单次执行分组：标题行 + 记录行；选中行就地展开详情。 */
function createTraceSection(
  group: TraceGroup,
  selectedKey: string,
  onToggle: (key: string) => void,
): HTMLElement {
  const section = document.createElement('section');
  section.className = 'trace';
  section.part.add('trace');

  const heading = document.createElement('div');
  heading.className = 'trace-heading';
  heading.part.add('trace-heading');
  const title = document.createElement('strong');
  title.textContent = group.title;
  const status = document.createElement('span');
  status.className = 'status';
  status.part.add('status');
  status.dataset['state'] = group.statusLabel;
  status.textContent = group.statusLabel;
  const summary = document.createElement('span');
  summary.className = 'summary';
  summary.textContent = `${group.summaryLabel} · ${group.durationLabel}`;
  heading.append(title, status, summary);

  const rows = document.createElement('ul');
  rows.className = 'records';
  for (const row of group.rows) {
    rows.append(createRecordRow(
      row,
      selectedKey === row.key,
      onToggle,
    ));
  }

  section.append(heading, rows);
  return section;
}

/** 单条记录行：按钮语义 + 类型徽标 + 摘要 + 耗时/token；选中时展开详情。 */
function createRecordRow(
  row: TraceRecordRow,
  selected: boolean,
  onToggle: (key: string) => void,
): HTMLLIElement {
  const item = document.createElement('li');
  item.className = selected ? 'record selected' : 'record';
  item.part.add('record');
  item.dataset['kind'] = row.kind;
  item.dataset['error'] = String(row.isError);

  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'record-button';
  button.setAttribute('aria-expanded', String(selected));
  button.addEventListener('click', () => onToggle(row.key));
  const kind = document.createElement('span');
  kind.className = 'kind';
  kind.textContent = row.kindLabel;
  const summary = document.createElement('span');
  summary.className = 'record-summary';
  summary.textContent = row.summary;
  const time = document.createElement('span');
  time.className = 'time';
  time.textContent = `${row.timeLabel} · ${row.durationLabel}`;
  const tokens = document.createElement('span');
  tokens.className = 'tokens';
  tokens.textContent = row.tokenLabel;
  const firstToken = document.createElement('span');
  firstToken.className = 'first-token';
  firstToken.textContent = row.firstTokenLabel;
  if (row.firstTokenLabel.length > 0) {
    firstToken.title = '模型调用开始到首个非空正文、思考或 Tool 参数增量';
  }
  const tokenSpeed = document.createElement('span');
  tokenSpeed.className = 'token-speed';
  tokenSpeed.textContent = row.tokenSpeedLabel;
  if (row.tokenSpeedLabel.length > 0) {
    tokenSpeed.title = '输出 token ÷ 首个内容增量到模型流完成的耗时';
  }
  button.append(kind, summary, time);
  if (row.tokenLabel.length > 0) {
    button.append(tokens);
  }
  if (row.firstTokenLabel.length > 0) {
    button.append(firstToken);
  }
  if (row.tokenSpeedLabel.length > 0) {
    button.append(tokenSpeed);
  }
  item.append(button);

  if (selected) {
    const detail = document.createElement('div');
    detail.className = 'detail';
    detail.part.add('detail');
    // 详情分段来自纯函数投影；空文本分段直接跳过，不渲染空标题。
    for (const section of toDetailSections(row.record)) {
      if (section.text.length === 0) {
        continue;
      }
      const heading = document.createElement('h3');
      heading.textContent = section.title;
      const text = document.createElement('pre');
      text.textContent = section.text;
      detail.append(heading, text);
    }
    item.append(detail);
  }
  return item;
}

/** 未连接或空轨迹提示。 */
function createEmpty(connected: boolean): HTMLParagraphElement {
  const empty = document.createElement('p');
  empty.className = 'empty';
  empty.part.add('empty');
  empty.textContent = connected
    ? '当前会话暂无轨迹；发送一条消息后，这里会按执行展示每一步调用。'
    : '等待 Agent 连接调用轨迹数据源…';
  return empty;
}

/**
 * 本地保留说明弹窗（默认关闭）。
 *
 * <p>内容回答用户最可能追问的问题：为什么轨迹不存到服务端。要点与
 * CallTraceStore 的设计注释一一对应，避免两处口径漂移。
 */
function createInfoDialog(onClosed: () => void): HTMLDialogElement {
  const dialog = document.createElement('dialog');
  dialog.part.add('info-dialog');
  dialog.setAttribute('aria-labelledby', 'trace-storage-dialog-title');
  const article = document.createElement('article');
  const title = document.createElement('h3');
  title.id = 'trace-storage-dialog-title';
  title.textContent = '轨迹数据保存在哪里？';
  const paragraphs = [
    '调用轨迹记录的是执行过程元数据：每步调用的开始时间与耗时、token 用量、人工确认交互与失败边界。这些数据不属于"模型上下文"，按当前架构不会写入服务端的会话存储——会话存储只保存模型下一轮需要看到的消息与续接状态。',
    '服务端审计日志里另有按 traceId 记录的调用审计（模型/Tool 的耗时与 token），供管理员在管理后台查看；面向普通用户的轨迹查询接口不在本期范围。',
    '因此轨迹按会话保存在当前浏览器的 localStorage：每个会话保留最近若干次执行、超长内容自动截断。更换设备、更换浏览器或清理站点数据后，轨迹会消失；对话内容不受影响，仍从服务端会话历史完整恢复。',
    '"清空本会话轨迹"只删除当前会话在本浏览器的轨迹记录，不影响对话与任何业务数据。',
  ];
  article.append(title);
  for (const text of paragraphs) {
    const paragraph = document.createElement('p');
    paragraph.textContent = text;
    article.append(paragraph);
  }
  const close = document.createElement('button');
  close.type = 'button';
  close.className = 'ghost';
  close.dataset['dialogClose'] = 'true';
  close.textContent = '我知道了';
  close.addEventListener('click', () => dialog.close());
  dialog.addEventListener('close', onClosed);
  // 点击遮罩关闭：dialog 上的点击目标等于自身时即为遮罩区域。
  dialog.addEventListener('click', event => {
    if (event.target === dialog) {
      dialog.close();
    }
  });
  dialog.append(article, close);
  return dialog;
}

/**
 * 轨迹视图使用独立、很薄的参考样式；视觉值可由 --patchbridge-agent-trace-*
 * CSS Variables 覆盖，结构节点通过 ::part 暴露，宿主不依赖内部 class 名。
 */
function createStyle(): HTMLStyleElement {
  const style = document.createElement('style');
  style.textContent = `
    :host {
      display: block;
      height: 100%;
      color: var(--patchbridge-agent-trace-color, #1f2329);
      font: var(--patchbridge-agent-trace-font, 14px/1.5 system-ui, sans-serif);
    }
    .panel { height: 100%; overflow: auto; box-sizing: border-box; padding: var(--patchbridge-agent-trace-padding, 20px); background: var(--patchbridge-agent-trace-background, #fff); }
    .header { position: sticky; top: 0; z-index: 1; display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 0 0 14px; background: inherit; }
    h2 { margin: 0; font-size: var(--patchbridge-agent-trace-title-size, 16px); }
    .meta { display: block; color: var(--patchbridge-agent-trace-muted-color, #667085); font-size: 12px; }
    .actions { display: flex; gap: 8px; flex: none; }
    .ghost { border: 1px solid var(--patchbridge-agent-trace-border-color, #e4e7ec); border-radius: 6px; background: transparent; color: inherit; padding: 4px 10px; font: inherit; font-size: 12px; cursor: pointer; }
    .ghost:hover:enabled { border-color: var(--patchbridge-agent-trace-accent-color, #1e3a8a); color: var(--patchbridge-agent-trace-accent-color, #1e3a8a); }
    .ghost:disabled { opacity: .5; cursor: default; }
    .warning { margin: 0 0 12px; padding: 8px 12px; border-radius: 8px; color: var(--patchbridge-agent-trace-warning-color, #b54708); background: var(--patchbridge-agent-trace-warning-background, #fef0c7); font-size: 12px; }
    .list { display: grid; gap: var(--patchbridge-agent-trace-gap, 14px); }
    .trace { padding: var(--patchbridge-agent-trace-card-padding, 14px); border: 1px solid var(--patchbridge-agent-trace-border-color, #e4e7ec); border-radius: var(--patchbridge-agent-trace-radius, 8px); background: var(--patchbridge-agent-trace-card-background, #fff); }
    .trace-heading { display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap; }
    .trace-heading strong { font-size: 13px; }
    .status { flex: none; padding: 1px 8px; border-radius: 999px; font-size: 12px; color: var(--patchbridge-agent-trace-accent-color, #1e3a8a); background: var(--patchbridge-agent-trace-accent-background, #eef2ff); }
    .status[data-state="失败（见错误码）"], .status[data-state^="失败"] { color: var(--patchbridge-agent-trace-error-color, #b42318); background: var(--patchbridge-agent-trace-error-background, #fee4e2); }
    .status[data-state="进行中"] { color: var(--patchbridge-agent-trace-warning-color, #b54708); background: var(--patchbridge-agent-trace-warning-background, #fef0c7); }
    .status[data-state="输出已截断"] { color: var(--patchbridge-agent-trace-warning-color, #b54708); background: var(--patchbridge-agent-trace-warning-background, #fef0c7); }
    .summary { color: var(--patchbridge-agent-trace-muted-color, #667085); font-size: 12px; }
    .records { list-style: none; margin: 10px 0 0; padding: 0; display: grid; gap: 6px; }
    .record { border: 1px solid transparent; border-radius: 6px; }
    .record[data-error="true"] .record-button .record-summary { color: var(--patchbridge-agent-trace-error-color, #b42318); }
    .record-button { display: flex; flex-wrap: wrap; align-items: baseline; gap: 10px; width: 100%; text-align: left; border: 0; border-radius: 6px; background: var(--patchbridge-agent-trace-row-background, #f8fafc); color: inherit; font: inherit; font-size: 13px; padding: 6px 10px; cursor: pointer; }
    .record-button:hover { background: var(--patchbridge-agent-trace-row-hover-background, #eef2f6); }
    .kind { flex: none; min-width: 84px; color: var(--patchbridge-agent-trace-muted-color, #667085); font-size: 12px; }
    .record[data-kind="user-input"] .kind { color: var(--patchbridge-agent-trace-accent-color, #1e3a8a); }
    .record-summary { flex: 1 1 auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .time { flex: none; color: var(--patchbridge-agent-trace-muted-color, #667085); font-size: 12px; }
    .tokens, .first-token, .token-speed { flex: none; white-space: nowrap; font-size: 12px; font-variant-numeric: tabular-nums; }
    .tokens, .token-speed { color: var(--patchbridge-agent-trace-accent-color, #1e3a8a); }
    .first-token { color: var(--patchbridge-agent-trace-muted-color, #667085); }
    .selected { border-color: var(--patchbridge-agent-trace-accent-color, #1e3a8a); }
    .detail { margin: 6px 0 0; padding: 10px; border-radius: 6px; background: var(--patchbridge-agent-trace-detail-background, #f2f4f7); }
    .detail h3 { margin: 8px 0 4px; font-size: 12px; color: var(--patchbridge-agent-trace-muted-color, #667085); }
    .detail h3:first-child { margin-top: 0; }
    .detail pre { margin: 0; max-height: 240px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 12px; font-family: var(--patchbridge-agent-trace-monospace, ui-monospace, SFMono-Regular, Menlo, monospace); }
    .empty { padding: 16px; border-radius: 8px; background: var(--patchbridge-agent-trace-empty-background, #f8fafc); color: var(--patchbridge-agent-trace-muted-color, #667085); }
    dialog { border: 1px solid var(--patchbridge-agent-trace-border-color, #e4e7ec); border-radius: 10px; max-width: 520px; padding: 20px; color: var(--patchbridge-agent-trace-color, #1f2329); background: var(--patchbridge-agent-trace-background, #fff); }
    dialog::backdrop { background: rgb(0 0 0 / 0.35); }
    dialog article p { margin: 8px 0; font-size: 13px; line-height: 1.7; }
    dialog .ghost { margin-top: 12px; }
    @media (prefers-color-scheme: dark) {
      :host { color: var(--patchbridge-agent-trace-color, #e5e7eb); }
      .panel { background: var(--patchbridge-agent-trace-background, #111827); }
      .trace { border-color: var(--patchbridge-agent-trace-border-color, #374151); background: var(--patchbridge-agent-trace-card-background, #1f2937); }
      .record-button { background: var(--patchbridge-agent-trace-row-background, #111827); }
      .record-button:hover { background: var(--patchbridge-agent-trace-row-hover-background, #263244); }
      .detail { background: var(--patchbridge-agent-trace-detail-background, #111827); }
      .empty { background: var(--patchbridge-agent-trace-empty-background, #1f2937); }
      .ghost { border-color: var(--patchbridge-agent-trace-border-color, #4b5563); }
      dialog { color: var(--patchbridge-agent-trace-color, #e5e7eb); background: var(--patchbridge-agent-trace-background, #1f2937); border-color: var(--patchbridge-agent-trace-border-color, #4b5563); }
    }
    @media (max-width: 720px) {
      .header { align-items: flex-start; flex-direction: column; }
      .actions { flex-wrap: wrap; }
      .record-button { display: grid; grid-template-columns: minmax(76px, auto) minmax(0, 1fr); }
      .time, .tokens, .first-token, .token-speed { grid-column: 2; }
    }
  `;
  return style;
}

declare global {
  /** 为 document.querySelector 与模板工具提供自定义元素类型。 */
  interface HTMLElementTagNameMap {
    'patchbridge-agent-call-trace': PatchBridgeAgentCallTraceElement;
  }
}
