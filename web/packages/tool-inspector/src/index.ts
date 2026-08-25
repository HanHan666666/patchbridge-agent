/**
 * Tools Inspector 浏览器入口：加载 bundle 只注册一个只读自定义元素。
 * Agent Core 与 Widget 均不依赖本包，因此生产环境可以不加载该资源。
 */
import {
  PatchBridgeAgentToolInspectorElement,
  TOOL_INSPECTOR_ELEMENT_NAME,
} from './tool-inspector-element';

export { PatchBridgeAgentToolInspectorElement, TOOL_INSPECTOR_ELEMENT_NAME };
export { toToolInspectorItems, toolSourceLabel } from './viewModel';
export type { ToolInspectorItem } from './viewModel';

if (customElements.get(TOOL_INSPECTOR_ELEMENT_NAME) == null) {
  customElements.define(
    TOOL_INSPECTOR_ELEMENT_NAME,
    PatchBridgeAgentToolInspectorElement,
  );
}
