/**
 * E2E 全链路验证脚本（Node 22，对运行中的 demo 应用执行）。
 *
 * 覆盖链路：表单登录 → 工具发现（RBAC 过滤）→ 真实模型网关 SSE 中继
 * → 本地工具调用 → Global MCP 配置边界 → 越权拒绝 → Conversation CRUD + 乐观锁
 * → Admin 审计查询。Global MCP 不预置测试 Server，真实 endpoint 由用户在管理页配置。
 * 用 Node 而不是 curl：Windows shell 下 curl 发送中文会以本地编码传输导致 400。
 */

const BASE = process.env.E2E_BASE ?? 'http://localhost:8080';

let passed = 0;
let failed = 0;

function ok(condition, label, detail = '') {
  if (condition) {
    passed += 1;
    console.log(`  PASS  ${label}`);
  } else {
    failed += 1;
    console.log(`  FAIL  ${label} ${detail}`);
  }
}

/** 表单登录，返回后续请求要带的 Cookie 头。 */
async function login(username, password) {
  const page = await fetch(`${BASE}/login`);
  const token = (await page.text()).match(/name="_csrf" value="([^"]+)"/)?.[1] ?? '';
  const cookie = page.headers.get('set-cookie')?.split(';')[0] ?? '';
  const response = await fetch(`${BASE}/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', Cookie: cookie },
    body: new URLSearchParams({ username, password, _csrf: token }).toString(),
    redirect: 'manual',
  });
  const finalCookie = response.headers.get('set-cookie')?.split(';')[0] ?? cookie;
  return finalCookie;
}

async function api(cookie, path, init = {}) {
  const response = await fetch(`${BASE}${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', Cookie: cookie, ...(init.headers ?? {}) },
  });
  return { status: response.status, body: await response.json().catch(() => null), response };
}

async function main() {
  console.log('== 登录 ==');
  const admin = await login('admin', 'admin123');
  const user = await login('user', 'user123456');
  ok(admin.length > 0 && user.length > 0, 'admin / user 登录成功');

  console.log('== 未登录访问 /ai 返回 401 + AUTH_REQUIRED ==');
  const anonymous = await api('', '/ai/tools');
  ok(anonymous.status === 401 && anonymous.body?.error?.code === 'AUTH_REQUIRED',
      'AUTH_REQUIRED 错误码', JSON.stringify(anonymous.body));

  console.log('== 工具发现（RBAC 过滤）==');
  const adminTools = await api(admin, '/ai/tools');
  const adminNames = adminTools.body.tools.map(t => t.name).sort();
  ok(adminNames.join() ===
      'local.device_get,local.device_list,local.device_restart',
  'admin 可见 Demo 的 3 个本地工具', adminNames.join(','));
  const userTools = await api(user, '/ai/tools');
  ok(userTools.body.tools.map(t => t.name).sort().join() ===
      'local.device_get,local.device_list', 'user 只见 2 个本地只读工具');

  console.log('== Global MCP 配置边界 ==');
  const mcpConfiguration = await api(admin, '/ai/admin/mcp/servers');
  ok(mcpConfiguration.status === 200
      && mcpConfiguration.body?.source === 'jdbc'
      && mcpConfiguration.body?.mutable === true
      && Array.isArray(mcpConfiguration.body?.servers),
  'Global MCP 使用可管理的 JDBC 配置源且不依赖预置 Server',
  JSON.stringify(mcpConfiguration.body).slice(0, 160));

  console.log('== 模型窗口与上下文压缩配置 ==');
  const modelConfiguration = await api(admin, '/ai/model/config');
  const contextWindowTokens = modelConfiguration.body?.contextWindowTokens;
  ok(modelConfiguration.status === 200
      && Number.isSafeInteger(contextWindowTokens)
      && modelConfiguration.body?.automaticThresholdTokens
        === Math.floor(contextWindowTokens * 0.8)
      && Number.isSafeInteger(modelConfiguration.body?.keepRecentTokens)
      && modelConfiguration.body.keepRecentTokens > 0
      && modelConfiguration.body.keepRecentTokens
        < modelConfiguration.body.automaticThresholdTokens,
  '服务端公开唯一模型窗口、80% 自动阈值与合法近期预算',
  JSON.stringify(modelConfiguration.body));

  console.log('== 真实模型网关结构化 SSE ==');
  const streamResponse = await fetch(`${BASE}/ai/model/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Cookie: admin },
    body: JSON.stringify({
      traceId: 'e2e-trace-1',
      conversationId: null,
      request: {
        responseMessageId: 'e2e-model-assistant-1',
        messages: [{
          id: 'e2e-model-user-1',
          role: 'user',
          blocks: [{ type: 'text', text: '查询设备 DEV-1001' }],
        }],
        modelState: null,
        tools: [{
          name: 'local.device_get',
          description: '查询设备',
          inputSchema: { type: 'object' },
        }],
      },
    }),
  });
  const sseText = await streamResponse.text();
  ok(streamResponse.status === 200
      && sseText.includes('"type":"block-start"')
      && sseText.includes('"type":"message-stop"')
      && !sseText.includes('choices')
      && !sseText.includes('[DONE]'),
      'SSE 只返回框架结构化事件', sseText.slice(0, 240));

  console.log('== 工具调用 ==');
  const deviceList = await api(admin, '/ai/tools/call', {
    method: 'POST',
    body: JSON.stringify({ name: 'local.device_list', arguments: {}, traceId: 'e2e-trace-1', toolCallId: 'tc-0' }),
  });
  ok(deviceList.status === 200 && typeof deviceList.body.content === 'string',
      'device_list 返回文本 content（网关已收敛 content 块）', JSON.stringify(deviceList.body).slice(0, 120));

  const denied = await api(user, '/ai/tools/call', {
    method: 'POST',
    body: JSON.stringify({ name: 'local.device_restart', arguments: { sn: 'DEV-1001' } }),
  });
  ok(denied.status === 403 && denied.body?.error?.code === 'TOOL_FORBIDDEN',
      '越权调用被拒（403 TOOL_FORBIDDEN）');

  console.log('== Conversation CRUD + 乐观锁 ==');
  const created = await api(admin, '/ai/conversations', { method: 'POST', body: '{}' });
  const conversationId = created.body.conversation.conversationId;
  ok(created.status === 200 && !!conversationId, '创建会话');

  const saved = await api(admin, `/ai/conversations/${conversationId}`, {
    method: 'PUT',
    body: JSON.stringify({
      revision: 0,
      title: 'E2E 测试会话',
      context: {
        messages: [
          { id: 'e2e-user-1', role: 'user', blocks: [{ type: 'text', text: '查询设备 DEV-1001' }] },
          { id: 'e2e-assistant-1', role: 'assistant', blocks: [{ type: 'text', text: '设备运行正常' }] },
        ],
        modelContext: {
          checkpoint: null,
          firstRetainedMessageId: null,
          modelState: {
            format: 'e2e-provider/v1',
            data: { opaque: 'e2e-state-1' },
          },
          usage: null,
        },
      },
    }),
  });
  ok(saved.status === 200 && saved.body.conversation.revision === 1, '整回合保存后 revision=1');

  const stale = await api(admin, `/ai/conversations/${conversationId}`, {
    method: 'PUT',
    body: JSON.stringify({
      revision: 0,
      title: '旧版本写入',
      context: {
        messages: [
          { id: 'e2e-stale-1', role: 'user', blocks: [{ type: 'text', text: 'x' }] },
        ],
        modelContext: {
          checkpoint: null,
          firstRetainedMessageId: null,
          modelState: null,
          usage: null,
        },
      },
    }),
  });
  ok(stale.status === 409 && stale.body?.error?.code === 'CONVERSATION_CONFLICT',
      '旧 revision 写入返回 409 冲突', JSON.stringify(stale.body));

  const loaded = await api(admin, `/ai/conversations/${conversationId}`);
  ok(loaded.status === 200 && loaded.body.context.messages.length === 2
      && loaded.body.context.messages[1].blocks[0].text === '设备运行正常'
      && loaded.body.context.modelContext.modelState.data.opaque === 'e2e-state-1',
  '读回同一 revision 的完整消息与 ModelContext');

  const listed = await api(admin, '/ai/conversations');
  ok(listed.body.conversations.some(c => c.conversationId === conversationId), '会话列表包含新会话');

  console.log('== Admin 审计 ==');
  const stats = await api(admin, '/ai/admin/stats');
  ok(stats.status === 200 && (stats.body?.totalInvocations ?? 0) > 0,
      `审计统计有记录（${stats.body?.totalInvocations ?? stats.status} 次调用）`,
      JSON.stringify(stats.body).slice(0, 120));
  const traces = await api(admin, '/ai/admin/traces?traceId=e2e-trace-1');
  ok((traces.body?.items ?? []).length >= 2,
      `traceId 串联查询（${traces.body?.items?.length ?? traces.status} 条）`,
      JSON.stringify(traces.body).slice(0, 120));
  const userAdmin = await api(user, '/ai/admin/stats');
  ok(userAdmin.status === 403, '普通用户访问 Admin API 被拒（403）');

  const deleted = await api(admin, `/ai/conversations/${conversationId}`, { method: 'DELETE' });
  ok(deleted.status === 204, '删除会话');

  console.log(`\n结果：${passed} 通过，${failed} 失败`);
  process.exit(failed > 0 ? 1 : 0);
}

main().catch(error => {
  console.error('E2E 脚本异常:', error);
  process.exit(1);
});
