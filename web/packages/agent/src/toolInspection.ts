/**
 * Tool 调试视图的数据源：在执行期间展示 Runtime 实际冻结的 Tool 快照。
 *
 * <p>Registry 会随着页面注册、WebMCP 变更或后端刷新持续发布新 revision；已经
 * 开始的 AgentExecution 则必须继续使用启动时快照。该适配器把两种生命周期
 * 区分开：执行中固定为 current-execution，空闲时跟随 current-registry，避免
 * 调试列表把“下一轮可用工具”误报成“本轮模型可调用工具”。
 */
import type { ToolDefinition } from './types';
import type { ToolRegistry, ToolRegistrySnapshot } from './toolRegistry';

/** 调试列表所展示快照的业务范围。 */
export type ToolInspectionScope = 'current-execution' | 'current-registry';

/** 不含调用器的只读 Tool 调试快照。 */
export interface ToolInspectionSnapshot {
  /** 对应 Unified Tool Registry 的稳定 revision。 */
  readonly revision: number;
  /** 与当前范围严格对应的完整 Tool 定义。 */
  readonly tools: readonly ToolDefinition[];
  /** 区分本轮冻结快照与空闲时当前目录。 */
  readonly scope: ToolInspectionScope;
}

/** 可插拔 Inspector 依赖的最小只读端口。 */
export interface ToolInspectionSource {
  /** 空闲时刷新 Registry；执行中返回本轮快照，不改变本轮能力。 */
  refresh(signal?: AbortSignal): Promise<ToolInspectionSnapshot>;
  /** 同步读取当前应该展示的快照。 */
  snapshot(): ToolInspectionSnapshot;
  /** 订阅快照；注册时立即收到当前值。 */
  subscribe(listener: (snapshot: ToolInspectionSnapshot) => void): () => void;
}

/**
 * Controller 内部使用的执行感知数据源。
 *
 * <p>activate/deactivate 只由 Controller 的 run 生命周期调用；Inspector 只能
 * 通过 ToolInspectionSource 读取，不能改变 Agent 的实际能力集合。
 */
export class ExecutionAwareToolInspectionSource implements ToolInspectionSource {
  /** 当前 Registry 的订阅释放函数。 */
  private readonly unsubscribeRegistry: () => void;
  /** Inspector 观察者集合。 */
  private readonly listeners = new Set<(
    snapshot: ToolInspectionSnapshot,
  ) => void>();
  /** 当前执行冻结的快照；null 表示 Agent 空闲。 */
  private activeSnapshot: ToolRegistrySnapshot | null = null;
  /** 最近一次可展示的纯数据快照。 */
  private currentSnapshot: ToolInspectionSnapshot;
  /** 释放后拒绝继续刷新或激活。 */
  private disposed = false;

  /** 绑定唯一 Registry，并立即建立空闲目录视图。 */
  constructor(private readonly registry: ToolRegistry) {
    this.currentSnapshot = toInspectionSnapshot(
      registry.snapshot(),
      'current-registry',
    );
    this.unsubscribeRegistry = registry.subscribe(snapshot => {
      if (this.activeSnapshot == null && !this.disposed) {
        this.publish(toInspectionSnapshot(snapshot, 'current-registry'));
      }
    });
  }

  /** 空闲时执行真实发现；本轮运行中不允许 Inspector 改变其能力范围。 */
  async refresh(signal?: AbortSignal): Promise<ToolInspectionSnapshot> {
    this.assertActive();
    if (this.activeSnapshot != null) {
      return this.currentSnapshot;
    }
    await this.registry.refresh(signal);
    return this.currentSnapshot;
  }

  /** 返回当前不可变调试快照。 */
  snapshot(): ToolInspectionSnapshot {
    return this.currentSnapshot;
  }

  /** 订阅后立即推送，避免 Inspector 首屏读取与订阅之间出现竞态。 */
  subscribe(listener: (snapshot: ToolInspectionSnapshot) => void): () => void {
    this.assertActive();
    this.listeners.add(listener);
    listener(this.currentSnapshot);
    return () => this.listeners.delete(listener);
  }

  /** AgentExecution 启动前固定展示它实际持有的同一个 Registry 快照。 */
  activate(snapshot: ToolRegistrySnapshot): void {
    this.assertActive();
    this.activeSnapshot = snapshot;
    this.publish(toInspectionSnapshot(snapshot, 'current-execution'));
  }

  /** AgentExecution 结束后切回 Registry 当前 revision，供下一轮使用。 */
  deactivate(): void {
    if (this.disposed || this.activeSnapshot == null) {
      return;
    }
    this.activeSnapshot = null;
    this.publish(toInspectionSnapshot(
      this.registry.snapshot(),
      'current-registry',
    ));
  }

  /** 幂等释放 Registry 订阅与全部观察者。 */
  dispose(): void {
    if (this.disposed) {
      return;
    }
    this.disposed = true;
    this.unsubscribeRegistry();
    this.listeners.clear();
    this.activeSnapshot = null;
  }

  /** 原子替换当前值后向观察者发布同一个不可变对象。 */
  private publish(snapshot: ToolInspectionSnapshot): void {
    this.currentSnapshot = snapshot;
    for (const listener of [...this.listeners]) {
      listener(snapshot);
    }
  }

  /** 生命周期错误必须显式暴露，不能静默返回过期目录。 */
  private assertActive(): void {
    if (this.disposed) {
      throw new Error('ToolInspectionSource 已释放');
    }
  }
}

/** 从可执行 Registry 快照投影为不含调用器的 Inspector 数据。 */
function toInspectionSnapshot(
  snapshot: ToolRegistrySnapshot,
  scope: ToolInspectionScope,
): ToolInspectionSnapshot {
  return Object.freeze({
    revision: snapshot.revision,
    tools: snapshot.tools,
    scope,
  });
}
