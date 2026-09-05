/**
 * 组装工厂的调用轨迹采集开关测试。
 *
 * <p>二次审计 Q-02：采集与持久化必须显式 opt-in。本文件从工厂入口驱动完整
 * 发送链路（真实 Runtime + 脚本化 Model + 假会话 Client），验证三种装配的
 * 端到端事实：默认零采集零存储访问、memory 仅内存、persistent 写 localStorage。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createAgentController } from '../src/factory';
import type { Model, ModelStreamEvent } from '../src/clients/modelClient';
import type {
  Conversation,
  ConversationClient,
  ConversationDetail,
  ConversationSaveBody,
} from '../src/clients/conversationClient';
import type { ToolClient } from '../src/clients/toolClient';
import type { AgentHookFailure } from '../src/extensions';
import type { AgentError } from '../src/types';
import type { ContextCompactionGateway } from '../src/clients/contextCompactionClient';
import { TEST_MODEL_USAGE, testModelContext } from './testContext';

/** 工厂测试使用的固定模型窗口；这些用例不触发压缩模型调用。 */
const CONTEXT_GATEWAY: ContextCompactionGateway = {
  configuration: async () => ({
    contextWindowTokens: 128_000,
    automaticThresholdTokens: 102_400,
    keepRecentTokens: 20_000,
    reservedOutputTokens: 12_800,
  }),
  compact: async () => {
    throw new Error('该工厂测试不触发上下文压缩');
  },
};

/** 记录访问的 localStorage 替身；Controller 的 UX 缓存键与轨迹键都会经过它。 */
class RecordingStorage {
  /** 全部访问日志，格式为 "动词:键"。 */
  readonly accesses: string[] = [];
  private readonly data = new Map<string, string>();

  getItem(key: string): string | null {
    this.accesses.push(`get:${key}`);
    return this.data.get(key) ?? null;
  }

  setItem(key: string, value: string): void {
    this.accesses.push(`set:${key}`);
    this.data.set(key, value);
  }

  removeItem(key: string): void {
    this.accesses.push(`remove:${key}`);
    this.data.delete(key);
  }

  /** 轨迹相关键的访问日志。 */
  traceAccesses(): string[] {
    return this.accesses.filter(access => access.includes('call-trace'));
  }
}

/** 每次调用返回一段固定文本响应的脚本化 Model。 */
class SingleTurnModel implements Model {
  async *stream(): AsyncIterable<ModelStreamEvent> {
    yield { type: 'block-start', index: 0, block: { type: 'text' } };
    yield { type: 'block-delta', index: 0, delta: { type: 'text', text: '收到，已完成。' } };
    yield { type: 'block-stop', index: 0 };
    yield {
      type: 'message-stop',
      stopReason: 'end-turn',
      usage: TEST_MODEL_USAGE,
      modelState: null,
    };
  }
}

/** 首轮保存返回固定新会话；其余方法返回最小空结果。 */
class SingleConversationClient implements ConversationClient {
  /** 保存过的会话 ID，便于断言轨迹归属。 */
  savedId: string | null = null;

  async list(): Promise<Conversation[]> {
    return [];
  }

  async create(title: string | null): Promise<Conversation> {
    return conversation('conversation-factory', 0, title);
  }

  async get(id: string): Promise<ConversationDetail> {
    return {
      conversation: conversation(id, 0),
      context: { messages: [], modelContext: testModelContext() },
    };
  }

  async save(id: string, _body: ConversationSaveBody): Promise<Conversation> {
    this.savedId = id;
    return conversation(id, 1);
  }

  async delete(): Promise<void> {
    return undefined;
  }
}

/** 空目录 Tool Client：链路里没有 Tool，也不发起任何网络请求。 */
class EmptyToolClient implements ToolClient {
  async list(): Promise<never[]> {
    return [];
  }

  async call(): Promise<never> {
    throw new Error('本测试没有 Tool 调用');
  }
}

/** 构造最小会话元数据。 */
function conversation(
  id: string,
  revision: number,
  title: string | null = null,
): Conversation {
  return {
    conversationId: id,
    title,
    revision,
    status: 'ACTIVE',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  };
}

/** 安装全局 localStorage 记录替身；测试结束后恢复。 */
function stubStorage(): RecordingStorage {
  const storage = new RecordingStorage();
  vi.stubGlobal('localStorage', storage);
  return storage;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('createAgentController 调用轨迹采集开关', () => {
  it('缺省不采集：完整发送后数据源恒空且不触碰轨迹存储键', async () => {
    const storage = stubStorage();
    const conversations = new SingleConversationClient();
    const controller = createAgentController({
      endpoint: '/ai',
      model: new SingleTurnModel(),
      conversationClient: conversations,
      toolClient: new EmptyToolClient(),
      storageKey: 'ux:last-conversation',
      contextCompactionGateway: CONTEXT_GATEWAY,
    });

    await controller.initialize();
    await controller.sendMessage('列出车间设备');
    const source = controller.getCallTraceSource();

    expect(source.snapshot().traces).toEqual([]);
    expect(source.snapshot().persistenceError).toBeNull();
    expect(storage.traceAccesses()).toEqual([]);
    const unsubscribe = source.subscribe(() => undefined);
    expect(() => source.clear()).not.toThrow();
    unsubscribe();
    controller.dispose();
  });

  it('memory 模式采集完整轨迹且不访问轨迹存储键', async () => {
    const storage = stubStorage();
    const conversations = new SingleConversationClient();
    const controller = createAgentController({
      endpoint: '/ai',
      model: new SingleTurnModel(),
      conversationClient: conversations,
      toolClient: new EmptyToolClient(),
      storageKey: 'ux:last-conversation',
      callTrace: { mode: 'memory' },
      contextCompactionGateway: CONTEXT_GATEWAY,
    });

    await controller.initialize();
    await controller.sendMessage('列出车间设备');
    const source = controller.getCallTraceSource();

    expect(source.snapshot().traces).toHaveLength(1);
    expect(source.snapshot().traces[0]).toMatchObject({
      conversationId: 'conversation-factory',
      outcome: { type: 'completed', stopReason: 'end-turn' },
      failed: null,
    });
    expect(source.snapshot().traces[0]?.records.map(record => record.type))
      .toEqual(['user-input', 'model-call']);
    expect(source.snapshot().persistenceError).toBeNull();
    // 关键断言：仅内存模式全链路（含会话保存迁移）不出现任何轨迹键访问。
    expect(storage.traceAccesses()).toEqual([]);
    controller.dispose();
  });

  it('persistent 模式在会话保存后写入指定前缀的轨迹键', async () => {
    const storage = stubStorage();
    const conversations = new SingleConversationClient();
    const controller = createAgentController({
      endpoint: '/ai',
      model: new SingleTurnModel(),
      conversationClient: conversations,
      toolClient: new EmptyToolClient(),
      storageKey: 'ux:last-conversation',
      callTrace: { mode: 'persistent', storageKey: 'ct-factory-test' },
      contextCompactionGateway: CONTEXT_GATEWAY,
    });

    await controller.initialize();
    await controller.sendMessage('列出车间设备');
    const source = controller.getCallTraceSource();

    expect(source.snapshot().traces).toHaveLength(1);
    // 自定义前缀不含 "call-trace" 字样，直接断言原始访问日志。
    const writes = storage.accesses.filter(access => access.startsWith('set:'));
    expect(writes).toContain('set:ct-factory-test:conversation-factory');
    controller.dispose();
  });

  it('内部轨迹先于宿主 Hook 建立，started 失败仍保留原错误并封闭完整轨迹', async () => {
    stubStorage();
    const originalError = Object.freeze({
      code: 'HOST_HOOK_FAILED',
      message: '宿主 execution-started Hook 失败',
      retryable: false,
    }) satisfies AgentError;
    const diagnostics: AgentHookFailure[] = [];
    const controller = createAgentController({
      endpoint: '/ai',
      model: new SingleTurnModel(),
      conversationClient: new SingleConversationClient(),
      toolClient: new EmptyToolClient(),
      callTrace: { mode: 'memory' },
      runtime: {
        hooks: [{
          onEvent: event => {
            if (event.type === 'execution-started') {
              throw originalError;
            }
          },
        }],
        onHookError: failure => diagnostics.push(failure),
      },
      contextCompactionGateway: CONTEXT_GATEWAY,
    });

    await controller.initialize();
    await controller.sendMessage('触发宿主 Hook 失败');

    expect(controller.getState()).toMatchObject({
      status: 'error',
      error: originalError,
    });
    expect(controller.getState().error).toBe(originalError);
    expect(controller.getCallTraceSource().snapshot().traces).toEqual([
      expect.objectContaining({
        endedAt: expect.any(Number),
        outcome: null,
        failed: {
          code: originalError.code,
          message: originalError.message,
        },
        records: [
          expect.objectContaining({
            type: 'user-input',
            text: '触发宿主 Hook 失败',
          }),
        ],
      }),
    ]);
    expect(diagnostics).toEqual([]);
    controller.dispose();
  });
});
