package io.patchbridge.agent.mcp;

/**
 * JDBC MCP 凭据的对称加密端口。
 *
 * <p>context 必须作为认证附加数据参与校验，使密文与 Server 名称绑定，
 * 防止数据库中两个 Server 的凭据密文被直接互换。
 */
public interface McpCredentialCipher {

    /** 加密 UTF-8 明文并返回可存储文本。 */
    String encrypt(String context, String plaintext);

    /** 验证并解密存储文本，任何篡改都必须明确失败。 */
    String decrypt(String context, String ciphertext);
}
