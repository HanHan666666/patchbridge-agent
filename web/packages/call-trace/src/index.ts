/**
 * 调用轨迹浏览器入口：加载 bundle 只注册一个只读自定义元素。
 * Agent Core 与 Widget 均不依赖本包，宿主不加载该资源就不存在轨迹视图。
 */
import {
  PatchBridgeAgentCallTraceElement,
  CALL_TRACE_ELEMENT_NAME,
} from './call-trace-element';

export { PatchBridgeAgentCallTraceElement, CALL_TRACE_ELEMENT_NAME };
export {
  formatClock,
  formatDuration,
  formatUsage,
  toDetailSections,
  toRecordRow,
  toTraceGroups,
  traceStatusLabel,
  traceSummaryLabel,
  usageDetail,
} from './viewModel';
export type {
  TraceDetailSection,
  TraceGroup,
  TraceRecordRow,
} from './viewModel';

if (customElements.get(CALL_TRACE_ELEMENT_NAME) == null) {
  customElements.define(
    CALL_TRACE_ELEMENT_NAME,
    PatchBridgeAgentCallTraceElement,
  );
}
