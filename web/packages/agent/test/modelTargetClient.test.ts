/**
 * 目标目录与切换响应属于不可信 HTTP 数据：拒绝重复 ID、泄漏字段和不完整会话。
 */
import { describe, expect, it } from 'vitest';
import { HttpModelTargetClient } from '../src/clients/modelTargetClient';
import type { HttpTransport } from '../src/clients/http';
import { TEST_TARGET, testModelContext } from './testContext';

/** 为单次请求返回指定 JSON；测试只验证边界，不模拟另一个 Controller。 */
function transportWith(body: unknown): HttpTransport {
  return { async request() {
    return new Response(JSON.stringify(body), { status: 200,
      headers: { 'Content-Type': 'application/json' } });
  } };
}

/** 服务端公开目标的完整最小值。 */
function target(): Record<string, unknown> {
  return { ref: TEST_TARGET, displayName: '测试模型', protocol: 'anthropic-messages',
    imageInput: true, toolCalling: true,
    configuration: { contextWindowTokens: 128000, automaticThresholdTokens: 102400,
      keepRecentTokens: 20000, reservedOutputTokens: 12800 } };
}

describe('HttpModelTargetClient', () => {
  it('只接受脱敏且唯一的目标目录', async () => {
    await expect(new HttpModelTargetClient('/ai', transportWith({ targets: [target()],
      defaultTarget: TEST_TARGET })).catalog()).resolves.toMatchObject({ defaultTarget: TEST_TARGET });
    await expect(new HttpModelTargetClient('/ai', transportWith({ targets: [target(), target()],
      defaultTarget: TEST_TARGET })).catalog()).rejects.toThrow('重复 targetId');
    await expect(new HttpModelTargetClient('/ai', transportWith({ targets: [{ ...target(), apiKey: 'must-not-leak' }],
      defaultTarget: TEST_TARGET })).catalog()).rejects.toThrow('字段不符合协议');
  });

  it('已保存会话切换响应复用会话元数据和上下文严格校验', async () => {
    const context = { messages: [], modelTarget: TEST_TARGET, modelContext: testModelContext() };
    const incomplete = new HttpModelTargetClient('/ai', transportWith({
      conversation: { conversationId: 'c1', revision: 2 }, context,
    }));
    await expect(incomplete.switchConversation('c1', 1, TEST_TARGET, []))
      .rejects.toThrow('title');
  });
});
