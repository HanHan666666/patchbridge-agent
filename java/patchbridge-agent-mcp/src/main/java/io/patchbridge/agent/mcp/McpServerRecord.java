package io.patchbridge.agent.mcp;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 配置源中的 Global MCP Server 记录。
 *
 * <p>它是存储 Adapter 与应用服务的内部交换对象，包含已解密的运行配置，
 * 禁止直接作为 Web 响应序列化。Admin Controller 必须转换为脱敏视图。
 */
public final class McpServerRecord {

    /** Server 在 MCP 命名空间中的唯一段名称。 */
    private final String name;

    /** 仅供运行时使用的完整配置，可能包含凭据。 */
    private final McpServerConfig config;

    /** 乐观锁版本，创建时为 0。 */
    private final long revision;

    /** 创建时间，Unix 毫秒。 */
    private final long createdAt;

    /** 最后更新时间，Unix 毫秒。 */
    private final long updatedAt;

    /** 是否存在可供当前认证类型使用的密文凭据。 */
    private final boolean credentialConfigured;

    /** 创建一条不对外序列化的存储记录。 */
    public McpServerRecord(String name, McpServerConfig config, long revision,
                           long createdAt, long updatedAt, boolean credentialConfigured) {
        this.name = name;
        this.config = config.copy();
        this.revision = revision;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.credentialConfigured = credentialConfigured;
    }

    /** 返回 Server 名称。 */
    public String getName() { return name; }

    /** 返回完整运行配置的深拷贝，调用方不能修改记录本身。 */
    @JsonIgnore
    public McpServerConfig getConfig() { return config.copy(); }

    /** 返回当前乐观锁版本。 */
    public long getRevision() { return revision; }

    /** 返回创建时间。 */
    public long getCreatedAt() { return createdAt; }

    /** 返回更新时间。 */
    public long getUpdatedAt() { return updatedAt; }

    /** 返回是否已安全保存认证凭据。 */
    public boolean isCredentialConfigured() { return credentialConfigured; }
}
