/**
 * Widget Tool Registry 宿主入口行为测试。
 *
 * 测试使用最小 HTMLElement 替身运行生命周期，不模拟完整 DOM 渲染；
 * 目标是验证 Widget 始终透传 Controller 持有的唯一 Registry，
 * 以及可拔插 Inspector / Call Trace 依赖的 ready 事件契约；
 * 会话列表转义回归用桩 listEl 捕获模板输出（二次审计 Q-07）。
 */
import { disabledCallTraceSource } from '@patchbridge-agent/agent';
import { escapeHtml } from '../src/markdown';
import type {
  BrowserTool,
  CallTraceSource,
  HttpTransport,
  ToolInspectionSource,
  ToolRegistry,
} from '@patchbridge-agent/agent';
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';

/** Widget ready 事件的三项公共宿主能力。 */
interface ReadyDetail {
  readonly toolRegistry: ToolRegistry;
  readonly toolInspectionSource: ToolInspectionSource;
  readonly callTraceSource: CallTraceSource;
}

/** 原始 HTMLElement 描述符，用于测试后恢复全局环境。 */
const originalHTMLElement = Object.getOwnPropertyDescriptor(globalThis, 'HTMLElement');

/**
 * Controller 装配只需要 EventTarget 和 attachShadow；DOM 查询由测试替换的
 * buildSkeleton / render 隔离，避免为非渲染行为引入完整浏览器模拟器。
 */
class TestHTMLElement extends EventTarget {
  /** 测试元素持有的字符串属性，模拟浏览器 Custom Element 属性存储。 */
  private readonly attributes = new Map<string, string>();

  /** Node 环境不连接真实文档，属性 setter 不应自动触发重建。 */
  readonly isConnected = false;

  /** 测试不使用 ShadowRoot 内容，只需满足构造函数建立引用。 */
  attachShadow(_init: ShadowRootInit): ShadowRoot {
    return {} as ShadowRoot;
  }

  /** 未显式配置的 HTML 属性按照浏览器语义返回 null。 */
  getAttribute(name: string): string | null {
    return this.attributes.get(name) ?? null;
  }

  /**
   * 写入属性并对 observedAttributes 触发生命周期回调，模拟浏览器升级后的行为。
   */
  setAttribute(name: string, value: string): void {
    const oldValue = this.getAttribute(name);
    this.attributes.set(name, value);
    const constructor = this.constructor as unknown as {
      readonly observedAttributes?: readonly string[];
    };
    if (constructor.observedAttributes?.includes(name) !== true) {
      return;
    }
    const element = this as unknown as {
      attributeChangedCallback(
        attributeName: string,
        previousValue: string | null,
        nextValue: string | null,
      ): void;
    };
    element.attributeChangedCallback(name, oldValue, value);
  }
}

/** 动态导入后的 Widget 构造器；必须在安装 HTMLElement 替身后赋值。 */
let WidgetConstructor: typeof import('../src/patchbridge-agent-element').PatchBridgeAgentElement;

beforeAll(async () => {
  Object.defineProperty(globalThis, 'HTMLElement', {
    configurable: true,
    value: TestHTMLElement,
  });
  ({ PatchBridgeAgentElement: WidgetConstructor } = await import(
    '../src/patchbridge-agent-element'
  ));
});

afterAll(() => {
  if (originalHTMLElement == null) {
    Reflect.deleteProperty(globalThis, 'HTMLElement');
    return;
  }
  Object.defineProperty(globalThis, 'HTMLElement', originalHTMLElement);
});

/** 保持初始化请求未完成，使测试只观察同步 Controller 装配边界。 */
function pendingTransport(): HttpTransport {
  return {
    request: () => new Promise<Response>(() => undefined),
  };
}

/** 可被宿主注册的最小合法纯前端 Tool。 */
function browserTool(): BrowserTool {
  return {
    name: 'frontend.current_page',
    description: '读取当前页面信息',
    inputSchema: { type: 'object', properties: {} },
    execute: () => ({ page: 'devices' }),
  };
}

describe('PatchBridgeAgentElement Tool Registry 宿主入口', () => {
  it('脚本后加载升级现有元素时不会在 DOM 骨架前启动', () => {
    const widget = new WidgetConstructor();
    const readyEvents: Event[] = [];
    Reflect.set(widget, 'buildSkeleton', () => undefined);
    Reflect.set(widget, 'render', () => undefined);
    widget.httpTransport = pendingTransport();
    Reflect.set(widget, 'isConnected', true);
    widget.addEventListener('patchbridge-agent-ready', event => readyEvents.push(event));

    // Custom Element 升级时，已存在的 endpoint 会先通知属性变化。
    widget.attributeChangedCallback('endpoint', null, '/ai');

    expect(widget.toolRegistry).toBeNull();
    expect(readyEvents).toHaveLength(0);

    widget.connectedCallback();

    expect(widget.toolRegistry).not.toBeNull();
    expect(readyEvents).toHaveLength(1);
    widget.disconnectedCallback();
  });

  it('已连接但未启动时注入依赖只存值，不在 DOM 骨架前启动', () => {
    const widget = new WidgetConstructor();
    const readyEvents: Event[] = [];
    widget.addEventListener('patchbridge-agent-ready', event => readyEvents.push(event));
    // 脚本后加载升级：构造函数在元素已连接时运行，宿主预置的属性赋值
    // 会在此刻进入 setter，而 connectedCallback（骨架建立）尚未执行。
    // 刻意不 stub render——缺陷场景正是骨架未建就触发订阅的同步渲染，
    // 任何提前启动都会在这里抛出 listEl 未初始化的 TypeError。
    Reflect.set(widget, 'isConnected', true);
    widget.httpTransport = pendingTransport();
    widget.runtimeOptions = { limits: { maxModelCalls: 5 } };

    expect(widget.toolRegistry).toBeNull();
    expect(readyEvents).toHaveLength(0);

    Reflect.set(widget, 'buildSkeleton', () => undefined);
    Reflect.set(widget, 'render', () => undefined);
    widget.connectedCallback();

    // connectedCallback 用已存配置启动；构造期只存值不启动
    expect(widget.toolRegistry).not.toBeNull();
    expect(readyEvents).toHaveLength(1);
    expect(widget.runtimeOptions?.limits?.maxModelCalls).toBe(5);
    widget.disconnectedCallback();
  });

  it('title 与 login-url 变化只按当前快照重绘，不重建 Controller', () => {
    expect(WidgetConstructor.observedAttributes).toEqual([
      'endpoint',
      'call-trace',
      'title',
      'login-url',
    ]);
    const widget = new WidgetConstructor();
    const readyEvents: Event[] = [];
    const render = vi.fn();
    Reflect.set(widget, 'buildSkeleton', () => undefined);
    Reflect.set(widget, 'render', render);
    widget.httpTransport = pendingTransport();
    widget.addEventListener('patchbridge-agent-ready', event => readyEvents.push(event));
    widget.connectedCallback();
    const registry = widget.toolRegistry;
    const controller = Reflect.get(widget, 'controller') as { getState(): unknown };
    const currentState = controller.getState();
    render.mockClear();

    widget.setAttribute('title', '运维助手');
    widget.setAttribute('login-url', '/account/sign-in');

    expect(render).toHaveBeenCalledTimes(2);
    expect(render).toHaveBeenNthCalledWith(1, currentState);
    expect(render).toHaveBeenNthCalledWith(2, currentState);
    expect(Reflect.get(widget, 'panelTitle')).toBe('运维助手');
    expect(Reflect.get(widget, 'loginUrl')).toBe('/account/sign-in');
    expect(widget.toolRegistry).toBe(registry);
    expect(readyEvents).toHaveLength(1);
    widget.disconnectedCallback();
  });

  it('断开期间的视图属性变化不保存快照，重连后由新 Controller 绘制', () => {
    const widget = new WidgetConstructor();
    const readyEvents: Event[] = [];
    const render = vi.fn();
    Reflect.set(widget, 'buildSkeleton', () => undefined);
    Reflect.set(widget, 'render', render);
    widget.httpTransport = pendingTransport();
    widget.addEventListener('patchbridge-agent-ready', event => readyEvents.push(event));

    widget.connectedCallback();
    const firstRegistry = widget.toolRegistry;
    widget.disconnectedCallback();
    render.mockClear();

    widget.setAttribute('title', '重连助手');
    widget.setAttribute('login-url', '/reconnect-login');

    expect(render).not.toHaveBeenCalled();
    expect(widget.toolRegistry).toBeNull();

    widget.connectedCallback();

    expect(render).toHaveBeenCalled();
    expect(readyEvents).toHaveLength(2);
    expect(widget.toolRegistry).not.toBeNull();
    expect(widget.toolRegistry).not.toBe(firstRegistry);
    expect(Reflect.get(widget, 'panelTitle')).toBe('重连助手');
    expect(Reflect.get(widget, 'loginUrl')).toBe('/reconnect-login');
    widget.disconnectedCallback();
  });

  it('未启动或已断开时不接受 Tool 注册', () => {
    const widget = new WidgetConstructor();

    expect(widget.toolRegistry).toBeNull();
    expect(() => widget.registerTool(browserTool()))
      .toThrow(/patchbridge-agent-ready/);

    Reflect.set(widget, 'buildSkeleton', () => undefined);
    Reflect.set(widget, 'render', () => undefined);
    widget.httpTransport = pendingTransport();
    widget.connectedCallback();
    widget.disconnectedCallback();

    expect(widget.toolRegistry).toBeNull();
    expect(() => widget.registerTool(browserTool()))
      .toThrow(/patchbridge-agent-ready/);
  });

  it('恶意会话 id 与标题不得逃逸会话列表的属性/文本插值', () => {
    const widget = new WidgetConstructor();
    const listEl: { innerHTML: string } = { innerHTML: '' };
    Reflect.set(widget, 'listEl', listEl);
    const maliciousId = '"><img src=x onerror=window.__pwned=1><script>alert(1)</script>';
    const maliciousTitle = '<b>标题</b><script>x</script>';

    const renderConversations = (widget as unknown as {
      renderConversations(state: unknown): void;
    }).renderConversations.bind(widget);
    renderConversations({
      conversations: [{
        conversationId: maliciousId,
        title: maliciousTitle,
        revision: 0,
        status: 'ACTIVE',
        createdAt: '2026-08-22T10:00:00Z',
        updatedAt: '2026-08-22T10:00:00Z',
      }],
      conversation: null,
    });

    const html = listEl.innerHTML;
    // 标签必须整体实体化：引号被转义意味着载荷无法闭合属性、无法开启新标签，
    // onerror 等字样只能作为已引号属性值内的纯文本存在，不构成事件属性。
    expect(html).not.toContain('<img');
    expect(html).not.toContain('<script');
    expect(html).not.toContain('<b>');
    // 属性值与转义后的载荷全等：值内不存在任何裸引号/尖括号，浏览器解析
    // dataset.conversationId 时会精确还原原始 id，点击路由不受影响。
    const expectedId = escapeHtml(maliciousId);
    expect(html).toContain(`data-conversation-id="${expectedId}"`);
    expect(html).toContain(`data-delete="${expectedId}"`);
    expect(html).toContain('data-delete=');
  });

  it('call-trace 缺省不采集，显式取值触发重建，非法取值在启动时抛错', () => {
    const widget = new WidgetConstructor();
    const readyEvents: CustomEvent<ReadyDetail>[] = [];
    Reflect.set(widget, 'buildSkeleton', () => undefined);
    Reflect.set(widget, 'render', () => undefined);
    widget.httpTransport = pendingTransport();
    widget.addEventListener('patchbridge-agent-ready', event => {
      readyEvents.push(event as CustomEvent<ReadyDetail>);
    });

    // 缺省 off：数据源恒空，clear 是幂等空操作，不访问任何存储。
    widget.connectedCallback();
    const defaultSource = readyEvents[0]?.detail.callTraceSource;
    expect(defaultSource?.snapshot()).toMatchObject({
      conversationId: null,
      traces: [],
      persistenceError: null,
    });
    expect(() => defaultSource?.clear()).not.toThrow();

    // 运行中显式切换到 memory：采集配置属于 Controller 依赖，必须重建。
    Reflect.set(widget, 'isConnected', true);
    widget.setAttribute('call-trace', 'memory');
    expect(readyEvents).toHaveLength(2);
    expect(readyEvents[1]?.detail.callTraceSource)
      .not.toBe(readyEvents[0]?.detail.callTraceSource);

    // 非法取值是安全默认值边界：启动装配时直接抛错，不静默回落。
    widget.disconnectedCallback();
    widget.setAttribute('call-trace', 'always');
    expect(() => widget.connectedCallback()).toThrow(/call-trace/);
  });

  it('ready 事件同时暴露唯一 Registry、Inspector 与 Call Trace 数据源', () => {
    const widget = new WidgetConstructor();
    const readyEvents: CustomEvent<ReadyDetail>[] = [];
    Reflect.set(widget, 'buildSkeleton', () => undefined);
    Reflect.set(widget, 'render', () => undefined);
    widget.httpTransport = pendingTransport();
    widget.addEventListener('patchbridge-agent-ready', event => {
      readyEvents.push(event as CustomEvent<ReadyDetail>);
    });

    widget.connectedCallback();

    expect(readyEvents).toHaveLength(1);
    const firstEvent = readyEvents[0];
    expect(firstEvent?.bubbles).toBe(true);
    expect(firstEvent?.composed).toBe(true);
    expect(firstEvent?.detail).toMatchObject({
      toolRegistry: widget.toolRegistry,
      toolInspectionSource: expect.anything(),
      callTraceSource: expect.anything(),
    });
    expect(firstEvent?.detail.toolInspectionSource.snapshot().scope)
      .toBe('current-registry');
    expect(firstEvent?.detail.callTraceSource.snapshot()).toMatchObject({
      conversationId: null,
      traces: [],
      persistenceError: null,
    });

    const registration = widget.registerTool(browserTool());
    expect(widget.toolRegistry?.snapshot().tools).toEqual([
      expect.objectContaining({
        name: 'frontend.current_page',
        source: 'FRONTEND_LOCAL',
      }),
    ]);
    expect(firstEvent?.detail.toolInspectionSource.snapshot().tools).toEqual([
      expect.objectContaining({
        name: 'frontend.current_page',
        source: 'FRONTEND_LOCAL',
      }),
    ]);
    registration.dispose();
    expect(widget.toolRegistry?.snapshot().tools).toEqual([]);
    expect(firstEvent?.detail.toolInspectionSource.snapshot().tools).toEqual([]);

    const firstRegistry = widget.toolRegistry;
    const firstInspectionSource = firstEvent?.detail.toolInspectionSource;
    const firstTraceSource = firstEvent?.detail.callTraceSource;
    // 首次从缺省 /ai 切换到显式 endpoint 也必须重建，oldValue 此时为 null。
    Reflect.set(widget, 'isConnected', true);
    widget.attributeChangedCallback('endpoint', null, '/custom-ai');

    expect(readyEvents).toHaveLength(2);
    expect(readyEvents[1]?.detail.toolRegistry).toBe(widget.toolRegistry);
    expect(widget.toolRegistry).not.toBe(firstRegistry);
    expect(readyEvents[1]?.detail.toolInspectionSource).not.toBe(firstInspectionSource);
    // 采集未启用（缺省 call-trace）：重建后的轨迹数据源是同一个显式空源常量；
    // Registry 与 Inspector 仍然必须来自新 Controller，不允许复用旧实例。
    expect(readyEvents[1]?.detail.callTraceSource).toBe(disabledCallTraceSource);
    expect(firstTraceSource).toBe(disabledCallTraceSource);

    // Runtime 扩展配置属于 Controller 依赖，运行中替换必须重建而不是修改现有 Execution。
    widget.runtimeOptions = {
      limits: { maxModelCalls: 3 },
      hooks: [{ onEvent: () => undefined }],
    };
    expect(widget.runtimeOptions?.limits?.maxModelCalls).toBe(3);
    expect(readyEvents).toHaveLength(3);
    expect(readyEvents[2]?.detail.toolInspectionSource)
      .not.toBe(readyEvents[1]?.detail.toolInspectionSource);
    // 未开启采集时空源恒为同一常量；开启采集后的按实例隔离由 call-trace 属性用例覆盖。
    expect(readyEvents[2]?.detail.callTraceSource)
      .toBe(readyEvents[1]?.detail.callTraceSource);

    widget.disconnectedCallback();
  });
});
