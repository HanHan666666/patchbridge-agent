/** Tools Inspector 展示模型测试：确保所有来源不丢失且列表与执行感知快照一致。 */
import { describe, expect, it } from 'vitest';
import { toToolInspectorItems, toolSourceLabel } from '../src/viewModel';
import type { ToolInspectionSnapshot, ToolSource } from '@patchbridge-agent/agent';

describe('Tools Inspector view model', () => {
  it('完整保留快照顺序、revision、来源与确认提示', () => {
    const snapshot: ToolInspectionSnapshot = {
      revision: 7,
      scope: 'current-execution',
      tools: [{
        name: 'frontend.selected_device',
        title: null,
        description: '读取页面选中的设备',
        inputSchema: { type: 'object' },
        annotations: {
          readOnlyHint: false,
          destructiveHint: false,
          idempotentHint: false,
          requireConfirmation: true,
        },
        source: 'FRONTEND_LOCAL',
        permissions: [],
      }],
    };

    expect(toToolInspectorItems(snapshot)).toEqual([{
      name: 'frontend.selected_device',
      title: 'frontend.selected_device',
      description: '读取页面选中的设备',
      source: 'FRONTEND_LOCAL',
      sourceLabel: '纯前端',
      schema: JSON.stringify({ type: 'object' }, null, 2),
      requiresConfirmation: true,
      revision: 7,
    }]);
  });

  it('五类 Registry 来源均有稳定标签', () => {
    const sources: ToolSource[] = ['LOCAL', 'OPENAPI', 'MCP', 'FRONTEND_LOCAL', 'WEBMCP'];
    expect(sources.map(toolSourceLabel)).toEqual([
      '后端原生',
      '后端 OpenAPI',
      '远程 MCP',
      '纯前端',
      'WebMCP',
    ]);
  });
});
