/**
 * patchbridge-agent-tool-inspector：可插拔只读 Tool 列表。
 *
 * <p>组件只接受宿主传入的 ToolInspectionSource，不自行创建 Client 或获取另一份
 * 列表。因此运行中展示的是 Controller 交给 AgentEngine 的同一冻结快照。
 * 组件没有 Tool 调用入口，移除脚本与标签即可从生产环境完全剥离。
 */
import type {
  ToolInspectionSnapshot,
  ToolInspectionSource,
} from '@patchbridge-agent/agent';
import { toToolInspectorItems } from './viewModel';

/** Inspector 自定义元素名称。 */
export const TOOL_INSPECTOR_ELEMENT_NAME = 'patchbridge-agent-tool-inspector';

/** 可插拔只读 Tools Inspector。 */
export class PatchBridgeAgentToolInspectorElement extends HTMLElement {
  /** 封装参考视图的 Shadow Root。 */
  private readonly shadow: ShadowRoot;
  /** 宿主传入且当前正在展示的执行感知数据源。 */
  private sourceValue: ToolInspectionSource | null = null;
  /** 当前数据源快照订阅的释放函数。 */
  private unsubscribe: (() => void) | null = null;
  /** 绑定代次用于丢弃旧 Registry 的迟到刷新结果。 */
  private bindingGeneration = 0;
  /** 最近一次刷新错误；与上次成功快照同时展示。 */
  private error: string | null = null;

  /** 创建尚未绑定 Registry 的只读视图。 */
  constructor() {
    super();
    this.shadow = this.attachShadow({ mode: 'open' });
    this.render(null);
  }

  /** 当前展示使用的数据源；必须由 Widget ready 事件或宿主显式赋值。 */
  get toolSource(): ToolInspectionSource | null {
    return this.sourceValue;
  }

  /** 切换数据源时完整清理旧订阅，并在允许时主动刷新一次目录。 */
  set toolSource(source: ToolInspectionSource | null) {
    if (source === this.sourceValue) {
      return;
    }
    this.unbindSource();
    this.sourceValue = source;
    const generation = ++this.bindingGeneration;
    this.error = null;
    if (source == null) {
      this.render(null);
      return;
    }
    this.unsubscribe = source.subscribe(snapshot => {
      if (generation === this.bindingGeneration) {
        this.error = null;
        this.render(snapshot);
      }
    });
    void source.refresh().catch(cause => {
      if (generation !== this.bindingGeneration) {
        return;
      }
      this.error = errorMessage(cause);
      this.render(source.snapshot());
    });
  }

  /** 元素从 DOM 移除后不再保留 Registry listener。 */
  disconnectedCallback(): void {
    this.unbindSource();
    this.sourceValue = null;
    this.render(null);
  }

  /** 释放当前订阅并推进 generation，拦截迟到的 refresh 错误。 */
  private unbindSource(): void {
    this.bindingGeneration += 1;
    this.unsubscribe?.();
    this.unsubscribe = null;
  }

  /** 重建小型只读列表；所有外部数据通过 textContent 写入，避免 HTML 注入。 */
  private render(snapshot: ToolInspectionSnapshot | null): void {
    this.shadow.replaceChildren(createStyle());
    const panel = document.createElement('section');
    panel.className = 'panel';
    panel.part.add('panel');
    panel.setAttribute('aria-live', 'polite');

    const header = document.createElement('header');
    header.className = 'header';
    header.part.add('header');
    const title = document.createElement('h2');
    title.textContent = 'Agent 可调用的 Tools';
    header.append(title);
    if (snapshot != null) {
      const meta = document.createElement('span');
      meta.className = 'meta';
      meta.part.add('meta');
      const scope = snapshot.scope === 'current-execution' ? '本轮已冻结' : '当前目录';
      meta.textContent = `${snapshot.tools.length} 个 · revision ${snapshot.revision} · ${scope}`;
      header.append(meta);
    }
    panel.append(header);

    if (this.error != null) {
      const error = document.createElement('p');
      error.className = 'error';
      error.part.add('error');
      error.textContent = `Tools 刷新失败：${this.error}`;
      panel.append(error);
    }

    if (snapshot == null) {
      panel.append(createEmpty('等待 Agent 连接 Tool Inspection Source…'));
    } else if (snapshot.tools.length === 0) {
      panel.append(createEmpty('当前 Agent 没有可调用的 Tool'));
    } else {
      const list = document.createElement('ol');
      list.className = 'list';
      list.part.add('list');
      for (const item of toToolInspectorItems(snapshot)) {
        list.append(createToolItem(item));
      }
      panel.append(list);
    }
    this.shadow.append(panel);
  }
}

/** 创建单条 Tool 卡片，来源码同时暴露为 data-source 供宿主样式选择。 */
function createToolItem(
  item: ReturnType<typeof toToolInspectorItems>[number],
): HTMLLIElement {
  const element = document.createElement('li');
  element.className = 'tool';
  element.part.add('tool');
  element.dataset['source'] = item.source;

  const heading = document.createElement('div');
  heading.className = 'tool-heading';
  const title = document.createElement('strong');
  title.textContent = item.title;
  const source = document.createElement('span');
  source.className = 'source';
  source.part.add('source');
  source.textContent = item.sourceLabel;
  heading.append(title, source);

  const name = document.createElement('code');
  name.className = 'name';
  name.part.add('name');
  name.textContent = item.name;
  const description = document.createElement('p');
  description.textContent = item.description;
  const schema = document.createElement('details');
  schema.part.add('schema');
  const schemaTitle = document.createElement('summary');
  schemaTitle.textContent = item.requiresConfirmation
    ? '参数 Schema · 调用前需确认'
    : '参数 Schema';
  const code = document.createElement('pre');
  code.textContent = item.schema;
  schema.append(schemaTitle, code);
  element.append(heading, name, description, schema);
  return element;
}

/** 创建未连接或空目录提示。 */
function createEmpty(message: string): HTMLParagraphElement {
  const empty = document.createElement('p');
  empty.className = 'empty';
  empty.part.add('empty');
  empty.textContent = message;
  return empty;
}

/** 错误只提取可展示消息，不吞掉失败或伪造空列表。 */
function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause);
}

/**
 * Inspector 使用独立、很薄的参考样式；所有视觉值均可由 CSS Variables 覆盖，
 * 结构节点通过 ::part 暴露，宿主不需要依赖内部 class 名。
 */
function createStyle(): HTMLStyleElement {
  const style = document.createElement('style');
  style.textContent = `
    :host {
      display: block;
      height: 100%;
      color: var(--patchbridge-agent-tools-color, #1f2329);
      font: var(--patchbridge-agent-tools-font, 14px/1.5 system-ui, sans-serif);
    }
    .panel { height: 100%; overflow: auto; box-sizing: border-box; padding: var(--patchbridge-agent-tools-padding, 20px); background: var(--patchbridge-agent-tools-background, #fff); }
    .header { position: sticky; top: 0; z-index: 1; display: flex; align-items: baseline; justify-content: space-between; gap: 12px; padding: 0 0 14px; background: inherit; }
    h2 { margin: 0; font-size: var(--patchbridge-agent-tools-title-size, 16px); }
    .meta { color: var(--patchbridge-agent-tools-muted-color, #667085); font-size: 12px; }
    .list { list-style: none; padding: 0; margin: 0; display: grid; gap: var(--patchbridge-agent-tools-gap, 12px); }
    .tool { padding: var(--patchbridge-agent-tools-card-padding, 14px); border: 1px solid var(--patchbridge-agent-tools-border-color, #e4e7ec); border-radius: var(--patchbridge-agent-tools-radius, 8px); background: var(--patchbridge-agent-tools-card-background, #fff); }
    .tool-heading { display: flex; justify-content: space-between; gap: 12px; }
    .source { flex: none; color: var(--patchbridge-agent-tools-accent-color, #1e3a8a); font-size: 12px; }
    .name { display: block; margin-top: 4px; color: var(--patchbridge-agent-tools-muted-color, #667085); overflow-wrap: anywhere; }
    p { margin: 8px 0 0; }
    details { margin-top: 10px; }
    summary { cursor: pointer; color: var(--patchbridge-agent-tools-muted-color, #667085); }
    pre { margin: 8px 0 0; overflow: auto; padding: 10px; border-radius: 6px; background: var(--patchbridge-agent-tools-code-background, #f2f4f7); font-size: 12px; }
    .empty, .error { padding: 16px; border-radius: 8px; background: var(--patchbridge-agent-tools-empty-background, #f8fafc); }
    .error { color: var(--patchbridge-agent-tools-error-color, #b42318); }
  `;
  return style;
}

declare global {
  /** 为 document.querySelector 与模板工具提供自定义元素类型。 */
  interface HTMLElementTagNameMap {
    'patchbridge-agent-tool-inspector': PatchBridgeAgentToolInspectorElement;
  }
}
