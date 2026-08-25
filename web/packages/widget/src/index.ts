/**
 * widget 包入口：注册 <patchbridge-agent> 自定义元素。
 *
 * <p>构建为单文件 IIFE（dist/patchbridge-agent.js），宿主页面引入即完成注册；
 * 重复引入（多 bundle 场景）时跳过注册，避免 DuplicateDefinition 异常。
 */
import {
  ELEMENT_NAME,
  PatchBridgeAgentElement,
} from './patchbridge-agent-element';

if (customElements.get(ELEMENT_NAME) == null) {
  customElements.define(ELEMENT_NAME, PatchBridgeAgentElement);
}

export {
  ELEMENT_NAME,
  PatchBridgeAgentElement,
  READY_EVENT_NAME,
} from './patchbridge-agent-element';
export type {
  PatchBridgeAgentReadyEventDetail,
  WidgetRuntimeOptions,
} from './patchbridge-agent-element';
