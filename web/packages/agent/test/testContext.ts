/**
 * Browser 测试共享的显式上下文管理器。
 *
 * <p>既有 Runtime/Controller 单元测试不覆盖压缩边界，因此使用固定窗口且永不自动
 * 压缩的实现；正常模型响应仍强制要求 usage，保持生产契约而不引入测试专用降级。
 */
import type { ContextManager, PreparedModelContext } from '../src/contextManager';
import type { ModelUsage } from '../src/clients/modelClient';
import type {
  ContextCompactionConfiguration,
  ContextCompactionTrigger,
  ConversationContext,
  ModelContext,
  ModelState,
} from '../src/types';

/** 普通测试模型每次返回的明确 Provider 用量。 */
export const TEST_MODEL_USAGE: ModelUsage = Object.freeze({
  inputTokens: 100,
  outputTokens: 20,
  totalTokens: 120,
});

/** 未压缩测试会话的明确空模型工作上下文。 */
export function testModelContext(modelState: ModelState | null = null): ModelContext {
  return Object.freeze({
    checkpoint: null,
    firstRetainedMessageId: null,
    modelState,
    usage: null,
  });
}

/** 创建不会触发压缩、但会严格推进 Provider usage 的测试 ContextManager。 */
export function testContextManager(): ContextManager {
  const configuration: ContextCompactionConfiguration = Object.freeze({
    contextWindowTokens: 128_000,
    automaticThresholdTokens: 102_400,
    keepRecentTokens: 20_000,
    reservedOutputTokens: 12_800,
  });
  return {
    loadConfiguration: async () => configuration,
    getConfiguration: () => configuration,
    requiresAutomaticCompaction: () => false,
    prepareForModelCall: async input => Object.freeze({
      conversation: input.conversation,
      modelMessages: input.conversation.messages,
    }) satisfies PreparedModelContext,
    compact: async (
      _conversation: ConversationContext,
      _trigger: ContextCompactionTrigger,
    ) => {
      throw new Error('该测试未装配上下文压缩调用');
    },
    buildModelMessages: conversation => conversation.messages,
    recordModelResponse: (
      conversation,
      responseMessage,
      usage,
      modelState,
    ) => {
      if (usage == null) {
        throw new Error('模型 Provider 未返回必需的 token usage');
      }
      return Object.freeze({
        messages: conversation.messages,
        modelContext: Object.freeze({
          ...conversation.modelContext,
          modelState,
          usage: Object.freeze({
            totalTokens: usage.totalTokens,
            source: 'provider' as const,
            toolDefinitionTokens: 0,
            measuredThroughMessageId: responseMessage.id,
          }),
        }),
      });
    },
  };
}
