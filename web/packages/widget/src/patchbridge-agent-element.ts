/**
 * <patchbridge-agent> 默认 View：零依赖 Web Component 聊天面板。
 *
 * <p>View Layer 的职责边界（设计文档第 3 节）：只做 DOM 渲染、收集用户意图并
 * 转换为 Controller 调用、订阅 AgentState 渲染。不包含 Agent Loop、请求细节、
 * 确认 Promise 生命周期与竞态判断——全部由 @patchbridge-agent/agent 的 Controller 承担。
 *
 * <p>布局形态：块级平铺组件，填满宿主容器（高度由宿主页面给定，缺省回落
 * min-height 保证可用）；是否悬浮、放在页面哪个位置由宿主自己用 CSS 决定。
 * 默认主题仅作为可用的参考实现：宿主可以通过 --patchbridge-agent-* 设计令牌
 * 调整常用视觉属性，通过稳定的 ::part 覆盖关键语义节点，或设置 theme="none"
 * 只保留布局、交互和可访问性所需的基础样式。内部 class 不属于公共样式 API。
 *
 * <p>接入方式（宿主页面仅需两行）：
 * <pre>
 *   &lt;script src="/ai/assets/patchbridge-agent.js"&gt;&lt;/script&gt;
 *   &lt;patchbridge-agent endpoint="/ai" title="AI 助手" style="height:100%"&gt;&lt;/patchbridge-agent&gt;
 * </pre>
 *
 * <p>调用轨迹（Call Trace）默认不采集：需要时在元素上显式声明
 * call-trace="memory"（仅当前页内存）或 call-trace="persistent"（终态轨迹
 * 写入当前浏览器 localStorage）；生产页面保持缺省即零采集、零存储访问。
 *
 * <p>登录失效处理：宿主系统登录态过期时所有 /ai 接口返回 401（AUTH_REQUIRED），
 * 面板会显示“登录已失效”卡片，按钮跳转到 login-url 属性指定的宿主登录页
 * （缺省 /login）。登录方式属于宿主，widget 只负责把用户送回去，不实现登录本身。
 *
 * <p>渲染策略：稳定消息区只在渲染键变化时重建（键 = 会话身份 + 消息数量，
 * 长回答期间避免逐字重排），流式正文与思考过程单独节点仅更新内容；
 * 会话列表按“ID 序列 + 当前选中项”变化重建。深度思考块遵循“输出时自动展开、
 * 思考结束自动折叠”：思考期间（有 reasoning 且正文未开始）强制 open，
 * 正文开始或流式结束后强制收起。
 *
 * <p>多模态输入：📎 按钮选择图片（PNG / JPEG / WebP / GIF，单张 ≤8MB、
 * 单条 ≤4 张），Base64 图片来源暂存为待发送草稿，随文本一起交给 Controller
 * 组装厂商中立 ImageBlock；用户气泡内图片限尺寸渲染，历史消息随会话持久化。
 */
import {
  createAgentController,
  type CreateAgentControllerOptions,
  type AgentMessage,
  type AgentState,
  type ContentBlock,
  type PatchBridgeAgentController,
  type BrowserTool,
  type ImageAttachment,
  type ImageSource,
  type HttpTransport,
  type ToolRegistration,
  type ToolRegistry,
  type ToolInspectionSource,
  type CallTraceSource,
} from '@patchbridge-agent/agent';
import { escapeHtml, renderMarkdown } from './markdown';

/** 自定义元素名（全局注册一次）。 */
export const ELEMENT_NAME = 'patchbridge-agent';

/** Controller 与唯一 Tool Registry 已完成装配的宿主通知事件名。 */
export const READY_EVENT_NAME = 'patchbridge-agent-ready';

/**
 * patchbridge-agent-ready 事件负载。
 *
 * <p>Registry 用于挂载页面 Tool；执行感知数据源用于 Inspector 精确展示本轮
 * Engine 的冻结快照；轨迹数据源用于可选 Call Trace 视图展示当前会话执行过程。
 * 三者都来自同一 Controller，不会创建第二份运行时状态。
 */
export interface PatchBridgeAgentReadyEventDetail {
  /** 当前 Controller 持有的唯一 Tool Registry。 */
  readonly toolRegistry: ToolRegistry;
  /** 当前执行固定、空闲时跟随 Registry 的只读调试数据源。 */
  readonly toolInspectionSource: ToolInspectionSource;
  /** 当前会话的只读调用轨迹数据源；未开启采集时为恒空的显式空源。 */
  readonly callTraceSource: CallTraceSource;
}

/** Widget 允许宿主注入的默认 Runtime 配置，不包含工厂的其他基础设施依赖。 */
export type WidgetRuntimeOptions = NonNullable<CreateAgentControllerOptions['runtime']>;

/** 默认参考 View：只把 DOM 用户意图映射到 Headless Controller 公共端口。 */
class PatchBridgeAgentElement extends HTMLElement {
  /** 声明具有运行期生命周期语义的 HTML 属性。 */
  static get observedAttributes(): string[] {
    // endpoint / call-trace 改变 Controller 依赖；title/login-url 只改变当前视图配置。
    return ['endpoint', 'call-trace', 'title', 'login-url'];
  }

  /** 当前元素生命周期持有的 Controller。 */
  private controller: PatchBridgeAgentController | null = null;
  /** Controller 状态订阅的释放函数。 */
  private unsubscribe: (() => void) | null = null;
  /** 隔离参考 DOM 与样式的开放 Shadow Root。 */
  private shadow: ShadowRoot;
  /** 宿主注入的 HTTP 传输层；为空时使用框架内置的同源 fetch。 */
  private configuredHttpTransport: HttpTransport | undefined;
  /** 宿主注入的有界循环与扩展点配置；只在 Controller 装配时读取。 */
  private configuredRuntimeOptions: WidgetRuntimeOptions | undefined;

  /** 已绘制的稳定消息渲染键（会话身份 + 消息数量，实例级缓存：多实例互不污染），
   *  变化时才重建消息区。键必须包含会话身份：只看数量的话，两个消息数相同的
   *  会话互相切换会被误判为“无变化”，消息区继续显示先打开的会话。 */
  private renderedMessagesKey = '';
  /** 已绘制的会话列表渲染键（ID 序列 + 当前选中会话，实例级缓存），变化时才重建列表。 */
  private renderedConversationsKey = '';

  // 渲染缓存节点：connectedCallback 中创建，避免依赖构造时序
  /** 面板标题节点；动态 title 由当前 Controller 快照驱动重绘。 */
  private panelTitleEl!: HTMLElement;
  /** Header 中始终可见的上下文窗口占比摘要。 */
  private contextBadgeEl!: HTMLElement;
  /** 展示配置、检查点和手动压缩入口的上下文面板。 */
  private contextPopoverEl!: HTMLElement;
  private listEl!: HTMLElement;
  private messagesEl!: HTMLElement;
  private streamingEl!: HTMLElement;
  private streamingBubbleEl!: HTMLElement;
  private streamingTextEl!: HTMLElement;
  private streamingReasoningEl!: HTMLDetailsElement;
  private streamingReasoningSummaryEl!: HTMLElement;
  private streamingReasoningTextEl!: HTMLElement;
  private statusEl!: HTMLElement;
  /** max-tokens 是稳定但可能不完整的结果，使用独立提示区而不冒充错误。 */
  private maxTokensNoticeEl!: HTMLElement;
  private errorEl!: HTMLElement;
  private authEl!: HTMLElement;
  private inputEl!: HTMLTextAreaElement;
  private sendButtonEl!: HTMLButtonElement;
  private attachButtonEl!: HTMLButtonElement;
  private fileInputEl!: HTMLInputElement;
  private attachmentBarEl!: HTMLElement;
  private inputHintEl!: HTMLElement;
  private scrollEl!: HTMLElement;

  /**
   * 待发送的图片附件：只存在于 View 层，发送时交给 Controller
   * 组装多模态消息。属于“未提交的用户草稿”，不进 AgentState——状态源
   * 只放已确定的消息，草稿与输入框文本同级。
   */
  private pendingImages: ImageAttachment[] = [];

  /** 创建未连接的 Widget，并恢复脚本加载前注入的 JavaScript 属性。 */
  constructor() {
    super();
    this.shadow = this.attachShadow({ mode: 'open' });
    this.upgradeHttpTransportProperty();
    this.upgradeRuntimeOptionsProperty();
  }

  /**
   * 读取当前宿主 HTTP 传输层。
   *
   * <p>该属性为 JavaScript 属性而非 HTML 字符串属性，宿主可在元素连接前
   * 注入已有请求链；运行中替换时会销毁旧 Controller 并重新装配。
   */
  get httpTransport(): HttpTransport | undefined {
    return this.configuredHttpTransport;
  }

  /** 替换宿主 HTTP 传输层；运行中替换会整体重建 Controller。 */
  set httpTransport(transport: HttpTransport | undefined) {
    if (this.configuredHttpTransport === transport) {
      return;
    }
    this.configuredHttpTransport = transport;
    this.rebuildControllerIfRunning();
  }

  /** 读取当前默认 Runtime 的显式配置。 */
  get runtimeOptions(): WidgetRuntimeOptions | undefined {
    return this.configuredRuntimeOptions;
  }

  /**
   * 注入执行限额、Hook 与 Interceptor；运行中替换会完整重建 Controller。
   * 自定义 Engine 等更深层替换应使用 Headless Agent 工厂，不进入 Widget API。
   */
  set runtimeOptions(options: WidgetRuntimeOptions | undefined) {
    if (this.configuredRuntimeOptions === options) {
      return;
    }
    this.configuredRuntimeOptions = options;
    this.rebuildControllerIfRunning();
  }

  /**
   * 返回当前 Agent 与 Inspector 共用的 Tool Registry。
   *
   * <p>该属性没有 setter，Widget 不允许运行期替换 Registry；
   * 元素尚未连接或已断开时返回 null。宿主需要可靠感知重建时，
   * 应监听可冒泡、可穿越 Shadow DOM 的 patchbridge-agent-ready 事件。
   */
  get toolRegistry(): ToolRegistry | null {
    return this.controller?.getToolRegistry() ?? null;
  }

  /**
   * 在当前 Agent 的唯一 Registry 中注册纯前端 Tool。
   *
   * <p>注册直接委托给 Controller，不会在 Widget 复制 Tool 状态。
   * 未收到 ready 前调用会明确失败，避免 Tool 落入无人消费的临时 Registry。
   */
  registerTool(tool: BrowserTool): ToolRegistration {
    if (this.controller == null) {
      throw new Error(
        '<patchbridge-agent> 尚未启动，请在 patchbridge-agent-ready 事件后注册 Tool',
      );
    }
    return this.controller.registerTool(tool);
  }

  /**
   * 恢复元素升级前设置的 httpTransport 属性。
   *
   * <p>宿主可能在 Widget 脚本加载前就向未升级的元素赋值，此时会形成
   * 遮蔽 class setter 的自有属性。删除并重新赋值可保证两种脚本加载顺序语义一致。
   */
  private upgradeHttpTransportProperty(): void {
    if (!Object.prototype.hasOwnProperty.call(this, 'httpTransport')) {
      return;
    }
    const transport = this.httpTransport;
    Reflect.deleteProperty(this, 'httpTransport');
    this.httpTransport = transport;
  }

  /** 恢复元素升级前设置的 runtimeOptions，支持监听器先装配、脚本后加载。 */
  private upgradeRuntimeOptionsProperty(): void {
    if (!Object.prototype.hasOwnProperty.call(this, 'runtimeOptions')) {
      return;
    }
    const options = this.runtimeOptions;
    Reflect.deleteProperty(this, 'runtimeOptions');
    this.runtimeOptions = options;
  }

  /** 元素连接后建立 DOM 骨架并装配 Controller。 */
  connectedCallback(): void {
    this.buildSkeleton();
    this.startController();
  }

  /** 元素断开时释放 Controller 和全部异步工作。 */
  disconnectedCallback(): void {
    this.stopController();
  }

  /**
   * 应用运行期 HTML 属性变化。
   *
   * <p>endpoint 改变 HTTP 依赖、call-trace 改变采集配置，都必须重建 Controller；
   * title 与 login-url 仅影响 View，复用当前 Controller 的只读快照重绘，
   * 不能创建第二份 Agent 状态。
   */
  attributeChangedCallback(
    name: string,
    oldValue: string | null,
    newValue: string | null,
  ): void {
    if (oldValue === newValue) {
      return;
    }
    if (name === 'endpoint' || name === 'call-trace') {
      this.rebuildControllerIfRunning();
      return;
    }
    if (name === 'title' || name === 'login-url') {
      this.renderCurrentSnapshotIfRunning();
    }
  }

  /** 当前 endpoint 属性（缺省回落 /ai）。 */
  private get endpoint(): string {
    return this.getAttribute('endpoint') ?? '/ai';
  }

  /**
   * 当前调用轨迹采集模式；缺省 off（不采集、不创建 Hook、不访问 localStorage）。
   * 非法取值直接抛错：采集边界是安全默认值，不允许静默回落。
   */
  private get callTraceMode(): 'off' | 'memory' | 'persistent' {
    const value = this.getAttribute('call-trace') ?? 'off';
    if (value !== 'off' && value !== 'memory' && value !== 'persistent') {
      throw new Error(`call-trace 属性只接受 off/memory/persistent: ${value}`);
    }
    return value;
  }

  private get panelTitle(): string {
    return this.getAttribute('title') ?? 'AI 助手';
  }

  /** 宿主登录页地址：登录失效卡片的跳转目标（缺省 /login）。 */
  private get loginUrl(): string {
    return this.getAttribute('login-url') ?? '/login';
  }

  private startController(): void {
    const callTraceMode = this.callTraceMode;
    const controller = createAgentController({
      endpoint: this.endpoint,
      transport: this.configuredHttpTransport,
      runtime: this.configuredRuntimeOptions,
      // 轨迹采集默认关闭；宿主显式声明 call-trace 属性才创建采集 Hook。
      callTrace: callTraceMode === 'off' ? undefined : { mode: callTraceMode },
    });
    this.controller = controller;
    this.unsubscribe = controller.subscribe(state => this.render(state));
    void controller.initialize();

    // 事件必须在每次装配后重新发出：endpoint / transport 变化会创建
    // 新 Controller，宿主借此重新挂载 Local Tool、Inspector 与调用轨迹视图。
    const detail: PatchBridgeAgentReadyEventDetail = Object.freeze({
      toolRegistry: controller.getToolRegistry(),
      toolInspectionSource: controller.getToolInspectionSource(),
      callTraceSource: controller.getCallTraceSource(),
    });
    this.dispatchEvent(new CustomEvent<PatchBridgeAgentReadyEventDetail>(
      READY_EVENT_NAME,
      {
        detail,
        bubbles: true,
        composed: true,
      },
    ));
  }

  private stopController(): void {
    this.unsubscribe?.();
    this.unsubscribe = null;
    this.controller?.dispose();
    this.controller = null;
    // 重置渲染缓存，重连（reconnect）时强制全量重绘
    this.renderedMessagesKey = '';
    this.renderedConversationsKey = '';
  }

  /**
   * 依赖配置（endpoint / httpTransport / runtimeOptions）变化的统一重建入口。
   *
   * <p>只在 Controller 已运行时整体重建：Controller 非空即代表“已连接且
   * DOM 骨架已建”成立（骨架建好前不会创建 Controller，断开连接即销毁）。
   * 不能以 isConnected 作判据——脚本后加载升级时，构造函数会在元素已连接
   * 但骨架未建时恢复宿主预置的属性赋值，此时启动 Controller 会在渲染节点
   * 创建前触发订阅的同步渲染（render 访问未初始化的缓存节点直接抛错），
   * 异常逃出构造函数还会导致元素升级失败、connectedCallback 永不执行。
   */
  private rebuildControllerIfRunning(): void {
    if (this.controller == null) {
      return;
    }
    this.stopController();
    this.startController();
  }

  /**
   * 使用唯一 Controller 的当前快照重绘动态视图属性。
   * 元素尚未启动或已经断开时不保存快照；重连后的首次订阅会完成完整绘制。
   */
  private renderCurrentSnapshotIfRunning(): void {
    if (this.controller == null) {
      return;
    }
    this.render(this.controller.getState());
  }

  // ---------- DOM 骨架 ----------

  private buildSkeleton(): void {
    this.shadow.innerHTML = `
<style>${STYLES}</style>
<section class="panel" part="panel">
  <header class="panel-header" part="header">
    <span class="panel-title" part="title"></span>
    <details class="context-panel" part="context-panel">
      <summary class="context-badge" part="context-badge">上下文待计量</summary>
      <div class="context-popover" part="context-details"></div>
    </details>
    <button class="icon-btn new-btn" part="new-conversation-button" title="新会话">＋ 新会话</button>
  </header>
  <div class="body">
    <aside class="conversations" part="conversation-list">
      <div class="conversations-title">历史会话</div>
      <div class="conversation-list" part="conversation-items"></div>
    </aside>
    <div class="chat">
      <div class="messages-wrap" part="messages">
        <div class="messages"></div>
        <div class="streaming" hidden>
          <details class="reasoning" part="reasoning">
            <summary class="reasoning-summary"></summary>
            <pre class="reasoning-text"></pre>
          </details>
          <div class="bubble assistant" part="message assistant-message streaming-message">
            <div class="streaming-text md"></div>
            <span class="cursor"></span>
          </div>
        </div>
      </div>
      <div class="auth-required" part="auth-required" hidden>
        <div class="auth-card">
          <div class="auth-icon">🔒</div>
          <div class="auth-title">登录已失效</div>
          <div class="auth-desc">登录状态已过期或尚未登录，重新登录后继续对话</div>
          <button class="auth-btn" type="button">去登录</button>
        </div>
      </div>
      <div class="status-bar" part="status" hidden></div>
      <div class="max-tokens-notice" part="max-tokens-notice" role="status" hidden></div>
      <div class="error-bar" part="error" hidden></div>
      <div class="input-area" part="composer">
        <div class="attachment-bar" hidden></div>
        <div class="input-hint" hidden></div>
        <div class="input-row">
          <button class="attach-btn" part="attachment-button" title="添加图片">📎</button>
          <textarea part="input" rows="1" placeholder="输入问题，Enter 发送，Shift+Enter 换行"></textarea>
          <button class="send-btn" part="send-button">发送</button>
        </div>
        <input class="file-input" type="file"
               accept="image/png,image/jpeg,image/webp,image/gif" multiple hidden>
      </div>
    </div>
  </div>
</section>`;

    const $ = <T extends HTMLElement>(selector: string): T =>
      this.shadow.querySelector(selector) as T;

    this.listEl = $('.conversation-list');
    this.messagesEl = $('.messages');
    this.scrollEl = $('.messages-wrap');
    this.streamingEl = $('.streaming');
    this.streamingBubbleEl = $('.streaming .bubble');
    this.streamingTextEl = $('.streaming-text');
    this.streamingReasoningEl = $('.streaming .reasoning');
    this.streamingReasoningSummaryEl = $('.streaming .reasoning-summary');
    this.streamingReasoningTextEl = $('.streaming .reasoning-text');
    this.statusEl = $('.status-bar');
    this.maxTokensNoticeEl = $('.max-tokens-notice');
    this.errorEl = $('.error-bar');
    this.authEl = $('.auth-required');
    this.inputEl = $('textarea');
    this.sendButtonEl = $('.send-btn');
    this.attachButtonEl = $('.attach-btn');
    this.fileInputEl = $('.file-input');
    this.attachmentBarEl = $('.attachment-bar');
    this.inputHintEl = $('.input-hint');
    this.panelTitleEl = $('.panel-title');
    this.contextBadgeEl = $('.context-badge');
    this.contextPopoverEl = $('.context-popover');
    // 登录失效卡片：跳转目标属于宿主配置，登录本身永远由宿主页面完成
    $('.auth-btn').addEventListener('click', () => {
      window.location.href = this.loginUrl;
    });

    // ---------- 用户意图 → Controller 调用（View 的全部“业务逻辑”） ----------
    $('.new-btn').addEventListener('click', () => {
      this.controller?.startNewConversation();
    });
    this.sendButtonEl.addEventListener('click', () => this.onSend());
    this.inputEl.addEventListener('keydown', event => {
      if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();
        this.onSend();
      }
    });
    // 图片附件：📎 唤起文件选择，读取为 data URL 后暂存待发送
    this.attachButtonEl.addEventListener('click', () => this.fileInputEl.click());
    this.fileInputEl.addEventListener('change', () => {
      const selectedFiles = this.fileInputEl.files;
      if (selectedFiles != null) {
        this.addFiles(selectedFiles);
      }
      // 复位 value 允许再次选择同一文件（change 只在值变化时触发）
      this.fileInputEl.value = '';
    });
    // 预览条移除按钮用事件委托：条目随每次选择整体重建，逐项绑定会丢失
    this.attachmentBarEl.addEventListener('click', event => {
      const target = event.target as HTMLElement;
      const removeBtn = target.closest<HTMLElement>('[data-remove-index]');
      if (removeBtn != null) {
        this.pendingImages.splice(Number(removeBtn.dataset.removeIndex), 1);
        this.renderAttachments();
      }
    });
    // 列表点击与删除使用事件委托：列表项是整体重建的，逐项绑定会随重绘丢失
    this.listEl.addEventListener('click', event => {
      const target = event.target as HTMLElement;
      const deleteBtn = target.closest<HTMLElement>('[data-delete]');
      if (deleteBtn) {
        void this.controller?.deleteConversation(deleteBtn.dataset.delete ?? '');
        return;
      }
      const item = target.closest<HTMLElement>('[data-conversation-id]');
      if (item) {
        void this.controller?.loadConversation(item.dataset.conversationId ?? '');
      }
    });
    this.statusEl.addEventListener('click', event => {
      const target = event.target as HTMLElement;
      if (target.dataset.action === 'confirm-approve') {
        this.controller?.approveTool();
      } else if (target.dataset.action === 'confirm-reject') {
        this.controller?.rejectTool();
      } else if (target.dataset.action === 'abort') {
        this.controller?.abort();
      }
    });
    this.contextPopoverEl.addEventListener('click', event => {
      const target = event.target as HTMLElement;
      if (target.dataset.action === 'compact-context') {
        void this.controller?.compactContext();
      }
    });
    this.errorEl.addEventListener('click', event => {
      const target = event.target as HTMLElement;
      if (target.dataset.action === 'reload-conversation') {
        const id = this.controller?.getState().conversation?.conversationId;
        if (id != null) {
          void this.controller?.loadConversation(id);
        }
      }
    });
  }

  private onSend(): void {
    const text = this.inputEl.value;
    const images = [...this.pendingImages];
    if (text.trim().length === 0 && images.length === 0) {
      return;
    }
    this.inputEl.value = '';
    this.pendingImages = [];
    this.renderAttachments();
    this.clearInputHint();
    void this.controller?.sendMessage(text, images);
  }

  // ---------- 图片附件（多模态输入） ----------

  /**
   * 图片附件约束：格式对齐 OpenAI 视觉接口（PNG / JPEG / WebP / 非动图 GIF）；
   * 单张 8MB——base64 内联会膨胀约 33%，仍远低于网关与模型请求上限；
   * 单条消息最多 4 张，防止无意义的超大请求。校验属于输入形态检查，
   * 与“空文本不发”同级，留在 View 层而不进 Controller。
   */
  private static readonly ACCEPTED_IMAGE_TYPES =
    new Set(['image/png', 'image/jpeg', 'image/webp', 'image/gif']);
  private static readonly MAX_IMAGE_BYTES = 8 * 1024 * 1024;
  private static readonly MAX_IMAGES_PER_MESSAGE = 4;

  /** 校验并读取用户选择的图片为 data URL，追加到待发送列表。 */
  private addFiles(files: FileList | File[]): void {
    const problems: string[] = [];
    const accepted: File[] = [];
    for (const file of Array.from(files)) {
      if (!PatchBridgeAgentElement.ACCEPTED_IMAGE_TYPES.has(file.type)) {
        problems.push(`${file.name}：仅支持 PNG / JPEG / WebP / GIF 图片`);
        continue;
      }
      if (file.size > PatchBridgeAgentElement.MAX_IMAGE_BYTES) {
        problems.push(`${file.name}：超过 8MB 上限`);
        continue;
      }
      if (this.pendingImages.length + accepted.length
          >= PatchBridgeAgentElement.MAX_IMAGES_PER_MESSAGE) {
        problems.push(`单条消息最多 ${PatchBridgeAgentElement.MAX_IMAGES_PER_MESSAGE} 张图片`);
        continue;
      }
      accepted.push(file);
    }
    if (problems.length > 0) {
      this.showInputHint(problems.join('；'));
    }
    for (const file of accepted) {
      const reader = new FileReader();
      reader.onload = () => {
        try {
          const data = extractBase64Data(String(reader.result), file.type);
          this.pendingImages.push({
            source: { type: 'base64', mediaType: file.type, data },
          });
        } catch (cause) {
          this.showInputHint(
            cause instanceof Error ? cause.message : `${file.name}：图片读取失败`,
          );
          return;
        }
        if (problems.length === 0) {
          this.clearInputHint();
        }
        this.renderAttachments();
      };
      reader.onerror = () => this.showInputHint(`${file.name}：图片读取失败`);
      reader.readAsDataURL(file);
    }
  }

  /** 待发送图片的缩略图预览条；数量为零时整体隐藏。 */
  private renderAttachments(): void {
    this.attachmentBarEl.hidden = this.pendingImages.length === 0;
    this.attachmentBarEl.innerHTML = this.pendingImages
      .map((attachment, index) => `
        <div class="attachment-chip">
          <img src="${escapeHtml(imageSourceUrl(attachment.source))}" alt="待发送图片">
          <button class="chip-remove" data-remove-index="${index}" title="移除图片">×</button>
        </div>`)
      .join('');
  }

  /** 输入区上方的校验提示：只提示当前这次选择的问题，不进入全局错误条。 */
  private showInputHint(message: string): void {
    this.inputHintEl.hidden = false;
    this.inputHintEl.textContent = message;
  }

  private clearInputHint(): void {
    this.inputHintEl.hidden = true;
    this.inputHintEl.textContent = '';
  }

  // ---------- State → DOM ----------

  private render(state: AgentState): void {
    this.panelTitleEl.textContent = this.panelTitle;
    this.renderConversations(state);
    this.renderMessages(state);
    this.renderContextWindow(state);
    this.renderStreaming(state);
    this.renderStatusBar(state);
    this.renderRunOutcome(state);
    this.renderErrorBar(state);
    this.renderAuthRequired(state);
    this.renderInputArea(state);
  }

  /**
   * 展示服务端模型窗口、当前计量与最近检查点；摘要只在该调试面板出现，
   * 不伪装成聊天消息，也不参与消息区渲染。
   */
  private renderContextWindow(state: AgentState): void {
    const percentage = state.contextWindow.percentage;
    this.contextBadgeEl.textContent = percentage == null
      ? '上下文待计量'
      : `上下文 ${Math.round(percentage * 100)}%`;
    const configuration = state.contextConfiguration;
    if (configuration == null) {
      this.contextPopoverEl.textContent = '正在读取模型窗口配置…';
      return;
    }
    const current = state.contextWindow.currentTokens;
    const source = state.contextWindow.source === 'estimated' ? '压缩后估算' : 'Provider';
    const checkpoint = state.modelContext.checkpoint;
    const idle = busyLabel(state.status) == null;
    const canCompact = idle
      && current != null
      && state.contextWindow.compactable;
    const progress = percentage == null ? 0 : Math.round(percentage * 100);
    this.contextPopoverEl.innerHTML = `
      <div class="context-row"><span>当前</span><b>${current == null ? '等待首次模型计量' : `${formatTokens(current)} · ${source}`}</b></div>
      <div class="context-row"><span>窗口</span><b>${formatTokens(configuration.contextWindowTokens)}</b></div>
      <div class="context-progress" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${progress}">
        <span style="width:${progress}%"></span>
      </div>
      <div class="context-note">达到 ${formatTokens(configuration.automaticThresholdTokens)}（80%）自动压缩；保留近期 ${formatTokens(configuration.keepRecentTokens)}</div>
      ${checkpoint == null ? '<div class="context-note">尚未生成压缩检查点</div>' : `
        <div class="context-row"><span>已压缩</span><b>${checkpoint.compactionCount} 次 · ${checkpoint.trigger === 'manual' ? '手动' : '自动'}</b></div>
        <div class="context-note">${formatTokens(checkpoint.tokensBefore)} → 约 ${formatTokens(checkpoint.estimatedTokensAfter)} · ${escapeHtml(formatTimestamp(checkpoint.compactedAt))}</div>
        <details class="checkpoint-summary"><summary>查看最近摘要</summary><pre>${escapeHtml(checkpoint.summary)}</pre></details>`}
      <button class="context-compact-btn" part="context-compact-button" data-action="compact-context"${canCompact ? '' : ' disabled'}>立即压缩</button>`;
  }

  private renderConversations(state: AgentState): void {
    // 键包含当前选中会话：高亮属于列表渲染的一部分，切换会话时必须重建
    const key = `${state.conversations.map(c => c.conversationId).join(',')}|${state.conversation?.conversationId ?? ''}`;
    if (key === this.renderedConversationsKey) {
      return;
    }
    this.renderedConversationsKey = key;
    const currentId = state.conversation?.conversationId;
    this.listEl.innerHTML = state.conversations
      .map(conversation => {
        const active = conversation.conversationId === currentId ? ' active' : '';
        const title = escapeHtml(conversation.title ?? '未命名会话');
        // conversationId 与 title 一样来自 HTTP 响应，属于不可信输入；
        // 进入属性插值前必须转义，防止伪造 id 携带属性逃逸载荷（二次审计 Q-07）。
        const id = escapeHtml(conversation.conversationId);
        return `<div class="conversation-item${active}" data-conversation-id="${id}">
          <span class="conversation-title">${title}</span>
          <button class="icon-btn delete-btn" data-delete="${id}" title="删除会话">×</button>
        </div>`;
      })
      .join('');
  }

  private renderMessages(state: AgentState): void {
    // 'draft' 表示尚未保存的新会话：与已保存会话的 ID 不会撞名
    const key = `${state.conversation?.conversationId ?? 'draft'}#${state.messages
      .map(message => message.id)
      .join(',')}`;
    if (key === this.renderedMessagesKey) {
      return;
    }
    this.renderedMessagesKey = key;
    this.messagesEl.innerHTML = state.messages
      .map(message => renderMessage(message))
      .join('');
    this.scrollToBottom();
  }

  private renderStreaming(state: AgentState): void {
    const streaming = state.streamingAssistant;
    const hasContent = streaming != null && streaming.content.length > 0;
    const hasReasoning = streaming != null && streaming.reasoning.length > 0;
    this.streamingEl.hidden = !hasContent && !hasReasoning;
    this.streamingBubbleEl.hidden = !hasContent;
    this.streamingReasoningEl.hidden = !hasReasoning;
    if (streaming == null) {
      return;
    }
    // 思考进行中（有思考、无正文）自动展开；正文开始或流式结束自动收起
    const thinking = hasReasoning && !hasContent;
    this.streamingReasoningEl.open = thinking;
    this.streamingReasoningSummaryEl.textContent = thinking ? '🧠 正在思考…' : '🧠 已深度思考';
    this.streamingReasoningTextEl.textContent = streaming.reasoning;
    this.streamingTextEl.innerHTML = renderMarkdown(streaming.content);
    this.scrollToBottom();
  }

  private renderStatusBar(state: AgentState): void {
    const confirmation = state.pendingConfirmation;
    if (confirmation != null) {
      this.statusEl.hidden = false;
      const tool = confirmation.tool;
      const args = escapeHtml(JSON.stringify(confirmation.arguments, null, 2));
      this.statusEl.innerHTML = `
        <div class="confirmation">
          <div class="confirmation-title">⚠ 即将执行危险操作：<b>${escapeHtml(tool.title ?? tool.name)}</b></div>
          <div class="confirmation-args">${args}</div>
          <div class="confirmation-actions">
            <button class="primary" data-action="confirm-approve">允许执行</button>
            <button data-action="confirm-reject">拒绝</button>
          </div>
        </div>`;
      return;
    }
    const busyText = busyLabel(state.status);
    if (busyText == null) {
      this.statusEl.hidden = true;
      this.statusEl.innerHTML = '';
      return;
    }
    this.statusEl.hidden = false;
    const canAbort = state.status === 'streaming'
      || state.status === 'calling-tool'
      || state.status === 'compacting-context';
    this.statusEl.innerHTML = `<span class="busy">${busyText}</span>${
      canAbort ? '<button class="link-btn" data-action="abort">停止</button>' : ''
    }`;
  }

  /**
   * 展示模型输出上限终态。
   *
   * <p>max-tokens 已经产生可保存的稳定消息，因此不能进入错误条；独立语义节点
   * 既允许它与保存状态或保存错误同时存在，也允许宿主用 ::part 完整替换样式。
   */
  private renderRunOutcome(state: AgentState): void {
    if (state.runOutcome?.type !== 'max-tokens') {
      this.maxTokensNoticeEl.hidden = true;
      this.maxTokensNoticeEl.textContent = '';
      return;
    }
    this.maxTokensNoticeEl.hidden = false;
    this.maxTokensNoticeEl.textContent =
      '本次回答达到模型输出上限，内容可能不完整；可以继续发送消息让 Agent 补充。';
  }

  private renderErrorBar(state: AgentState): void {
    // 登录失效由居中登录卡片接管展示，错误条不再重复提示
    if (state.error == null || state.error.code === 'AUTH_REQUIRED') {
      this.errorEl.hidden = true;
      this.errorEl.innerHTML = '';
      return;
    }
    // 展示错误码 + 完整消息：错误码用于与服务端审计记录对账，消息必须完整可读
    this.errorEl.hidden = false;
    const conflict = state.error.code === 'CONVERSATION_CONFLICT';
    this.errorEl.innerHTML = `<span class="error-text">[${escapeHtml(state.error.code)}] ${escapeHtml(state.error.message)}</span>${
      conflict ? '<button class="link-btn" data-action="reload-conversation">重新加载会话</button>' : ''
    }`;
  }

  /** 登录失效（AUTH_REQUIRED）：覆盖层卡片是唯一入口，输入区同时禁用。 */
  private renderAuthRequired(state: AgentState): void {
    this.authEl.hidden = state.error?.code !== 'AUTH_REQUIRED';
  }

  private renderInputArea(state: AgentState): void {
    // 登录失效时禁用输入：继续发送只会得到同样的 401
    const authRequired = state.error?.code === 'AUTH_REQUIRED';
    const busy = busyLabel(state.status) != null;
    this.inputEl.disabled = busy || authRequired;
    // 附件按钮与输入框同策略：忙碌 / 失效期间不允许追加图片
    this.attachButtonEl.disabled = busy || authRequired;
    // 流式期间发送按钮切换为停止按钮，避免用户在禁用状态下失去中断手段
    if (state.status === 'streaming'
      || state.status === 'calling-tool'
      || state.status === 'compacting-context') {
      this.sendButtonEl.textContent = '停止';
      this.sendButtonEl.disabled = false;
      this.sendButtonEl.onclick = () => this.controller?.abort();
    } else {
      this.sendButtonEl.textContent = '发送';
      this.sendButtonEl.disabled = busy || authRequired;
      this.sendButtonEl.onclick = null;
    }
  }

  /** 仅当用户位于底部附近时自动跟随滚动，避免打断回看历史。 */
  private scrollToBottom(): void {
    const distance = this.scrollEl.scrollHeight - this.scrollEl.scrollTop - this.scrollEl.clientHeight;
    if (distance < 120) {
      this.scrollEl.scrollTop = this.scrollEl.scrollHeight;
    }
  }
}

/** 单条稳定消息按 ContentBlock 顺序渲染；模型与 Tool 输出始终先转义。 */
function renderMessage(message: AgentMessage): string {
  return `<div class="message-blocks" data-message-id="${escapeHtml(message.id)}">${message.blocks
    .map(block => renderContentBlock(message.role, block))
    .join('')}</div>`;
}

/** 按角色渲染一个稳定内容块，不重排同一消息中的语义顺序。 */
function renderContentBlock(
  role: AgentMessage['role'],
  block: ContentBlock,
): string {
  switch (block.type) {
    case 'text': {
      const body = role === 'assistant'
        ? `<div class="md">${renderMarkdown(block.text)}</div>`
        : escapeHtml(block.text);
      const visualRole = role === 'user' ? 'user' : 'assistant';
      return `<div class="bubble ${visualRole}" part="message content-block text-block ${visualRole}-message">${body}</div>`;
    }
    case 'image':
      return `<div class="bubble ${role === 'user' ? 'user' : 'assistant'}" part="message content-block image-block ${role}-message"><img class="chat-image" src="${escapeHtml(imageSourceUrl(block.source))}" alt="消息中的图片"></div>`;
    case 'reasoning':
      return `<details class="reasoning" part="message content-block reasoning-block"><summary class="reasoning-summary">🧠 深度思考</summary><pre class="reasoning-text">${escapeHtml(block.text)}</pre></details>`;
    case 'tool-call':
      return `<div class="bubble assistant tool-note" part="message content-block tool-call-block assistant-message"><div class="tool-tag">⚙ 调用工具：${escapeHtml(block.name)}</div></div>`;
    case 'tool-result':
      return `<details class="tool-result" part="message content-block tool-result-block tool-message"><summary>🔧 ${escapeHtml(block.name)} 执行结果</summary><pre>${escapeHtml(toolResultText(block.content))}</pre></details>`;
    default:
      return assertNeverBlock(block);
  }
}

/** 第一版 Tool Result 只有文本内容块；统一连接供折叠结果展示。 */
function toolResultText(
  content: readonly { readonly type: 'text'; readonly text: string }[],
): string {
  return content.map(item => item.text).join('\n');
}

/** 厂商中立图片来源转换成浏览器 img 可用 URL。 */
function imageSourceUrl(source: ImageSource): string {
  if (source.type === 'url') {
    return source.url;
  }
  return `data:${source.mediaType};base64,${source.data}`;
}

/** FileReader data URL 只在 View 草稿边界拆解，稳定消息不保存复合字符串。 */
function extractBase64Data(dataUrl: string, mediaType: string): string {
  const prefix = `data:${mediaType};base64,`;
  if (!dataUrl.startsWith(prefix)) {
    throw new Error(`图片读取结果不是预期的 Base64 data URL: ${mediaType}`);
  }
  return dataUrl.slice(prefix.length);
}

/** ContentBlock 编译期穷尽检查。 */
function assertNeverBlock(block: never): never {
  throw new Error(`未处理的消息内容块: ${JSON.stringify(block)}`);
}

/** 忙碌状态 → 提示文案；null 表示空闲。 */
function busyLabel(status: AgentStatus2): string | null {
  switch (status) {
    case 'loading-conversations': return '正在加载会话列表…';
    case 'loading-conversation': return '正在加载会话…';
    case 'loading-tools': return '正在准备工具…';
    case 'compacting-context': return '正在压缩模型上下文…';
    case 'streaming': return '正在生成回答…';
    case 'calling-tool': return '正在执行工具…';
    case 'waiting-confirmation': return '等待确认…';
    case 'saving': return '正在保存会话…';
    default: return null;
  }
}

/** 把 token 数格式化为紧凑且仍可精确理解的界面文本。 */
function formatTokens(value: number): string {
  return value >= 1000
    ? `${(value / 1000).toLocaleString('zh-CN', { maximumFractionDigits: 1 })}k tokens`
    : `${value.toLocaleString('zh-CN')} tokens`;
}

/** 检查点时间使用浏览器当前时区展示，非法协议值不在 View 静默修正。 */
function formatTimestamp(value: string): string {
  const timestamp = new Date(value);
  if (Number.isNaN(timestamp.getTime())) {
    throw new Error(`上下文检查点时间格式非法: ${value}`);
  }
  return timestamp.toLocaleString('zh-CN');
}

/** 从 agent 包重导出的状态类型别名（避免渲染函数签名依赖推断）。 */
type AgentStatus2 = AgentState['status'];

export { PatchBridgeAgentElement };

declare global {
  /** 自定义元素标签与实例类型的 TypeScript 映射。 */
  interface HTMLElementTagNameMap {
    /** 默认 Agent 参考 View。 */
    'patchbridge-agent': PatchBridgeAgentElement;
  }

  /** Widget 对宿主页面公开的自定义事件类型。 */
  interface HTMLElementEventMap {
    /** Controller 及其唯一 Tool Registry 已经就绪。 */
    'patchbridge-agent-ready': CustomEvent<PatchBridgeAgentReadyEventDetail>;
  }
}

const STYLES = `
/* 关键：hidden 属性的 UA 样式会被下面的 display 声明覆盖，必须显式禁用展示。
   曾经因此出现过“面板与错误条从未隐藏、页面常显一条空红条”的缺陷。 */
[hidden] { display: none !important; }
:host {
  all: initial; display: block; height: 100%; color: inherit; font: inherit;
  /* 尺寸令牌属于结构契约，theme="none" 仍需保留可用的滚动和交互区域。 */
  --patchbridge-agent-panel-width: 100%;
  --patchbridge-agent-panel-height: 100%;
  --patchbridge-agent-panel-min-height: 420px;
  --patchbridge-agent-conversation-list-width: 200px;
  --patchbridge-agent-message-max-width: 82%;
  --patchbridge-agent-input-max-height: 110px;
  --patchbridge-agent-image-max-size: 220px;
  --patchbridge-agent-send-button-width: 64px;
}
/*
 * 参考主题的公共设计令牌。默认值只在启用参考主题时注入；
 * theme="none" 下未指定的视觉声明自然失效，宿主仍可自行提供同名令牌。
 */
:host(:not([theme="none"])) {
  color-scheme: light;
  --patchbridge-agent-font-family: -apple-system, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
  --patchbridge-agent-font-size: 14px;
  --patchbridge-agent-font-size-small: 12px;
  --patchbridge-agent-font-size-caption: 13px;
  --patchbridge-agent-font-size-title: 15px;
  --patchbridge-agent-font-size-large: 16px;
  --patchbridge-agent-font-weight-emphasis: 600;

  --patchbridge-agent-color-primary: #2563eb;
  --patchbridge-agent-color-primary-hover: #1d4ed8;
  --patchbridge-agent-color-primary-disabled: #93c5fd;
  --patchbridge-agent-color-on-primary: #ffffff;
  --patchbridge-agent-color-background: #ffffff;
  --patchbridge-agent-color-surface: #f9fafb;
  --patchbridge-agent-color-surface-muted: #f3f4f6;
  --patchbridge-agent-color-surface-hover: #eff6ff;
  --patchbridge-agent-color-surface-active: #dbeafe;
  --patchbridge-agent-color-text: #1f2937;
  --patchbridge-agent-color-text-subtle: #374151;
  --patchbridge-agent-color-text-muted: #6b7280;
  --patchbridge-agent-color-border: #e5e7eb;
  --patchbridge-agent-color-control-border: #d1d5db;
  --patchbridge-agent-color-danger: #b91c1c;
  --patchbridge-agent-color-danger-hover: #dc2626;
  --patchbridge-agent-color-danger-background: #fef2f2;
  --patchbridge-agent-color-danger-border: #fecaca;
  --patchbridge-agent-color-warning: #b45309;
  --patchbridge-agent-color-warning-background: #fffbeb;
  --patchbridge-agent-color-warning-border: #fde68a;
  --patchbridge-agent-color-reasoning: #7c3aed;
  --patchbridge-agent-color-reasoning-strong: #6d28d9;
  --patchbridge-agent-color-reasoning-background: #f5f3ff;
  --patchbridge-agent-color-code-background: #e8ebf0;
  --patchbridge-agent-color-code-block-background: #f6f8fa;
  --patchbridge-agent-color-overlay: rgba(255, 255, 255, .94);
  --patchbridge-agent-color-header-button-hover: rgba(255, 255, 255, .2);
  --patchbridge-agent-color-remove-button: rgba(17, 24, 39, .65);
  --patchbridge-agent-color-remove-button-hover: rgba(17, 24, 39, .85);
  --patchbridge-agent-shadow-popover: 0 12px 32px rgba(15, 23, 42, .18);

  --patchbridge-agent-spacing-2xs: 2px;
  --patchbridge-agent-spacing-xs: 4px;
  --patchbridge-agent-spacing-sm: 6px;
  --patchbridge-agent-spacing-md: 8px;
  --patchbridge-agent-spacing-lg: 10px;
  --patchbridge-agent-spacing-xl: 12px;
  --patchbridge-agent-spacing-2xl: 14px;
  --patchbridge-agent-spacing-3xl: 16px;
  --patchbridge-agent-spacing-4xl: 24px;
  --patchbridge-agent-spacing-5xl: 32px;
  --patchbridge-agent-radius-sm: 4px;
  --patchbridge-agent-radius-md: 6px;
  --patchbridge-agent-radius-lg: 8px;
  --patchbridge-agent-radius-message: 10px;
  --patchbridge-agent-radius-panel: 12px;
  --patchbridge-agent-border-width: 1px;
  --patchbridge-agent-disabled-opacity: .5;
}
* { box-sizing: border-box; font-family: var(--patchbridge-agent-font-family); }
button:focus-visible, textarea:focus-visible, summary:focus-visible, [data-conversation-id]:focus-visible {
  outline: 2px solid currentColor; outline-offset: 2px;
}
.panel {
  height: var(--patchbridge-agent-panel-height); min-height: var(--patchbridge-agent-panel-min-height);
  width: var(--patchbridge-agent-panel-width);
  display: flex; flex-direction: column; overflow: hidden;
  background: var(--patchbridge-agent-color-background);
  color: var(--patchbridge-agent-color-text);
  border-radius: var(--patchbridge-agent-radius-panel);
  border: var(--patchbridge-agent-border-width) solid var(--patchbridge-agent-color-border);
}
.panel-header {
  display: flex; align-items: center; gap: var(--patchbridge-agent-spacing-md);
  padding: var(--patchbridge-agent-spacing-lg) var(--patchbridge-agent-spacing-2xl);
  background: var(--patchbridge-agent-color-primary);
  color: var(--patchbridge-agent-color-on-primary); flex: none;
}
.panel-title {
  flex: 1; font-size: var(--patchbridge-agent-font-size-title);
  font-weight: var(--patchbridge-agent-font-weight-emphasis);
}
.context-panel { position: relative; flex: none; }
.context-badge {
  list-style: none; cursor: pointer; white-space: nowrap;
  padding: var(--patchbridge-agent-spacing-sm) var(--patchbridge-agent-spacing-lg);
  border-radius: 999px; font-size: var(--patchbridge-agent-font-size-small);
  background: var(--patchbridge-agent-color-header-button-hover);
}
.context-badge::-webkit-details-marker { display: none; }
.context-popover {
  position: absolute; z-index: 10; top: calc(100% + var(--patchbridge-agent-spacing-md)); right: 0;
  width: min(340px, 82vw); padding: var(--patchbridge-agent-spacing-2xl);
  border: var(--patchbridge-agent-border-width) solid var(--patchbridge-agent-color-border);
  border-radius: var(--patchbridge-agent-radius-lg);
  background: var(--patchbridge-agent-color-background);
  color: var(--patchbridge-agent-color-text);
  box-shadow: var(--patchbridge-agent-shadow-popover);
  font-size: var(--patchbridge-agent-font-size-small);
}
.context-row {
  display: flex; justify-content: space-between; gap: var(--patchbridge-agent-spacing-xl);
  margin-bottom: var(--patchbridge-agent-spacing-md);
}
.context-row span, .context-note { color: var(--patchbridge-agent-color-text-muted); }
.context-progress {
  height: 7px; overflow: hidden; margin: var(--patchbridge-agent-spacing-md) 0;
  border-radius: 999px; background: var(--patchbridge-agent-color-surface-muted);
}
.context-progress span {
  display: block; height: 100%; border-radius: inherit;
  background: var(--patchbridge-agent-color-primary);
}
.context-note { line-height: 1.5; margin: var(--patchbridge-agent-spacing-md) 0; }
.checkpoint-summary { margin: var(--patchbridge-agent-spacing-lg) 0; }
.checkpoint-summary summary { cursor: pointer; color: var(--patchbridge-agent-color-primary); }
.checkpoint-summary pre {
  max-height: 180px; overflow: auto; white-space: pre-wrap; word-break: break-word;
  padding: var(--patchbridge-agent-spacing-md); margin: var(--patchbridge-agent-spacing-md) 0;
  border-radius: var(--patchbridge-agent-radius-md);
  background: var(--patchbridge-agent-color-surface-muted);
}
.context-compact-btn {
  width: 100%; padding: var(--patchbridge-agent-spacing-md);
  border: none; border-radius: var(--patchbridge-agent-radius-md); cursor: pointer;
  background: var(--patchbridge-agent-color-primary); color: var(--patchbridge-agent-color-on-primary);
}
.context-compact-btn:disabled { cursor: not-allowed; opacity: var(--patchbridge-agent-disabled-opacity); }
.icon-btn {
  border: none; background: transparent; color: inherit; cursor: pointer;
  font-size: var(--patchbridge-agent-font-size); line-height: 1;
  padding: var(--patchbridge-agent-spacing-sm) var(--patchbridge-agent-spacing-md);
  border-radius: var(--patchbridge-agent-radius-sm);
}
.icon-btn:hover { background: var(--patchbridge-agent-color-header-button-hover); }
.body { display: flex; flex: 1; min-height: 0; }
@media (max-width: 720px) {
  .body { flex-direction: column; }
  .conversations {
    width: auto; max-height: 130px; border-right: none;
    border-bottom: var(--patchbridge-agent-border-width) solid var(--patchbridge-agent-color-border);
  }
}
.conversations {
  width: var(--patchbridge-agent-conversation-list-width); flex: none;
  border-right: var(--patchbridge-agent-border-width) solid var(--patchbridge-agent-color-border);
  display: flex; flex-direction: column; background: var(--patchbridge-agent-color-surface);
}
.conversations-title {
  padding: var(--patchbridge-agent-spacing-md) var(--patchbridge-agent-spacing-lg);
  font-size: var(--patchbridge-agent-font-size-small);
  color: var(--patchbridge-agent-color-text-muted); flex: none;
}
.conversation-list { flex: 1; overflow-y: auto; }
.conversation-item {
  display: flex; align-items: center; gap: var(--patchbridge-agent-spacing-xs);
  padding: var(--patchbridge-agent-spacing-md) var(--patchbridge-agent-spacing-lg); cursor: pointer;
  font-size: var(--patchbridge-agent-font-size-caption);
  color: var(--patchbridge-agent-color-text-subtle); border-left: 2px solid transparent;
}
.conversation-item:hover { background: var(--patchbridge-agent-color-surface-hover); }
.conversation-item.active {
  background: var(--patchbridge-agent-color-surface-active);
  border-left-color: var(--patchbridge-agent-color-primary);
}
.conversation-title { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.delete-btn { color: var(--patchbridge-agent-color-text-muted); font-size: var(--patchbridge-agent-font-size-caption); }
.delete-btn:hover { color: var(--patchbridge-agent-color-danger-hover); }
.chat { flex: 1; display: flex; flex-direction: column; min-width: 0; overflow: hidden; position: relative; }
/* 登录失效覆盖卡片：盖住整个聊天区，登录入口唯一且醒目 */
.auth-required {
  position: absolute; inset: 0; z-index: 5;
  display: flex; align-items: center; justify-content: center;
  background: var(--patchbridge-agent-color-overlay);
}
.auth-card {
  text-align: center;
  padding: var(--patchbridge-agent-spacing-4xl) var(--patchbridge-agent-spacing-5xl);
}
.auth-icon { font-size: 30px; line-height: 1; }
.auth-title {
  margin: var(--patchbridge-agent-spacing-lg) 0 var(--patchbridge-agent-spacing-xs);
  font-size: var(--patchbridge-agent-font-size-large);
  font-weight: var(--patchbridge-agent-font-weight-emphasis);
  color: var(--patchbridge-agent-color-text);
}
.auth-desc {
  font-size: var(--patchbridge-agent-font-size-caption);
  color: var(--patchbridge-agent-color-text-muted);
  margin-bottom: var(--patchbridge-agent-spacing-3xl);
}
.auth-btn {
  padding: var(--patchbridge-agent-spacing-md) 28px; border: none;
  border-radius: var(--patchbridge-agent-radius-lg); cursor: pointer;
  background: var(--patchbridge-agent-color-primary);
  color: var(--patchbridge-agent-color-on-primary);
  font-size: var(--patchbridge-agent-font-size);
}
.auth-btn:hover { background: var(--patchbridge-agent-color-primary-hover); }
.messages-wrap { flex: 1; overflow-y: auto; min-height: 0; }
.bubble {
  max-width: var(--patchbridge-agent-message-max-width);
  margin: var(--patchbridge-agent-spacing-sm) var(--patchbridge-agent-spacing-lg);
  padding: var(--patchbridge-agent-spacing-md) var(--patchbridge-agent-spacing-xl);
  border-radius: var(--patchbridge-agent-radius-message);
  font-size: var(--patchbridge-agent-font-size); line-height: 1.6; word-break: break-word;
}
.bubble.user {
  margin-left: auto; background: var(--patchbridge-agent-color-primary);
  color: var(--patchbridge-agent-color-on-primary); white-space: pre-wrap;
}
.bubble.assistant {
  background: var(--patchbridge-agent-color-surface-muted);
  color: var(--patchbridge-agent-color-text);
}
.tool-tag {
  margin-top: var(--patchbridge-agent-spacing-xs);
  font-size: var(--patchbridge-agent-font-size-small);
  color: var(--patchbridge-agent-color-text-muted);
}
/* 深度思考折叠块：位于气泡上方，视觉上与正文明确分层 */
.reasoning {
  margin: var(--patchbridge-agent-spacing-sm) var(--patchbridge-agent-spacing-lg) 0;
  font-size: var(--patchbridge-agent-font-size-small);
}
.reasoning-summary {
  cursor: pointer; color: var(--patchbridge-agent-color-reasoning); user-select: none;
  font-size: var(--patchbridge-agent-font-size-small);
  padding: var(--patchbridge-agent-spacing-2xs) 0;
}
.reasoning-text {
  margin: var(--patchbridge-agent-spacing-xs) 0 var(--patchbridge-agent-spacing-sm);
  padding: var(--patchbridge-agent-spacing-md) var(--patchbridge-agent-spacing-lg);
  background: var(--patchbridge-agent-color-reasoning-background);
  border-radius: var(--patchbridge-agent-radius-md);
  color: var(--patchbridge-agent-color-reasoning-strong); white-space: pre-wrap; word-break: break-word;
  max-height: 220px; overflow-y: auto; font-family: inherit;
}
.tool-result {
  margin: var(--patchbridge-agent-spacing-sm) var(--patchbridge-agent-spacing-lg);
  font-size: var(--patchbridge-agent-font-size-small);
  color: var(--patchbridge-agent-color-text-muted);
}
.tool-result summary { cursor: pointer; }
.tool-result pre {
  margin: var(--patchbridge-agent-spacing-sm) 0 0;
  padding: var(--patchbridge-agent-spacing-md);
  background: var(--patchbridge-agent-color-surface);
  border-radius: var(--patchbridge-agent-radius-md);
  overflow-x: auto; white-space: pre-wrap; word-break: break-all;
}
/* Markdown 子集样式：模型输出的结构化内容 */
.md p { margin: 0 0 var(--patchbridge-agent-spacing-sm); }
.md p:last-child { margin-bottom: 0; }
.md h1, .md h2, .md h3, .md h4, .md h5, .md h6 {
  margin: var(--patchbridge-agent-spacing-lg) 0 var(--patchbridge-agent-spacing-sm);
  font-weight: var(--patchbridge-agent-font-weight-emphasis);
  color: var(--patchbridge-agent-color-text);
}
.md h1 { font-size: var(--patchbridge-agent-font-size-large); }
.md h2 { font-size: var(--patchbridge-agent-font-size-title); }
.md h3, .md h4, .md h5, .md h6 { font-size: var(--patchbridge-agent-font-size); }
.md ul, .md ol { margin: var(--patchbridge-agent-spacing-xs) 0; padding-left: 20px; }
.md li { margin: var(--patchbridge-agent-spacing-2xs) 0; }
.md strong { font-weight: var(--patchbridge-agent-font-weight-emphasis); }
.md code {
  background: var(--patchbridge-agent-color-code-background); padding: 1px 5px;
  border-radius: var(--patchbridge-agent-radius-sm);
  font-family: ui-monospace, Consolas, monospace;
  font-size: var(--patchbridge-agent-font-size-small);
}
.md pre {
  margin: var(--patchbridge-agent-spacing-sm) 0;
  padding: var(--patchbridge-agent-spacing-md);
  background: var(--patchbridge-agent-color-code-block-background);
  border-radius: var(--patchbridge-agent-radius-md); overflow-x: auto;
}
.md pre code { background: none; padding: 0; display: block; }
.md blockquote {
  margin: var(--patchbridge-agent-spacing-xs) 0;
  padding: var(--patchbridge-agent-spacing-2xs) var(--patchbridge-agent-spacing-lg);
  border-left: 3px solid var(--patchbridge-agent-color-control-border);
  color: var(--patchbridge-agent-color-text-muted);
}
.md hr {
  border: none; border-top: var(--patchbridge-agent-border-width) solid var(--patchbridge-agent-color-border);
  margin: var(--patchbridge-agent-spacing-md) 0;
}
.status-bar {
  flex: none; padding: var(--patchbridge-agent-spacing-sm) var(--patchbridge-agent-spacing-xl);
  border-top: var(--patchbridge-agent-border-width) solid var(--patchbridge-agent-color-border);
  font-size: var(--patchbridge-agent-font-size-caption);
}
.status-bar .busy {
  color: var(--patchbridge-agent-color-text-muted);
  margin-right: var(--patchbridge-agent-spacing-md);
}
.max-tokens-notice {
  flex: none;
  padding: var(--patchbridge-agent-spacing-md) var(--patchbridge-agent-spacing-xl);
  background: var(--patchbridge-agent-color-warning-background);
  color: var(--patchbridge-agent-color-warning);
  border-top: var(--patchbridge-agent-border-width) solid var(--patchbridge-agent-color-warning-border);
  font-size: var(--patchbridge-agent-font-size-caption);
  white-space: pre-wrap;
  word-break: break-word;
}
.confirmation { padding: var(--patchbridge-agent-spacing-xs) 0; }
.confirmation-title {
  color: var(--patchbridge-agent-color-warning);
  font-size: var(--patchbridge-agent-font-size-caption);
}
.confirmation-args {
  margin: var(--patchbridge-agent-spacing-sm) 0;
  padding: var(--patchbridge-agent-spacing-md);
  background: var(--patchbridge-agent-color-warning-background);
  border: var(--patchbridge-agent-border-width) solid var(--patchbridge-agent-color-warning-border);
  border-radius: var(--patchbridge-agent-radius-md); font-family: ui-monospace, Consolas, monospace;
  font-size: var(--patchbridge-agent-font-size-small);
  white-space: pre-wrap; word-break: break-all; max-height: 140px; overflow-y: auto;
}
.confirmation-actions { display: flex; gap: var(--patchbridge-agent-spacing-md); }
.confirmation-actions button {
  padding: 5px var(--patchbridge-agent-spacing-2xl);
  border-radius: var(--patchbridge-agent-radius-md);
  border: var(--patchbridge-agent-border-width) solid var(--patchbridge-agent-color-control-border);
  background: var(--patchbridge-agent-color-background); cursor: pointer;
  font-size: var(--patchbridge-agent-font-size-caption);
  color: var(--patchbridge-agent-color-text);
}
.confirmation-actions button.primary {
  background: var(--patchbridge-agent-color-primary);
  border-color: var(--patchbridge-agent-color-primary);
  color: var(--patchbridge-agent-color-on-primary);
}
.error-bar {
  flex: none; padding: var(--patchbridge-agent-spacing-md) var(--patchbridge-agent-spacing-xl);
  background: var(--patchbridge-agent-color-danger-background);
  color: var(--patchbridge-agent-color-danger);
  border-top: var(--patchbridge-agent-border-width) solid var(--patchbridge-agent-color-danger-border);
  font-size: var(--patchbridge-agent-font-size-caption);
  display: flex; align-items: center; gap: var(--patchbridge-agent-spacing-md);
}
.error-text { flex: 1; min-width: 0; white-space: pre-wrap; word-break: break-word; }
.link-btn {
  border: none; background: transparent; color: var(--patchbridge-agent-color-primary);
  cursor: pointer; font-size: var(--patchbridge-agent-font-size-caption); padding: 0; flex: none;
}
.link-btn:hover { text-decoration: underline; }
.input-area {
  flex: none; padding: var(--patchbridge-agent-spacing-lg) var(--patchbridge-agent-spacing-lg) 0;
  border-top: var(--patchbridge-agent-border-width) solid var(--patchbridge-agent-color-border);
}
/* 待发送图片预览条：缩略图 + 移除按钮，随输入区整体出现 */
.attachment-bar {
  display: flex; gap: var(--patchbridge-agent-spacing-md); flex-wrap: wrap;
  padding: 0 0 var(--patchbridge-agent-spacing-md);
}
.attachment-chip {
  position: relative; width: 64px; height: 64px;
  border-radius: var(--patchbridge-agent-radius-md); overflow: hidden;
  border: var(--patchbridge-agent-border-width) solid var(--patchbridge-agent-color-border);
  background: var(--patchbridge-agent-color-surface);
}
.attachment-chip img { width: 100%; height: 100%; object-fit: cover; display: block; }
.chip-remove {
  position: absolute; top: 2px; right: 2px; width: 18px; height: 18px;
  border: none; border-radius: 50%; cursor: pointer;
  background: var(--patchbridge-agent-color-remove-button);
  color: var(--patchbridge-agent-color-on-primary);
  font-size: var(--patchbridge-agent-font-size-small); line-height: 18px;
  padding: 0;
}
.chip-remove:hover { background: var(--patchbridge-agent-color-remove-button-hover); }
/* 附件校验提示：只描述本次选择的问题，不与全局错误条混用 */
.input-hint {
  padding: 0 0 var(--patchbridge-agent-spacing-sm);
  font-size: var(--patchbridge-agent-font-size-small);
  color: var(--patchbridge-agent-color-danger);
}
.input-row {
  display: flex; gap: var(--patchbridge-agent-spacing-md);
  padding-bottom: var(--patchbridge-agent-spacing-lg);
}
.attach-btn {
  flex: none; width: 40px;
  border: var(--patchbridge-agent-border-width) solid var(--patchbridge-agent-color-control-border);
  border-radius: var(--patchbridge-agent-radius-lg);
  cursor: pointer; background: var(--patchbridge-agent-color-background);
  color: var(--patchbridge-agent-color-text);
  font-size: var(--patchbridge-agent-font-size-large);
}
.attach-btn:hover:not(:disabled) { background: var(--patchbridge-agent-color-surface-muted); }
.attach-btn:disabled { cursor: not-allowed; opacity: var(--patchbridge-agent-disabled-opacity); }
.input-row textarea {
  flex: 1; resize: none;
  border: var(--patchbridge-agent-border-width) solid var(--patchbridge-agent-color-control-border);
  border-radius: var(--patchbridge-agent-radius-lg);
  padding: var(--patchbridge-agent-spacing-md) var(--patchbridge-agent-spacing-lg);
  font-size: var(--patchbridge-agent-font-size); line-height: 1.5;
  max-height: var(--patchbridge-agent-input-max-height);
  background: var(--patchbridge-agent-color-background);
  color: var(--patchbridge-agent-color-text);
}
.input-row textarea:focus { border-color: var(--patchbridge-agent-color-primary); }
/* 用户气泡内的图片：限尺寸圆角展示，多图纵向排列 */
.chat-image {
  display: block; max-width: var(--patchbridge-agent-image-max-size);
  max-height: var(--patchbridge-agent-image-max-size);
  border-radius: var(--patchbridge-agent-radius-lg);
  margin: var(--patchbridge-agent-spacing-2xs) 0;
}
.send-btn {
  flex: none; width: var(--patchbridge-agent-send-button-width); border: none;
  border-radius: var(--patchbridge-agent-radius-lg); cursor: pointer;
  background: var(--patchbridge-agent-color-primary);
  color: var(--patchbridge-agent-color-on-primary);
  font-size: var(--patchbridge-agent-font-size);
}
.send-btn:disabled { background: var(--patchbridge-agent-color-primary-disabled); cursor: not-allowed; }
.cursor {
  display: inline-block; width: 2px; height: 14px; margin-left: 2px;
  background: currentColor; vertical-align: -2px; animation: blink 1s step-start infinite;
}
@keyframes blink { 50% { opacity: 0; } }
`;
