package io.patchbridge.agent.core.error;

/**
 * Tool 定义/路由版本引用与当前服务端状态不一致。
 *
 * <p>浏览器持有的 Tool 快照已经过期：同名 Tool 的定义内容或路由目标发生了语义变化。
 * 此时继续执行会让模型基于旧描述把调用落到新目标上，必须明确失败，
 * 由调用方重新发现工具后以新版本重试；该异常以 409 TOOL_VERSION_MISMATCH 返回浏览器，
 * 与权限拒绝（403）和执行失败（500）保持可区分的语义。
 */
public class ToolVersionMismatchException extends Exception {

    public ToolVersionMismatchException(String message) {
        super(message);
    }
}
