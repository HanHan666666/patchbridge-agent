/**
 * Unified Tool Registry：浏览器 Agent 的唯一 Tool 目录与调用路由。
 *
 * <p>本文件把后端 Tool、页面 JavaScript Tool 和可选协议 Adapter 收敛为同一份
 * 不可变快照。Controller、Engine 与只读 Inspector 都消费该快照，避免各自拉取、
 * 过滤或拼装工具列表后产生“界面看到的 Tool 与模型实际可调用的 Tool 不一致”。
 *
 * <p>每次模型调用使用固定 revision 的快照；注册、注销或 Provider 刷新只会生成
 * 新快照，不会改变已经开始的一轮模型请求及其后续 Tool Call 路由。
 */
import type { ToolClient, ToolCallContext } from './clients/toolClient';
import type {
  JsonObject,
  ToolCallResult,
  ToolDefinition,
} from './types';
import { compileToolInputSchema } from './jsonSchema';
import { copyAndFreezeJsonObject } from './jsonValues';

/** Tool 执行函数获得的可信调用上下文；业务参数与链路字段保持分离。 */
export interface BrowserToolExecutionContext extends ToolCallContext {
  /** 用户中止当前 Agent Run 时同步触发。 */
  signal?: AbortSignal;
}

/** 页面主动注册的纯前端 Tool。 */
export interface BrowserTool {
  /** Tool 名在当前 Registry 内必须唯一。 */
  name: string;
  /** 面向用户的标题；为空时 Inspector 使用 name。 */
  title?: string | null;
  /** 提供给模型的能力描述。 */
  description: string;
  /**
   * 模型只能生成该 JSON Schema 声明的业务参数；Registry 在注册期编译并在执行边界强制校验。
   * 受支持子集：type（含联合）、properties、required、additionalProperties（布尔）、
   * enum、items、title/description；其他关键字注册时明确失败。
   */
  inputSchema: JsonObject;
  /**
   * 风险与确认提示；页面通常只需声明 readOnlyHint。
   * 未明确只读时默认要求用户确认，避免新增写操作因遗漏注记而直接执行。
   */
  annotations?: BrowserToolAnnotations | null;
  /**
   * 执行页面已有逻辑；可以调用当前 Store、前端 Service 或原有同源 API。
   * 返回字符串时直接回填模型，其他 JSON 值按 JSON 序列化后回填。
   */
  execute(
    arguments_: JsonObject,
    context: BrowserToolExecutionContext,
  ): unknown | Promise<unknown>;
}

/** 页面 Tool 的便捷注记；Registry 会补齐 Runtime 所需的完整安全字段。 */
export interface BrowserToolAnnotations {
  /** 为 true 表示绝不修改状态，同时默认取消确认。 */
  readOnlyHint?: boolean;
  /** 为 true 表示操作具有破坏性。 */
  destructiveHint?: boolean;
  /** 为 true 表示相同参数重复执行具有相同业务效果。 */
  idempotentHint?: boolean;
  /** 显式覆盖默认确认规则；写操作省略时为 true。 */
  requireConfirmation?: boolean;
  /** 返回内容包含可能影响模型的不可信外部数据。 */
  untrustedContentHint?: boolean;
}

/** Registry 内部及 Adapter Provider 使用的“定义 + 执行器”完整条目。 */
export interface ExecutableTool {
  /** 不含函数的公共定义，可以安全交给模型与 Inspector。 */
  definition: ToolDefinition;
  /** 执行并返回标准 Tool Call 结果。 */
  invoke(
    arguments_: JsonObject,
    context: ToolCallContext,
    signal?: AbortSignal,
  ): Promise<ToolCallResult>;
}

/** 可刷新 Tool 来源；协议与基础设施差异必须封装在 Provider 内。 */
export interface BrowserToolProvider {
  /** Provider 稳定标识，同一 Registry 内不得重复。 */
  readonly id: string;
  /** 原子加载该 Provider 当前的完整 Tool 集合。 */
  load(signal?: AbortSignal): Promise<readonly ExecutableTool[]>;
}

/** 单个页面 Tool 的生命周期句柄。 */
export interface ToolRegistration {
  /** 本次注册对应的公开定义。 */
  readonly definition: ToolDefinition;
  /** 幂等注销；注销后新快照不再包含该 Tool。 */
  dispose(): void;
}

/** 动态 Provider 的生命周期句柄，供 WebMCP 等可选 Adapter 驱动同步。 */
export interface ToolProviderRegistration {
  /** 只刷新当前 Provider，并原子发布新的 Registry 快照。 */
  refresh(signal?: AbortSignal): Promise<ToolRegistrySnapshot>;
  /** 幂等移除 Provider 及其全部 Tool。 */
  dispose(): void;
}

/**
 * 一次不可变 Tool 目录快照。
 *
 * <p>invoke 绑定生成快照时的执行器 Map，所以即使同名 Tool 随后注销并重新注册，
 * 已开始的模型请求仍会调用它最初看到的实现，不会发生定义与执行器错配。
 */
export interface ToolRegistrySnapshot {
  /** 每次成功发布完整目录后递增，用于观察者识别版本。 */
  readonly revision: number;
  /** 按名称排序的不可变公开定义。 */
  readonly tools: readonly ToolDefinition[];
  /** 调用该快照中的 Tool；未知名称会明确失败。 */
  invoke(
    name: string,
    arguments_: JsonObject,
    context: ToolCallContext,
    signal?: AbortSignal,
  ): Promise<ToolCallResult>;
}

/** Browser Tool Registry 公共端口。 */
export interface ToolRegistry {
  /** 刷新全部 Provider，并在全部成功后一次性发布新快照。 */
  refresh(signal?: AbortSignal): Promise<ToolRegistrySnapshot>;
  /** 获取当前不可变快照。 */
  snapshot(): ToolRegistrySnapshot;
  /** 注册页面本地 JavaScript Tool。 */
  register(tool: BrowserTool): ToolRegistration;
  /** 挂载可选 Tool Provider；首次数据由返回句柄显式 refresh。 */
  addProvider(provider: BrowserToolProvider): ToolProviderRegistration;
  /** 订阅目录；注册时立即收到当前快照。 */
  subscribe(listener: (snapshot: ToolRegistrySnapshot) => void): () => void;
}

/** 后端 Unified Tool Gateway 的 Provider Adapter。 */
export class BackendToolProvider implements BrowserToolProvider {
  public readonly id = 'backend';

  /** 后端发现与调用必须共享同一个 Client，保证 endpoint 与认证链一致。 */
  constructor(private readonly client: ToolClient) {}

  /** 把后端定义绑定到对应的 HTTP 调用器。 */
  async load(signal?: AbortSignal): Promise<readonly ExecutableTool[]> {
    const definitions = await this.client.list(signal);
    return definitions.map(definition => ({
      definition: copyDefinition(definition),
      invoke: (arguments_, context, invokeSignal) =>
        this.client.call(definition.name, arguments_, context, invokeSignal),
    }));
  }
}

/** Tool 重名错误；冲突必须由宿主显式解决，禁止来源优先级或静默覆盖。 */
export class ToolAlreadyRegisteredError extends Error {
  /** 保存发生冲突的稳定 Tool 名，便于 UI 和测试定位。 */
  constructor(public readonly toolName: string) {
    super(`Tool 已注册，禁止静默覆盖: ${toolName}`);
    this.name = 'ToolAlreadyRegisteredError';
  }
}

/** 默认统一 Registry：本地条目与 Provider 快照均采用 Copy-on-write 发布。 */
export class DefaultToolRegistry implements ToolRegistry {
  /** 当前页面直接注册的 Tool，以名称作唯一键。 */
  private readonly localTools = new Map<string, ExecutableTool>();
  /** 动态 Provider 与其最近一次成功完整集合。 */
  private readonly providers = new Map<string, ProviderState>();
  /** 只读快照观察者，不参与 Tool 调度。 */
  private readonly listeners = new Set<(snapshot: ToolRegistrySnapshot) => void>();
  /** 当前已原子发布的不可变快照。 */
  private currentSnapshot: ToolRegistrySnapshot = createSnapshot(0, []);
  /** 下一个成功发布的单调 revision。 */
  private nextRevision = 1;
  /** 全量刷新代次，防止慢响应覆盖后发请求。 */
  private refreshGeneration = 0;

  /** 创建 Registry；固定 Provider 仍通过同一挂载入口校验。 */
  constructor(providers: readonly BrowserToolProvider[] = []) {
    for (const provider of providers) {
      this.attachProvider(provider);
    }
  }

  /** 并行读取全部 Provider，只有完整成功且仍是最新刷新时才提交。 */
  async refresh(signal?: AbortSignal): Promise<ToolRegistrySnapshot> {
    const generation = ++this.refreshGeneration;
    const states = [...this.providers.values()].map(state => ({
      state,
      generation: ++state.refreshGeneration,
    }));
    const loaded = await Promise.all(states.map(async scheduled => ({
      scheduled,
      id: scheduled.state.provider.id,
      tools: await scheduled.state.provider.load(signal),
    })));
    if (generation !== this.refreshGeneration) {
      return this.currentSnapshot;
    }
    if (loaded.some(item =>
      this.providers.get(item.id) !== item.scheduled.state
      || item.scheduled.state.refreshGeneration !== item.scheduled.generation)) {
      return this.currentSnapshot;
    }
    const nextProviders = new Map<string, readonly ExecutableTool[]>();
    for (const item of loaded) {
      nextProviders.set(item.id, copyEntries(item.tools));
    }
    this.validateCombined(nextProviders, this.localTools);
    for (const scheduled of states) {
      scheduled.state.tools = nextProviders.get(scheduled.state.provider.id) ?? [];
    }
    return this.publish();
  }

  /** 返回当前快照，不触发任何 I/O。 */
  snapshot(): ToolRegistrySnapshot {
    return this.currentSnapshot;
  }

  /** 注册纯前端 Tool，并立即发布包含该 Tool 的新快照。 */
  register(tool: BrowserTool): ToolRegistration {
    const entry = fromBrowserTool(tool);
    this.assertNameAvailable(entry.definition.name);
    this.localTools.set(entry.definition.name, entry);
    this.publish();
    let disposed = false;
    return {
      definition: copyDefinition(entry.definition),
      dispose: () => {
        if (disposed) {
          return;
        }
        disposed = true;
        this.localTools.delete(entry.definition.name);
        this.publish();
      },
    };
  }

  /** 挂载空 Provider，由返回句柄显式决定首次刷新时机。 */
  addProvider(provider: BrowserToolProvider): ToolProviderRegistration {
    const state = this.attachProvider(provider);
    let disposed = false;
    return {
      refresh: async signal => {
        if (disposed) {
          throw new Error(`Tool Provider 已释放: ${provider.id}`);
        }
        const generation = ++state.refreshGeneration;
        const tools = copyEntries(await provider.load(signal));
        if (disposed
          || this.providers.get(provider.id) !== state
          || generation !== state.refreshGeneration) {
          return this.currentSnapshot;
        }
        const proposed = this.providerToolSets(provider.id, tools);
        this.validateCombined(proposed, this.localTools);
        state.tools = tools;
        return this.publish();
      },
      dispose: () => {
        if (disposed) {
          return;
        }
        disposed = true;
        state.refreshGeneration += 1;
        this.providers.delete(provider.id);
        if (state.tools.length > 0) {
          this.publish();
        }
      },
    };
  }

  /** 订阅后立即推送当前快照，便于只读视图无竞态首屏渲染。 */
  subscribe(listener: (snapshot: ToolRegistrySnapshot) => void): () => void {
    this.listeners.add(listener);
    listener(this.currentSnapshot);
    return () => this.listeners.delete(listener);
  }

  /** 挂载空 Provider 状态；数据必须由 refresh 显式加载。 */
  private attachProvider(provider: BrowserToolProvider): ProviderState {
    if (provider.id.trim().length === 0) {
      throw new Error('Tool Provider id 不能为空');
    }
    if (this.providers.has(provider.id)) {
      throw new Error(`Tool Provider 已注册: ${provider.id}`);
    }
    const state: ProviderState = { provider, tools: [], refreshGeneration: 0 };
    this.providers.set(provider.id, state);
    return state;
  }

  /** 检查新本地 Tool 不与任何现有来源冲突。 */
  private assertNameAvailable(name: string): void {
    if (this.localTools.has(name)) {
      throw new ToolAlreadyRegisteredError(name);
    }
    for (const state of this.providers.values()) {
      if (state.tools.some(tool => tool.definition.name === name)) {
        throw new ToolAlreadyRegisteredError(name);
      }
    }
  }

  /** 构造替换单一 Provider 后的候选数据，不提前修改当前状态。 */
  private providerToolSets(
    targetId: string,
    targetTools: readonly ExecutableTool[],
  ): Map<string, readonly ExecutableTool[]> {
    const proposed = new Map<string, readonly ExecutableTool[]>();
    for (const [id, state] of this.providers) {
      proposed.set(id, id === targetId ? targetTools : state.tools);
    }
    return proposed;
  }

  /** 对完整候选目录执行确定性重名检查。 */
  private validateCombined(
    providerSets: ReadonlyMap<string, readonly ExecutableTool[]>,
    locals: ReadonlyMap<string, ExecutableTool>,
  ): void {
    const names = new Set<string>();
    for (const name of locals.keys()) {
      if (names.has(name)) {
        throw new ToolAlreadyRegisteredError(name);
      }
      names.add(name);
    }
    for (const tools of providerSets.values()) {
      for (const tool of tools) {
        const name = tool.definition.name;
        if (names.has(name)) {
          throw new ToolAlreadyRegisteredError(name);
        }
        names.add(name);
      }
    }
  }

  /** 汇总当前条目并发布新的不可变 revision。 */
  private publish(): ToolRegistrySnapshot {
    const entries = [...this.localTools.values()];
    for (const state of this.providers.values()) {
      entries.push(...state.tools);
    }
    this.validateCombined(this.providerToolSets('', []), this.localTools);
    this.currentSnapshot = createSnapshot(this.nextRevision++, entries);
    for (const listener of [...this.listeners]) {
      listener(this.currentSnapshot);
    }
    return this.currentSnapshot;
  }
}

/** Registry 内单一 Provider 的当前不可变集合。 */
interface ProviderState {
  /** Adapter 本身。 */
  readonly provider: BrowserToolProvider;
  /** 最近一次完整加载成功的工具集合。 */
  tools: readonly ExecutableTool[];
  /** Provider 独立刷新代次，阻止慢请求覆盖更新的 toolchange 结果。 */
  refreshGeneration: number;
}

/** 把本地执行返回值严格转换为模型 Tool Result 文本。 */
function fromBrowserTool(tool: BrowserTool): ExecutableTool {
  validateBrowserTool(tool);
  // 注册期编译 Schema：不受支持的关键字或非法结构立即失败，不允许静默降级为"不校验"。
  const compiledSchema = compileToolInputSchema(tool.inputSchema, tool.name);
  const readOnly = tool.annotations?.readOnlyHint === true;
  const definition: ToolDefinition = {
    name: tool.name,
    title: tool.title ?? null,
    description: tool.description,
    inputSchema: copyAndFreezeJsonObject(tool.inputSchema),
    annotations: {
      readOnlyHint: readOnly,
      destructiveHint: tool.annotations?.destructiveHint === true,
      idempotentHint: tool.annotations?.idempotentHint === true,
      requireConfirmation: tool.annotations?.requireConfirmation ?? !readOnly,
      untrustedContentHint: tool.annotations?.untrustedContentHint === true,
    },
    source: 'FRONTEND_LOCAL',
    permissions: [],
  };
  return {
    definition,
    invoke: async (arguments_, context, signal) => {
      // 统一执行边界：模型参数是不可信输入，校验失败形成明确的 Tool 失败结果，
      // 页面 execute() 不会被调用，Tool 作者无需重复实现参数保护。
      const issue = compiledSchema.validate(arguments_);
      if (issue != null) {
        return {
          toolCallId: context.toolCallId,
          content: `${tool.name} 参数校验失败（${issue.path}）：${issue.message}；Tool 未执行`,
          isError: true,
        };
      }
      const value = await tool.execute(arguments_, { ...context, signal });
      return {
        toolCallId: context.toolCallId,
        content: serializeToolValue(value),
        isError: false,
      };
    },
  };
}

/** 拒绝不完整 Tool，避免无描述或无名称能力进入模型上下文。 */
function validateBrowserTool(tool: BrowserTool): void {
  if (!TOOL_NAME_PATTERN.test(tool.name)) {
    throw new Error(`Tool name 必须是 1-128 位字母、数字、点、下划线或连字符: ${tool.name}`);
  }
  if (tool.description.trim().length === 0) {
    throw new Error(`Tool description 不能为空: ${tool.name}`);
  }
  if (tool.inputSchema == null
    || typeof tool.inputSchema !== 'object'
    || Array.isArray(tool.inputSchema)) {
    throw new Error(`Tool inputSchema 必须是对象: ${tool.name}`);
  }
  if (typeof tool.execute !== 'function') {
    throw new Error(`Tool execute 必须是函数: ${tool.name}`);
  }
}

/** 对齐主流 Function Calling 与 WebMCP 当前草案的 Tool 名约束。 */
const TOOL_NAME_PATTERN = /^[A-Za-z0-9_.-]{1,128}$/u;

/** 字符串原样返回；其他合法 JSON 值必须可确定性序列化。 */
function serializeToolValue(value: unknown): string {
  if (typeof value === 'string') {
    return value;
  }
  const serialized = JSON.stringify(value);
  if (serialized == null) {
    throw new Error('纯前端 Tool 必须返回字符串或可 JSON 序列化的值');
  }
  return serialized;
}

/** 创建绑定固定执行器 Map 的不可变快照。 */
function createSnapshot(
  revision: number,
  sourceEntries: readonly ExecutableTool[],
): ToolRegistrySnapshot {
  const entries = [...sourceEntries]
    .map(entry => ({
      definition: copyDefinition(entry.definition),
      invoke: entry.invoke,
    }))
    .sort((left, right) => left.definition.name.localeCompare(right.definition.name));
  const byName = new Map(entries.map(entry => [entry.definition.name, entry]));
  const tools = Object.freeze(entries.map(entry => freezeDefinition(
    copyDefinition(entry.definition),
  )));
  return Object.freeze({
    revision,
    tools,
    invoke: async (
      name: string,
      arguments_: JsonObject,
      context: ToolCallContext,
      signal?: AbortSignal,
    ) => {
      const entry = byName.get(name);
      if (entry == null) {
        throw new Error(`Tool 不在当前执行快照中: ${name}`);
      }
      return entry.invoke(arguments_, context, signal);
    },
  });
}

/** 深度复制公共定义中的可变数组和一层 metadata，避免观察者修改 Registry。 */
function copyDefinition(definition: ToolDefinition): ToolDefinition {
  return {
    ...definition,
    inputSchema: copyAndFreezeJsonObject(definition.inputSchema),
    annotations: definition.annotations == null ? null : { ...definition.annotations },
    permissions: [...definition.permissions],
  };
}

/** 深度冻结公开定义，兑现 Snapshot 对嵌套 JSON Schema 的不可变承诺。 */
function freezeDefinition(definition: ToolDefinition): ToolDefinition {
  if (definition.annotations != null) {
    Object.freeze(definition.annotations);
  }
  Object.freeze(definition.permissions);
  return Object.freeze(definition);
}

/** 复制 Provider 返回的条目，并拒绝条目内重复名称。 */
function copyEntries(entries: readonly ExecutableTool[]): readonly ExecutableTool[] {
  const names = new Set<string>();
  return entries.map(entry => {
    const name = entry.definition.name;
    if (!TOOL_NAME_PATTERN.test(name)) {
      throw new Error(`Provider Tool name 不合法: ${name}`);
    }
    if (entry.definition.description.trim().length === 0) {
      throw new Error(`Provider Tool description 不能为空: ${name}`);
    }
    if (names.has(name)) {
      throw new ToolAlreadyRegisteredError(name);
    }
    names.add(name);
    return { definition: copyDefinition(entry.definition), invoke: entry.invoke };
  });
}
