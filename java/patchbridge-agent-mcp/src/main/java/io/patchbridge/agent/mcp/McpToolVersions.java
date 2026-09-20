package io.patchbridge.agent.mcp;

import io.patchbridge.agent.core.tool.ToolAnnotations;
import io.patchbridge.agent.core.tool.ToolDefinition;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Tool 定义与路由版本的唯一计算入口。
 *
 * <p>认证主体影响路由语义，必须参与版本计算；公开的无密钥摘要会成为低熵凭据的
 * 离线猜测校验器，因此这里使用独立的 256 位共享密钥和 HMAC-SHA-256。相同密钥、
 * 配置与定义在多实例上生成相同引用，密钥轮换会使旧引用明确失效。密钥仅由宿主
 * 外部注入，不以凭据推导、不生成进程私有默认值，也不复用存储加密密钥。
 */
final class McpToolVersions {
    /** 构造时固定的签名密钥；不得进入 ToolDefinition、日志或管理端响应。 */
    private final SecretKeySpec key;

    /** 严格接受标准 Base64 编码的 32 字节共享密钥，配置错误立即阻止 Registry 创建。 */
    McpToolVersions(String encodedKey) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedKey == null ? "" : encodedKey);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("mcp.tool-version-key 必须是标准 Base64 的 32 字节密钥");
        }
        if (decoded.length != 32) {
            throw new IllegalArgumentException("mcp.tool-version-key 必须是标准 Base64 的 32 字节密钥");
        }
        key = new SecretKeySpec(decoded, "HmacSHA256");
    }

    /** 计算本次定义与实际路由的版本；运维超时、TTL 与启停检查不参与调用语义摘要。 */
    String versionOf(String serverKey, McpServerConfig config, String remoteName,
                     String title, String description, Map<String, Object> inputSchema,
                     ToolAnnotations annotations, List<String> permission) {
        Map<String, Object> definition = new LinkedHashMap<String, Object>();
        definition.put("domain", "patchbridge/mcp-tool-version/v1");
        definition.put("serverKey", serverKey);
        definition.put("url", config.getUrl());
        definition.put("transport", config.getTransport());
        McpServerConfig.Auth auth = config.getAuth();
        Map<String, Object> identity = null;
        if (auth != null) {
            identity = new LinkedHashMap<String, Object>();
            identity.put("type", auth.getType());
            identity.put("username", auth.getUsername());
            identity.put("password", auth.getPassword());
            identity.put("token", auth.getToken());
            identity.put("headerName", auth.getHeaderName());
            identity.put("headers", auth.getHeaders());
        }
        definition.put("auth", identity);
        definition.put("remoteName", remoteName);
        definition.put("title", title);
        definition.put("description", description);
        definition.put("inputSchema", inputSchema);
        Map<String, Object> hints = null;
        if (annotations != null) {
            hints = new LinkedHashMap<String, Object>();
            hints.put("readOnly", annotations.isReadOnlyHint());
            hints.put("destructive", annotations.isDestructiveHint());
            hints.put("idempotent", annotations.isIdempotentHint());
            hints.put("requireConfirmation", annotations.isRequireConfirmation());
        }
        definition.put("annotations", hints);
        definition.put("permissions", permission);
        StringBuilder canonical = new StringBuilder();
        appendValue(canonical, definition);
        try {
            // Mac 不是线程安全对象；每次计算独立实例，只共享不可变密钥。
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] digest = mac.doFinal(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(Character.forDigit((item >> 4) & 0xF, 16));
                hex.append(Character.forDigit(item & 0xF, 16));
            }
            return hex.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("当前 JVM 无法计算 MCP Tool 版本引用", e);
        }
    }

    /** 从导入全名中截取远端 Tool 名；前两段分别是命名空间根与 Server 键。 */
    static String remoteNameOf(ToolDefinition imported) {
        String fullName = imported.getName();
        int first = fullName.indexOf('.');
        int second = fullName.indexOf('.', first + 1);
        return fullName.substring(second + 1);
    }

    /**
     * 按类型、长度和固定键序编码结构，字段内容中的换行或分隔符不能伪造相邻字段。
     * Map 与 List 显式编码元素个数，null 和空文本具有不同语义。
     */
    private static void appendValue(StringBuilder out, Object value) {
        if (value == null) {
            out.append('N');
        } else if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            List<String> keys = new ArrayList<String>();
            for (Object item : map.keySet()) {
                if (!(item instanceof String)) {
                    throw new IllegalArgumentException("MCP 版本中的对象键必须是字符串");
                }
                keys.add((String) item);
            }
            Collections.sort(keys);
            out.append('M').append(keys.size()).append(':');
            for (String name : keys) {
                appendValue(out, name);
                appendValue(out, map.get(name));
            }
        } else if (value instanceof List) {
            List<?> list = (List<?>) value;
            out.append('L').append(list.size()).append(':');
            for (Object item : list) {
                appendValue(out, item);
            }
        } else {
            if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                throw new IllegalArgumentException("MCP 版本只接受 JSON 值");
            }
            String text = String.valueOf(value);
            out.append(value instanceof String ? 'S' : value instanceof Boolean ? 'B' : 'D')
                    .append(text.length()).append(':').append(text);
        }
    }
}
