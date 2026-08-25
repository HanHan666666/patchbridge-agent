/**
 * 无构建工具宿主的浏览器入口：只暴露 WebMCP 显式连接函数。
 * 常规工程应优先从 @patchbridge-agent/webmcp-adapter 导入；Demo 的静态 HTML
 * 使用本 IIFE 全局对象，避免为了演示引入前端打包链。
 */
import { connectDocumentWebMcp } from './index';

/** 浏览器全局最小 API。 */
export interface PatchBridgeAgentWebMcpBrowserApi {
  /** 把当前 Document 的 WebMCP Tool 挂载到指定 Registry。 */
  readonly connectDocumentWebMcp: typeof connectDocumentWebMcp;
}

declare global {
  /** 静态 HTML 可直接使用的 WebMCP Adapter 入口。 */
  interface Window {
    PatchBridgeAgentWebMcp?: PatchBridgeAgentWebMcpBrowserApi;
  }
}

if (window.PatchBridgeAgentWebMcp == null) {
  window.PatchBridgeAgentWebMcp = Object.freeze({ connectDocumentWebMcp });
}
