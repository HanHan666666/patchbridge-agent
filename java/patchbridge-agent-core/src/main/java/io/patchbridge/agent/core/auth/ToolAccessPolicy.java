package io.patchbridge.agent.core.auth;

import io.patchbridge.agent.core.tool.ToolDefinition;
import io.patchbridge.agent.core.user.UserContext;

/**
 * Tool 访问策略 SPI：AI Tool Call 与普通 API Call 共用同一套企业权限体系的接入点。
 *
 * <p>两道检查都必须实现：
 * <ul>
 *   <li>canDiscover —— 决定 /ai/tools 是否把该 Tool 暴露给当前用户（减少无权限
 *       Tool 进入模型上下文）；</li>
 *   <li>canInvoke —— 每次 /ai/tools/call 都重新执行。浏览器不是安全边界，
 *       列表过滤不能替代调用校验。</li>
 * </ul>
 *
 * <p>user 为 null 表示未识别出登录用户。
 */
public interface ToolAccessPolicy {

    boolean canDiscover(UserContext user, ToolDefinition tool);

    boolean canInvoke(UserContext user, ToolDefinition tool);
}
