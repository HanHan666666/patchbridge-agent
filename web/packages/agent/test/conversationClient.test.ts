/**
 * ConversationClient HTTP 响应边界校验测试（二次审计 Q-07）。
 *
 * <p>HTTP 响应属于不可信输入：字段缺失或类型不符必须显式失败，
 * 不允许畸形数据流入 Controller 状态与 View 渲染。
 */
import { describe, expect, it } from 'vitest';
import { HttpConversationClient } from '../src/clients/conversationClient';
import type { HttpTransport } from '../src/clients/http';
import { testModelContext } from './testContext';

/** 返回固定 JSON 文本的桩传输层。 */
function transportWith(body: unknown): HttpTransport {
  return {
    async request() {
      return new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    },
  };
}

function validConversation(): Record<string, unknown> {
  return {
    conversationId: 'conv-1',
    title: '设备巡检',
    revision: 3,
    status: 'ACTIVE',
    createdAt: '2026-08-22T10:00:00Z',
    updatedAt: '2026-08-22T10:05:00Z',
  };
}

describe('HttpConversationClient 响应边界校验', () => {
  it('合法会话响应原样通过，包括 null 标题', async () => {
    const conversation = { ...validConversation(), title: null };
    const client = new HttpConversationClient('/ai', transportWith({ conversations: [conversation] }));
    await expect(client.list()).resolves.toHaveLength(1);
  });

  it('list 中任意条目形状非法都整体失败', async () => {
    const cases: readonly unknown[] = [
      { conversations: [null] },
      { conversations: [{ ...validConversation(), conversationId: '' }] },
      { conversations: [{ ...validConversation(), conversationId: 108 }] },
      { conversations: [{ ...validConversation(), title: 7 }] },
      { conversations: [{ ...validConversation(), revision: '3' }] },
      { conversations: [{ ...validConversation(), status: null }] },
      { conversations: [{ ...validConversation(), updatedAt: 123 }] },
      { conversations: 'not-array' },
    ];
    for (const body of cases) {
      const client = new HttpConversationClient('/ai', transportWith(body));
      await expect(client.list(), JSON.stringify(body)).rejects.toThrow();
    }
  });

  it('create/save 返回的会话对象同样强制校验', async () => {
    const bad = new HttpConversationClient('/ai', transportWith({ conversation: { title: 'x' } }));
    await expect(bad.create('x')).rejects.toThrow('conversationId');
    await expect(bad.save('conv-1', {
      title: 'x',
      revision: 0,
      context: { messages: [], modelContext: testModelContext() },
    })).rejects.toThrow('conversationId');
  });

  it('get 的 context.messages 与 modelContext 形状强制校验', async () => {
    const badMessages = new HttpConversationClient('/ai', transportWith({
      conversation: validConversation(),
      context: { messages: 'oops', modelContext: testModelContext() },
    }));
    await expect(badMessages.get('conv-1')).rejects.toThrow('context.messages');

    const badState = new HttpConversationClient('/ai', transportWith({
      conversation: validConversation(),
      context: { messages: [], modelContext: 'opaque' },
    }));
    await expect(badState.get('conv-1')).rejects.toThrow('context.modelContext');
  });
});
