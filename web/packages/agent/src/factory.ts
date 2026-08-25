/**
 * 组装工厂：为最常见的接入形态（同源部署 + 默认 Runtime）提供一行式创建。
 *
 * <p>设计原因：宿主系统通常只需要 endpoint 即可运行；自定义 Engine 或
 * 自定义 Model（改写模型调用策略）的接入点也收敛在这里，避免使用方了解内部装配顺序。
 */
import { HttpConversationClient } from './clients/conversationClient';
import type { ConversationClient } from './clients/conversationClient';
import { HttpModel } from './clients/modelClient';
import type { Model } from './clients/modelClient';
import { HttpToolClient } from './clients/toolClient';
import type { ToolClient } from './clients/toolClient';
import type { HttpTransport } from './clients/http';
import { DefaultAgentController } from './controller';
import type { AgentEngine } from './engine';
import {
  DEFAULT_AGENT_EXECUTION_LIMITS,
  DefaultAgentRuntime,
} from './runtime';
import type {
  AgentExecutionLimits,
  DefaultAgentRuntimeOptions,
} from './runtime';
import { CallTraceStore } from './callTrace';
import { BackendToolProvider, DefaultToolRegistry } from './toolRegistry';
import type { ToolRegistry } from './toolRegistry';

/** 创建 Controller 的可选依赖：全部提供时工厂只做装配不做默认构造。 */
export interface CreateAgentControllerOptions {
  /** 同源的 Agent API 根路径，如 "/ai"。 */
  endpoint: string;
  /** 自定义引擎；缺省使用 DefaultAgentRuntime。 */
  engine?: AgentEngine;
  /**
   * 宿主 HTTP 传输层；内置 Client 共享此实例，用于复用鉴权、
   * CSRF、Bearer 刷新与请求拦截链。显式提供的 Client 由宿主自行管理传输。
  */
  transport?: HttpTransport;
  /** 完整替换默认厂商中立 Model；不能与自定义 engine 同时提供。 */
  model?: Model;
  /** 默认 Runtime 扩展配置；limits 可逐项覆盖工厂文档化的有界默认值。 */
  runtime?: Omit<DefaultAgentRuntimeOptions, 'limits'> & {
    /** 只覆盖有明确宿主需求的预算；未出现的字段使用统一工厂默认值。 */
    readonly limits?: Partial<AgentExecutionLimits>;
  };
  /** 替换默认后端 Tool Client，仍由默认 Registry 包装为 Backend Provider。 */
  toolClient?: ToolClient;
  /**
   * 完整替换默认统一 Tool Registry；不得与 toolClient 同时提供，避免来源不明确。
  */
  toolRegistry?: ToolRegistry;
  /** 完整替换默认会话 Client。 */
  conversationClient?: ConversationClient;
  /** localStorage 上次会话缓存键。 */
  storageKey?: string;
  /**
   * 调用轨迹采集配置；缺省不采集——不创建轨迹存储、不挂采集 Hook、
   * 不访问 localStorage。生产宿主保持缺省即获得零采集默认值，
   * 需要调试时显式选择仅内存或持久化模式，两个决策不容许隐式混合。
   */
  callTrace?: {
    /** memory 只保留当前页内存；persistent 额外把终态轨迹写入 localStorage。 */
    readonly mode: 'memory' | 'persistent';
    /** localStorage 键前缀；仅 persistent 模式生效，省略用默认前缀。 */
    readonly storageKey?: string;
  };
}

/** 创建默认装配的 AgentController：View 唯一需要持有的对象。 */
export function createAgentController(
  options: CreateAgentControllerOptions,
): DefaultAgentController {
  const endpoint = options.endpoint;
  if (options.engine != null
    && (options.model != null || options.runtime != null)) {
    throw new Error('自定义 engine 不能与 model 或 runtime 配置同时提供');
  }
  if (options.toolRegistry != null && options.toolClient != null) {
    throw new Error('toolRegistry 与 toolClient 不能同时提供');
  }
  const tools = options.toolRegistry ?? new DefaultToolRegistry([
    new BackendToolProvider(
      options.toolClient ?? new HttpToolClient(endpoint, options.transport),
    ),
  ]);
  const conversations =
    options.conversationClient ?? new HttpConversationClient(endpoint, options.transport);
  // 轨迹采集是显式 opt-in：未配置时不创建存储、不追加默认 Hook，生产默认零采集。
  const callTrace = options.callTrace == null
    ? null
    : new CallTraceStore({
      storageKey: options.callTrace.storageKey,
      persist: options.callTrace.mode === 'persistent',
    });
  const engine = options.engine ?? new DefaultAgentRuntime(
    options.model ?? new HttpModel(endpoint, options.transport),
    {
      ...options.runtime,
      // 内部轨迹必须先于宿主 Hook 观察事件：即使宿主在 execution-started 抛错，
      // 轨迹也已经建立，随后仍能用同一次 execution-failed 正常封闭失败事实。
      hooks: callTrace == null
        ? [...(options.runtime?.hooks ?? [])]
        : [callTrace, ...(options.runtime?.hooks ?? [])],
      limits: Object.freeze({
        ...DEFAULT_AGENT_EXECUTION_LIMITS,
        ...(options.runtime?.limits ?? {}),
      }),
    },
  );
  return new DefaultAgentController({
    engine,
    conversations,
    tools,
    storageKey: options.storageKey,
    // 自定义 Engine 不接入生命周期 Hook：显式开启时 Store 仍可作为数据源暴露，
    // 由宿主自行接线喂给过程事实；未开启时为 null，Controller 暴露显式空源。
    callTrace,
  });
}
