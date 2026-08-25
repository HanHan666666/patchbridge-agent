/**
 * WebMCP Adapter：把浏览器原生 document.modelContext 发现的 Tool 映射进
 * PatchBridge Agent 的 Unified Tool Registry。
 *
 * <p>该包是显式 opt-in 的协议边界，不修改 Document 原型，也不提供 polyfill。
 * WebMCP 仍是演进中的 Community Group Draft，因此协议类型集中在这里；草案变化时
 * 只需替换 Adapter，不让实验性浏览器 API 渗透 Agent Core、Controller 或 Widget。
 *
 * <p>方言适配：Chrome 预览实现与现行草案在字符串化边界上不一致——getTools()
 * 返回的 inputSchema 是 JSON 字符串（草案规定对象），executeTool 的入参也必须
 * 是 JSON 字符串（草案规定对象，传对象会抛 Failed to parse input arguments）。
 * 两处差异都在本包内吸收，Registry 与 Runtime 始终只面对对象形态。
 */
import type {
  BrowserToolProvider,
  ExecutableTool,
  JsonObject,
  ToolProviderRegistration,
  ToolRegistry,
  ToolRegistrySnapshot,
} from '@patchbridge-agent/agent';

/** WebMCP 草案当前公开的风险提示。 */
export interface WebMcpToolAnnotations {
  /** 工具不会修改任何状态。 */
  readOnlyHint?: boolean;
  /** 工具输出包含注册者视角下的不可信内容。 */
  untrustedContentHint?: boolean;
}

/** document.modelContext.getTools() 返回的可执行工具句柄。 */
export interface WebMcpRegisteredTool {
  /** WebMCP Tool 稳定名称。 */
  name: string;
  /** 面向用户的显示标题。 */
  title?: string;
  /** 面向 Agent 的能力描述。 */
  description: string;
  /** WebMCP 省略 schema 时表示接收任意对象。草案规定返回 JSON 对象，但 Chrome
   *  预览实现注册时序列化存储、返回时不再解析，直接给 JSON 字符串；两种形态
   *  都要接受，由 Adapter 在进入 Registry 前统一还原成对象。 */
  inputSchema?: JsonObject | string;
  /** 注册 Tool 的 Window；Adapter 只原样回传给 executeTool。 */
  window: unknown;
  /** 注册 Tool 的页面 origin。 */
  origin: string;
  /** 当前草案只定义 readOnly 与 untrustedContent。 */
  annotations?: WebMcpToolAnnotations;
}

/** WebMCP ModelContext 的最小消费端端口，对齐当前 CG Draft。 */
export interface WebMcpModelContext extends EventTarget {
  /** 获取当前文档及允许来源子文档暴露给调用方的 Tool。 */
  getTools(options?: { fromOrigins?: readonly string[] }): Promise<readonly WebMcpRegisteredTool[]>;
  /** 通过浏览器保存的 RegisteredTool 句柄执行，返回规范定义的序列化字符串。
   *
   * <p>入参必须是 JSON 字符串：Chrome 预览实现直接对入参做 JSON.parse，传对象
   * 会抛 "Failed to parse input arguments"；草案规定的 object 形态尚无实现跟进，
   * Adapter 统一按当前唯一存在的实现（字符串方言）传参。 */
  executeTool(
    tool: WebMcpRegisteredTool,
    input: string,
    options?: { signal?: AbortSignal },
  ): Promise<string>;
}

/** 仅要求可读取 modelContext，便于宿主与测试传入 Document 的窄接口。 */
export interface WebMcpDocument {
  /** 不支持 WebMCP 的浏览器中该属性不存在。 */
  readonly modelContext?: WebMcpModelContext;
}

/** Adapter 的显式配置。 */
export interface WebMcpAdapterOptions {
  /** Registry 内 Provider 唯一 ID；接入多个上下文时必须分别指定。 */
  providerId?: string;
  /** 允许发现 Tool 的额外来源；省略时仅查询规范默认的同源文档。 */
  fromOrigins?: readonly string[];
  /** toolchange 的异步刷新无法返回 Promise，宿主必须明确接收错误。 */
  onRefreshError: (cause: unknown) => void;
}

/** 当前浏览器没有暴露 document.modelContext 时的稳定错误。 */
export class WebMcpUnavailableError extends Error {
  constructor() {
    super('当前浏览器未提供 document.modelContext，无法启用 WebMCP Adapter');
    this.name = 'WebMcpUnavailableError';
  }
}

/**
 * 把实现返回的 inputSchema 统一还原成 Registry 需要的 JSON 对象。
 *
 * <p>Chrome 字符串方言若直接透传，会在下游被当对象展开成字符索引表，
 * 模型与 Inspector 看到的“参数描述”变成无意义字符映射。解析失败按发现
 * 错误抛出，经 refresh / start 的既有错误通道交给宿主，不做静默兜底。
 */
function parseInputSchema(schema: JsonObject | string | undefined): JsonObject {
  if (schema == null) {
    return { type: 'object' };
  }
  if (typeof schema !== 'string') {
    return { ...schema };
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(schema);
  } catch (cause) {
    throw new Error(`WebMCP Tool inputSchema 不是合法 JSON：${
      cause instanceof Error ? cause.message : String(cause)
    }`);
  }
  if (parsed == null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('WebMCP Tool inputSchema 必须是 JSON 对象');
  }
  return parsed as JsonObject;
}

/**
 * WebMCP Tool Provider：每次 load 都同时捕获定义与 RegisteredTool 句柄。
 * 执行器因此与 Registry revision 绑定，规避草案本身指出的“注销后同名重注册”错配。
 */
export class WebMcpToolProvider implements BrowserToolProvider {
  /** Registry 内的 Provider 稳定标识。 */
  public readonly id: string;
  /** 宿主明确允许发现的跨源文档列表。 */
  private readonly fromOrigins: readonly string[] | undefined;

  /** 从单一 ModelContext 和显式发现范围创建 Provider。 */
  constructor(
    private readonly modelContext: WebMcpModelContext,
    options: Pick<WebMcpAdapterOptions, 'providerId' | 'fromOrigins'> = {},
  ) {
    this.id = options.providerId ?? 'webmcp';
    this.fromOrigins = options.fromOrigins == null
      ? undefined
      : Object.freeze([...options.fromOrigins]);
  }

  /** 发现当前 WebMCP Tool，并把定义与本次返回的可执行句柄绑定。 */
  async load(): Promise<readonly ExecutableTool[]> {
    const tools = this.fromOrigins == null
      ? await this.modelContext.getTools()
      : await this.modelContext.getTools({ fromOrigins: this.fromOrigins });
    return tools.map(tool => this.toExecutable(tool));
  }

  /** 把协议对象转换为 Registry 条目，协议差异不泄漏给 Runtime。 */
  private toExecutable(tool: WebMcpRegisteredTool): ExecutableTool {
    const readOnly = tool.annotations?.readOnlyHint === true;
    return {
      definition: {
        name: tool.name,
        title: tool.title ?? null,
        description: tool.description,
        inputSchema: parseInputSchema(tool.inputSchema),
        annotations: {
          readOnlyHint: readOnly,
          destructiveHint: false,
          idempotentHint: false,
          // 当前 WebMCP 没有确认提示；非只读调用采用框架保守确认边界。
          requireConfirmation: !readOnly,
          untrustedContentHint: tool.annotations?.untrustedContentHint === true,
        },
        source: 'WEBMCP',
        permissions: [],
      },
      invoke: async (arguments_, context, signal) => ({
        toolCallId: context.toolCallId,
        content: await this.modelContext.executeTool(
          tool,
          JSON.stringify(arguments_),
          signal == null ? undefined : { signal },
        ),
        isError: false,
      }),
    };
  }
}

/**
 * WebMCP 与 Registry 的生命周期连接器。
 * start/stop 明确控制监听范围，适合 SPA 页面挂载与卸载，不留下全局监听器。
 */
export class WebMcpAdapter {
  /** 把协议对象转换为 Registry 条目的窄 Provider。 */
  private readonly provider: WebMcpToolProvider;
  /** 宿主必填的异步刷新错误出口。 */
  private readonly onRefreshError: (cause: unknown) => void;
  /** 当前 Provider 在 Registry 中的生命周期句柄。 */
  private registration: ToolProviderRegistration | null = null;

  /** toolchange 事件没有可等待的返回值，错误通过强制回调交给宿主呈现或上报。 */
  private readonly handleToolChange = (): void => {
    const registration = this.registration;
    if (registration == null) {
      return;
    }
    void registration.refresh().catch(cause => {
      // stop 或重启后，旧事件的迟到错误不再属于当前 Adapter 生命周期。
      if (this.registration === registration) {
        this.onRefreshError(cause);
      }
    });
  };

  /** 创建未启动的 Adapter；只有 start 会实际修改 Registry。 */
  constructor(
    private readonly registry: ToolRegistry,
    private readonly modelContext: WebMcpModelContext,
    options: WebMcpAdapterOptions,
  ) {
    this.provider = new WebMcpToolProvider(modelContext, options);
    this.onRefreshError = options.onRefreshError;
  }

  /** 挂载 Provider、监听变化并完成首次发现；失败时回滚全部生命周期状态。 */
  async start(signal?: AbortSignal): Promise<ToolRegistrySnapshot> {
    if (this.registration != null) {
      throw new Error('WebMCP Adapter 已启动');
    }
    const registration = this.registry.addProvider(this.provider);
    this.registration = registration;
    this.modelContext.addEventListener('toolchange', this.handleToolChange);
    try {
      return await registration.refresh(signal);
    } catch (cause) {
      this.modelContext.removeEventListener('toolchange', this.handleToolChange);
      registration.dispose();
      this.registration = null;
      throw cause;
    }
  }

  /** 主动刷新 WebMCP Provider；可用于宿主提供“重新发现”入口。 */
  refresh(signal?: AbortSignal): Promise<ToolRegistrySnapshot> {
    if (this.registration == null) {
      throw new Error('WebMCP Adapter 尚未启动');
    }
    return this.registration.refresh(signal);
  }

  /** 幂等停止监听并从 Registry 移除全部 WebMCP Tool。 */
  stop(): void {
    if (this.registration == null) {
      return;
    }
    this.modelContext.removeEventListener('toolchange', this.handleToolChange);
    this.registration.dispose();
    this.registration = null;
  }
}

/**
 * 从 Document 显式连接 WebMCP；不支持时直接失败，让 Demo/宿主展示真实能力状态。
 */
export async function connectDocumentWebMcp(
  registry: ToolRegistry,
  document_: WebMcpDocument,
  options: WebMcpAdapterOptions,
  signal?: AbortSignal,
): Promise<WebMcpAdapter> {
  if (document_.modelContext == null) {
    throw new WebMcpUnavailableError();
  }
  const adapter = new WebMcpAdapter(registry, document_.modelContext, options);
  await adapter.start(signal);
  return adapter;
}
