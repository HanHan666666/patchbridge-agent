/**
 * Tools Inspector 展示模型：把 Agent 公共 ToolDefinition 转成稳定、只读的 UI 数据。
 * 该层不依赖 DOM，来源文案与排序规则可独立测试，避免 Custom Element 内散落分支。
 */
import type { ToolDefinition, ToolInspectionSnapshot, ToolSource } from '@patchbridge-agent/agent';

/** Inspector 单条展示数据。 */
export interface ToolInspectorItem {
  /** Registry 中的全局唯一名称。 */
  readonly name: string;
  /** 面向人的标题。 */
  readonly title: string;
  /** 模型使用的能力说明。 */
  readonly description: string;
  /** 来源稳定码，便于宿主自定义筛选或样式。 */
  readonly source: ToolSource;
  /** 来源的中文展示标签。 */
  readonly sourceLabel: string;
  /** 格式化后的 JSON Schema。 */
  readonly schema: string;
  /** 是否需要 Agent Runtime 发起用户确认。 */
  readonly requiresConfirmation: boolean;
  /** 当前 Tool 所属 Registry revision。 */
  readonly revision: number;
}

/** 将完整快照映射为同 revision 的展示列表，不做来源过滤。 */
export function toToolInspectorItems(
  snapshot: ToolInspectionSnapshot,
): readonly ToolInspectorItem[] {
  return snapshot.tools.map(tool => toItem(tool, snapshot.revision));
}

/** 来源标签只在此处维护，Inspector DOM 不按协议来源分支。 */
export function toolSourceLabel(source: ToolSource): string {
  switch (source) {
    case 'LOCAL':
      return '后端原生';
    case 'OPENAPI':
      return '后端 OpenAPI';
    case 'MCP':
      return '远程 MCP';
    case 'FRONTEND_LOCAL':
      return '纯前端';
    case 'WEBMCP':
      return 'WebMCP';
  }
}

/** 单条转换集中处理空标题与注记，保证渲染层只负责布局。 */
function toItem(tool: ToolDefinition, revision: number): ToolInspectorItem {
  return Object.freeze({
    name: tool.name,
    title: tool.title?.trim() || tool.name,
    description: tool.description,
    source: tool.source,
    sourceLabel: toolSourceLabel(tool.source),
    schema: JSON.stringify(tool.inputSchema, null, 2),
    requiresConfirmation: tool.annotations?.requireConfirmation === true,
    revision,
  });
}
