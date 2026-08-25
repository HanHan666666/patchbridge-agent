/**
 * 错误标准化：把 HTTP 状态码、后端错误体、网络异常收敛为统一 AgentError。
 *
 * <p>设计原因：View 不解析网络细节（设计文档第 18 节），因此所有异常必须在
 * Client 边界完成翻译；AgentErrorCode 与后端 core 模块的枚举名保持一致，
 * 保证一条链路（浏览器 → 网关 → 审计）上的错误码可直接对账。
 */
import type { AgentError } from './types';

/** 后端统一错误体 {error:{code,message}} 的结构。 */
interface BackendErrorBody {
  error?: { code?: unknown; message?: unknown };
}

/** 从后端响应体中提取 code / message；结构不符时回退到给定默认值。 */
function readBackendError(raw: unknown): { code: string | null; message: string | null } {
  const body = raw as BackendErrorBody | null;
  if (body == null || typeof body !== 'object' || body.error == null) {
    return { code: null, message: null };
  }
  const code = typeof body.error.code === 'string' ? body.error.code : null;
  const message = typeof body.error.message === 'string' ? body.error.message : null;
  return { code, message };
}

/** 可重试的错误码集合：网络与上游模型故障重试有意义，权限与状态类错误重试无意义。 */
const RETRYABLE_CODES = new Set(['NETWORK_ERROR', 'MODEL_FAILED']);

/**
 * 把非 2xx 的 fetch 响应转换为 AgentError。
 * 响应体可能是后端标准错误体，也可能是网关 / 容器产生的任意 HTML——都收敛为中文提示。
 */
export async function toAgentErrorFromResponse(response: Response): Promise<AgentError> {
  let code: string | null = null;
  let message: string | null = null;
  try {
    const parsed = readBackendError(await response.json());
    code = parsed.code;
    message = parsed.message;
  } catch {
    // 响应体不是 JSON（如网关 502 HTML 页）时保留空值，走状态码映射
  }
  if (code == null) {
    code = statusToCode(response.status);
  }
  return {
    code,
    message: message ?? `请求失败（HTTP ${response.status}）`,
    retryable: RETRYABLE_CODES.has(code),
  };
}

/** 网络层异常（连接拒绝 / DNS / 中断）的标准化。 */
export function toAgentErrorFromNetwork(cause: unknown): AgentError {
  return {
    code: 'NETWORK_ERROR',
    message: '网络异常，请检查连接后重试',
    retryable: true,
  };
}

/**
 * 传输级失败判定：网络异常标准化后的 AgentError（AbortError / HTTP 状态错误均不算）。
 * 共享 HTTP 层与模型流客户端据此决定是否重试。
 */
export function isNetworkError(cause: unknown): cause is AgentError {
  return (
    cause != null &&
    typeof cause === 'object' &&
    (cause as AgentError).code === 'NETWORK_ERROR'
  );
}

/** 用户主动中止不是错误流程的一部分，由调用方决定呈现方式。 */
export function abortedError(): AgentError {
  return { code: 'ABORTED', message: '已停止生成', retryable: false };
}

/** 业务规则冲突（多 Tab 并发写会话）：提示刷新而不是静默覆盖。 */
export function conversationConflictError(): AgentError {
  return {
    code: 'CONVERSATION_CONFLICT',
    message: '会话已在其他窗口被修改，请重新加载该会话',
    retryable: false,
  };
}

/** 在不允许的时机发起操作（如生成中再次发送）。 */
export function invalidStateError(message: string): AgentError {
  return { code: 'INVALID_STATE', message, retryable: false };
}

/** HTTP 状态码到框架错误码的映射；未覆盖的状态码归为通用 MODEL/服务错误。 */
function statusToCode(status: number): string {
  if (status === 401 || status === 403) {
    // 后端对未登录返回 AUTH_REQUIRED；403 视为登录态失效（会话过期后端点被拦截）
    return 'AUTH_REQUIRED';
  }
  if (status === 409) {
    return 'CONVERSATION_CONFLICT';
  }
  if (status === 404) {
    return 'TOOL_FAILED';
  }
  return 'MODEL_FAILED';
}
