package io.patchbridge.agent.storage.jdbc;

import io.patchbridge.agent.mcp.McpCredentialCipher;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * MCP JDBC 凭据的 AES-256-GCM 实现。
 *
 * <p>每次加密生成独立 96-bit IV，Server 名称作为 AAD，存储格式带有
 * 显式版本字节。第一版只接受 256-bit Base64 密钥，不从默认值、
 * 主机信息或随机启动值推导，以免产生无法跨重启解密的假安全数据。
 */
public final class AesGcmMcpCredentialCipher implements McpCredentialCipher {

    /** 当前密文序列化版本。 */
    private static final byte FORMAT_VERSION = 1;

    /** GCM 推荐的 96-bit IV 字节数。 */
    private static final int IV_LENGTH = 12;

    /** GCM 认证标签位数。 */
    private static final int TAG_BITS = 128;

    /** AES-256 密钥。 */
    private final SecretKeySpec key;

    /** 密码学安全随机源，为每个密文生成独立 IV。 */
    private final SecureRandom secureRandom = new SecureRandom();

    /** 从标准 Base64 编码的 32 字节密钥创建密码器。 */
    public AesGcmMcpCredentialCipher(String base64Key) {
        if (base64Key == null || base64Key.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "JDBC MCP 模式必须配置 patchbridge-agent.mcp.jdbc.encryption-key");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("MCP JDBC encryption-key 必须是标准 Base64", e);
        }
        if (decoded.length != 32) {
            throw new IllegalArgumentException("MCP JDBC encryption-key 解码后必须为 32 字节");
        }
        this.key = new SecretKeySpec(decoded, "AES");
    }

    /** 使用随机 IV 加密凭据，并将版本、IV 和密文组合后 Base64 编码。 */
    @Override
    public String encrypt(String context, String plaintext) {
        requireContext(context);
        if (plaintext == null) {
            throw new IllegalArgumentException("待加密的 MCP 凭据不能为空");
        }
        byte[] iv = new byte[IV_LENGTH];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer payload = ByteBuffer.allocate(1 + IV_LENGTH + encrypted.length);
            payload.put(FORMAT_VERSION).put(iv).put(encrypted);
            return Base64.getEncoder().encodeToString(payload.array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("MCP 凭据加密失败", e);
        }
    }

    /** 校验版本、AAD 和 GCM 标签后返回 UTF-8 凭据。 */
    @Override
    public String decrypt(String context, String ciphertext) {
        requireContext(context);
        if (ciphertext == null || ciphertext.trim().isEmpty()) {
            throw new IllegalArgumentException("待解密的 MCP 凭据不能为空");
        }
        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(ciphertext);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("MCP 凭据密文不是有效 Base64", e);
        }
        if (payload.length <= 1 + IV_LENGTH || payload[0] != FORMAT_VERSION) {
            throw new IllegalStateException("MCP 凭据密文格式或版本不受支持");
        }
        byte[] iv = new byte[IV_LENGTH];
        byte[] encrypted = new byte[payload.length - 1 - IV_LENGTH];
        System.arraycopy(payload, 1, iv, 0, IV_LENGTH);
        System.arraycopy(payload, 1 + IV_LENGTH, encrypted, 0, encrypted.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("MCP 凭据密文校验或解密失败", e);
        }
    }

    /** AAD 上下文不能为空，否则无法将密文绑定到 Server。 */
    private static void requireContext(String context) {
        if (context == null || context.trim().isEmpty()) {
            throw new IllegalArgumentException("MCP 凭据加密上下文不能为空");
        }
    }
}
