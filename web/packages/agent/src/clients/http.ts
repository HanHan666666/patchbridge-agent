/**
 * 浏览器侧 HTTP 访问公共层：统一传输契约、JSON 请求与错误标准化。
 *
 * <p>设计原因：三个 Client（Model / Tool / Conversation）共享同一套
 * “传输 + 错误翻译”逻辑，收敛在此避免各自散落实现导致行为不一致。
 * 默认传输使用同源 fetch 并携带 Cookie；宿主可注入自己的传输层，
 * 以复用 Bearer 刷新、CSRF 与现有请求拦截链，框架不感知业务鉴权细节。
 *
 * <p>传输级恢复：GET 请求遇网络级失败自动重试一次（见 requestJson 注释）；
 * 模型流的零事件重试在 modelClient 内独立实现（流式请求的安全性条件不同）。
 */
import {
  invalidStateError,
  isNetworkError,
  toAgentErrorFromNetwork,
  toAgentErrorFromResponse,
} from '../errors';

/**
 * Agent HTTP 传输契约。
 *
 * <p>宿主实现应返回标准 Fetch API Response，以便 JSON 与 SSE
 * 共享同一条传输链。契约只负责执行请求；HTTP 状态、网络错误与
 * 重试语义由框架客户端统一处理，避免宿主适配器重复实现。
 */
export interface HttpTransport {
  /** 执行一次 HTTP 请求；不应在此层自动重试非幂等请求。 */
  request(url: string, init: RequestInit): Promise<Response>;
}

/**
 * 默认浏览器传输：调用标准 fetch，未显式指定时使用 same-origin 凭据策略。
 *
 * <p>保留 RequestInit 中的显式 credentials，便于直接使用 Client 的
 * 高级接入方覆盖单次请求；常规宿主定制应注入 HttpTransport。
 */
export class FetchHttpTransport implements HttpTransport {
  /** 调用浏览器 fetch，并为零配置场景补齐同源 Cookie 策略。 */
  request(url: string, init: RequestInit): Promise<Response> {
    return fetch(url, {
      ...init,
      credentials: init.credentials ?? 'same-origin',
    });
  }
}

/** 内置 Client 共享的无状态默认传输实例。 */
export const defaultHttpTransport: HttpTransport = new FetchHttpTransport();

/** 发起 JSON 请求；非 2xx 响应统一转换为 AgentError 异常抛出。 */
export async function requestJson<T>(
  url: string,
  init: RequestInit,
  transport: HttpTransport = defaultHttpTransport,
): Promise<T> {
  try {
    return await requestJsonOnce<T>(url, init, transport);
  } catch (cause) {
    // 传输级失败单次重试，仅限幂等的 GET：浏览器中止 SSE 流后，Chromium
    // 连接池可能把服务端已关闭的 socket 派给下一次请求，该请求以零字节
    // 连接失败收场且不刷新页面不自愈——实测“停止生成后再次发消息”时
    // 最先撞上死 socket 的就是发送前的 GET /ai/tools。非幂等方法（工具
    // 调用、会话保存等）无法从客户端证明服务端未执行，自动重试可能造成
    // 工具重复执行等副作用，一律不重试。
    const method = (init.method ?? 'GET').toUpperCase();
    if (method !== 'GET' || init.signal?.aborted || !isNetworkError(cause)) {
      throw cause;
    }
  }
  return requestJsonOnce<T>(url, init, transport);
}

/**
 * requestJson 的单次执行体：fetch + 错误标准化，不含重试策略。
 * 204 与空响应体都表示调用成功但没有返回值，不能继续执行 JSON 解析。
 */
async function requestJsonOnce<T>(
  url: string,
  init: RequestInit,
  transport: HttpTransport,
): Promise<T> {
  let response: Response;
  try {
    response = await transport.request(url, init);
  } catch (cause) {
    // 用户主动中止不是传输故障：保留原生 AbortError，让 Runtime 依据已经选定的
    // cancelled / failed 终态统一收敛，避免 HTTP 层擅自改写执行语义。
    if (isAbortError(cause)) {
      throw cause;
    }
    throw toAgentErrorFromNetwork(cause);
  }
  if (!response.ok) {
    throw await toAgentErrorFromResponse(response);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  const responseText = await response.text();
  if (responseText.length === 0) {
    return undefined as T;
  }
  return JSON.parse(responseText) as T;
}

/** 判断异常是否为主动中止（fetch 规范中的 AbortError / DOMException）。 */
export function isAbortError(cause: unknown): boolean {
  return cause instanceof Error && cause.name === 'AbortError';
}

/** 组装 JSON 请求头；保持简洁的薄封装，不引入额外抽象。 */
export function jsonInit(method: string, body: unknown, signal?: AbortSignal): RequestInit {
  return {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body == null ? undefined : JSON.stringify(body),
    signal,
  };
}

/**
 * 统一规范 API 根路径，避免三个 Client 对尾部斜杠产生不同语义。
 * 空字符串代表当前 origin，根路径 "/" 也因此正确拼接为 "/model/stream"。
 */
export function normalizeEndpoint(endpoint: string): string {
  return endpoint.replace(/\/+$/, '');
}

/**
 * 校验服务端列表字段；协议缺失时显式失败，不伪造成“空数据”。
 */
export function requireArrayField<T>(value: unknown, field: string): T[] {
  if (!Array.isArray(value)) {
    throw invalidStateError(`服务端响应缺少数组字段 ${field}`);
  }
  return value as T[];
}
