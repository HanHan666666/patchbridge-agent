/**
 * Browser Runtime 的有限扩展端口。
 *
 * <p>Hook 只观察稳定生命周期事实；Model/Tool Interceptor 才能包围对应调用。
 * 三类扩展都不接触 Controller、DOM 或 Runtime 私有可变状态，避免宿主定制反向
 * 侵入 Agent Loop。服务端已有同名调用拦截器，两者运行位置和安全边界完全不同。
 */
import type {
  AgentInterrupt,
} from './engine';
import type {
  ModelCallContext,
  ModelRequest,
  ModelStreamEvent,
  ModelStopReason,
  ModelUsage,
} from './clients/modelClient';
import type { ToolCallContext } from './clients/toolClient';
import type {
  AgentRunOutcome,
  JsonObject,
  ToolCallResult,
  ToolDefinition,
} from './types';

/** Hook 可见的最小执行上下文，不包含 ModelState 等 Provider 私有数据。 */
export interface AgentHookContext {
  /** 当前执行的统一追踪标识。 */
  readonly traceId: string;
  /** 新会话第一次调用时为 null。 */
  readonly conversationId: string | null;
}

/** 只读生命周期事件；刻意不包含逐 token 增量，避免观察插件拖慢模型流。 */
export type AgentLifecycleEvent =
  | {
      /** 执行开始事件。 */
      readonly type: 'execution-started';
      /** 本轮开始前已存在的稳定消息数量。 */
      readonly initialMessageCount: number;
      /** 本轮实际冻结的 Tool Registry revision。 */
      readonly toolRevision: number;
    }
  | {
      /** 单次模型调用开始事件。 */
      readonly type: 'model-call-started';
      /** 当前 Execution 内从一开始的调用序号。 */
      readonly callIndex: number;
      /** Runtime 为本次 Assistant 响应预分配的消息 ID。 */
      readonly responseMessageId: string;
    }
  | {
      /** 单次模型调用正常完成事件。 */
      readonly type: 'model-call-completed';
      /** 当前 Execution 内从一开始的调用序号。 */
      readonly callIndex: number;
      /** 与开始事件严格对应的响应消息 ID。 */
      readonly responseMessageId: string;
      /** Provider 已标准化的停止原因。 */
      readonly stopReason: ModelStopReason;
      /** 本次调用的 token 用量；正常完成事件只会携带 Provider 的非空计量。 */
      readonly usage: ModelUsage | null;
      /** 调用开始到首个非空正文、思考或 Tool 参数增量的耗时。 */
      readonly firstTokenLatencyMs: number;
      /** 首个非空内容增量到模型流完成的耗时；用于计算平均输出速度。 */
      readonly outputDurationMs: number;
    }
  | {
      /** 单次 Tool 调用准备开始事件。 */
      readonly type: 'tool-call-started';
      /** 模型生成的稳定 Tool Call ID。 */
      readonly callId: string;
      /** 本轮快照中的完整 Tool 名。 */
      readonly toolName: string;
      /** 已完成 JSON 对象校验的 Tool 参数。 */
      readonly arguments: JsonObject;
    }
  | {
      /** 单次 Tool 调用完成事件。 */
      readonly type: 'tool-call-completed';
      /** 与开始事件严格对应的 Tool Call ID。 */
      readonly callId: string;
      /** 本轮快照中的完整 Tool 名。 */
      readonly toolName: string;
      /** Tool 结果是否为业务失败。 */
      readonly isError: boolean;
    }
  | {
      /** Runtime 请求宿主处理人工中断的事件。 */
      readonly type: 'interrupt-requested';
      /** 含稳定 ID 和响应类型的中断载荷。 */
      readonly interrupt: AgentInterrupt;
    }
  | {
      /** 宿主已响应当前人工中断的事件。 */
      readonly type: 'interrupt-resolved';
      /** 被精确消费的中断 ID。 */
      readonly interruptId: string;
      /** 宿主给出的确认结果。 */
      readonly value: boolean;
    }
  | {
      /** Execution 以非异常终态收敛的事件。 */
      readonly type: 'execution-completed';
      /** 与 AgentRunResult 完全一致的成功、截断或取消语义。 */
      readonly outcome: AgentRunOutcome;
      /** 本轮最终产生的稳定消息数量。 */
      readonly addedMessageCount: number;
    }
  | {
      /** Execution 以异常结束的事件。 */
      readonly type: 'execution-failed';
      /** 已标准化的框架错误码。 */
      readonly errorCode: string;
      /** 可供宿主审计的错误消息。 */
      readonly errorMessage: string;
    };

/** 已经选定且不得被观察者改判的 Execution 终态事件。 */
export type AgentTerminalLifecycleEvent = Extract<
  AgentLifecycleEvent,
  { readonly type: 'execution-completed' | 'execution-failed' }
>;

/**
 * 只读同步 Hook。
 *
 * <p>同步是刻意的边界：Runtime 保证注册顺序与事件顺序一致。普通生命周期事件的
 * Hook 若抛错则本轮明确失败；终态已经线性化，处理终态事件的 Hook 若抛错只能进入
 * 独立 diagnostics，不能把 completed/cancelled/failed 改判成第二个终态。需要异步
 * 上报的 Hook 应自行写入宿主队列，不能让网络 I/O 阻塞模型流。
 */
export interface AgentHook {
  /** 观察一个生命周期事实；禁止改写事件、上下文或从终态异常推导第二终态。 */
  onEvent(event: AgentLifecycleEvent, context: AgentHookContext): void;
}

/**
 * 终态 Hook 自身失败的独立诊断事实。
 *
 * <p>终态一旦选定便不能由观察者异常改判，因此该事实不能再次通过 AgentHook 广播，
 * 否则同一个失败 Hook 会递归触发自身。Runtime 通过独立 diagnostics 回调报告它，
 * 同时继续把原终态按注册顺序交给剩余 Hook。
 */
export interface AgentHookFailure {
  /** 抛出异常的 Hook 在本轮固定快照中的零基索引。 */
  readonly hookIndex: number;
  /** Hook 正在观察的已冻结终态事件。 */
  readonly event: AgentTerminalLifecycleEvent;
  /** 与该终态事件对应的已冻结执行上下文。 */
  readonly context: AgentHookContext;
  /** Hook 原样抛出的异常；diagnostics 只能观察，不能改变 Execution 结果。 */
  readonly cause: unknown;
}

/** Model Interceptor 调用下一层时使用的端口。 */
export type ModelCallNext = (
  request: ModelRequest,
  context: ModelCallContext,
  signal: AbortSignal,
) => AsyncIterable<ModelStreamEvent>;

/** Browser Model 调用拦截器；适合上下文注入、流审计和调用策略。 */
export interface ModelInterceptor {
  /** 包围下一层 Model；每次调用只能执行一次 next。 */
  intercept(
    request: ModelRequest,
    context: ModelCallContext,
    signal: AbortSignal,
    next: ModelCallNext,
  ): AsyncIterable<ModelStreamEvent>;
}

/** Runtime 交给 Tool Interceptor 的不可变调用数据。 */
export interface BrowserToolInvocation {
  /** 本轮快照中的准确 Tool 定义。 */
  readonly tool: ToolDefinition;
  /** 已校验为 JSON 对象的模型参数。 */
  readonly arguments: JsonObject;
  /** 与服务端审计对齐的调用上下文。 */
  readonly context: ToolCallContext;
  /** 用户取消 Execution 时同步触发。 */
  readonly signal: AbortSignal;
}

/** Tool Interceptor 调用下一层时使用的端口。 */
export type ToolCallNext = (
  invocation: BrowserToolInvocation,
) => Promise<ToolCallResult>;

/** Browser Tool 调用拦截器；适合审批外的业务策略、审计和结果治理。 */
export interface ToolInterceptor {
  /** 包围下一层 Tool 调用；每次调用只能执行一次 next。 */
  intercept(
    invocation: BrowserToolInvocation,
    next: ToolCallNext,
  ): Promise<ToolCallResult>;
}

/** 复制 Hook 列表，阻止宿主在 Execution 中途改变调用顺序。 */
export function snapshotHooks(hooks: readonly AgentHook[]): readonly AgentHook[] {
  return Object.freeze([...hooks]);
}

/** 把 Model Interceptor 按注册顺序组合成单一调用端口。 */
export function composeModelInterceptors(
  interceptors: readonly ModelInterceptor[],
  terminal: ModelCallNext,
): ModelCallNext {
  return [...interceptors].reduceRight<ModelCallNext>(
    (next, interceptor) => (request, context, signal) =>
      interceptor.intercept(
        request,
        context,
        signal,
        onceModelNext(next),
      ),
    terminal,
  );
}

/** 把 Tool Interceptor 按注册顺序组合成单一调用端口。 */
export function composeToolInterceptors(
  interceptors: readonly ToolInterceptor[],
  terminal: ToolCallNext,
): ToolCallNext {
  return [...interceptors].reduceRight<ToolCallNext>(
    (next, interceptor) => invocation =>
      interceptor.intercept(invocation, onceToolNext(next)),
    terminal,
  );
}

/** Model middleware 的 next 只能消费一次，防止重复发起计费调用。 */
function onceModelNext(next: ModelCallNext): ModelCallNext {
  let called = false;
  return (request, context, signal) => {
    if (called) {
      throw new Error('ModelInterceptor.next 只能调用一次');
    }
    called = true;
    return next(request, context, signal);
  };
}

/** Tool middleware 的 next 只能消费一次，防止重复执行有副作用操作。 */
function onceToolNext(next: ToolCallNext): ToolCallNext {
  let called = false;
  return invocation => {
    if (called) {
      throw new Error('ToolInterceptor.next 只能调用一次');
    }
    called = true;
    return next(invocation);
  };
}
