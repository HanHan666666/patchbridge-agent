package io.patchbridge.agent.mcp;

import io.patchbridge.agent.core.tool.ToolAnnotations;
import io.patchbridge.agent.core.tool.ToolDefinition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * MCP Tool 定义/路由版本引用的唯一计算入口。
 *
 * <p>版本引用是“服务端认可的定义 + 路由”的确定性内容摘要：同一份有效配置与
 * 同一份远程导入结果在任何实例上算出相同版本，因此多实例之间无需共享内存状态，
 * 即可判断浏览器回传的引用是否过期。
 *
 * <p>纳入摘要的是影响调用语义与路由目标的字段：endpoint、transport、认证方式
 * （含 API Key Header 名与静态 Header 名），以及该 Tool 自身的导入结果
 * （标题、描述、Schema、注记与权限映射）。凭据明文、超时、缓存 TTL 与启停标记
 * 不改变调用的含义：凭据轮换仍由远程服务端鉴权把关，启停与工具下线由调用时的
 * enabled 检查和快照查找把关，因此不参与版本计算，避免无关运维操作
 * 打散全部进行中的调用引用。
 */
final class McpToolVersions {

    /** 工具类只承载静态计算逻辑，不允许实例化。 */
    private McpToolVersions() {
    }

    /**
     * 计算单个导入 Tool 的定义/路由版本引用；结果写入 ToolDefinition 并随发现下发。
     *
     * @param serverKey 配置源中的 Server 键；路由目标的一部分
     * @param config 该 Server 当前生效的运行配置
     * @param remoteName 远端 Tool 名
     * @param title 远端标题（可空）
     * @param description 远端描述
     * @param inputSchema 远端输入 Schema
     * @param annotations 导入注记
     * @param permission 宿主权限映射结果；未映射时为空列表
     * @return 十六进制 SHA-256 摘要，作为 ToolDefinition.version
     */
    static String versionOf(String serverKey, McpServerConfig config, String remoteName,
                            String title, String description, Map<String, Object> inputSchema,
                            ToolAnnotations annotations, List<String> permission) {
        StringBuilder canonical = new StringBuilder();
        // 路由语义：目标地址与传输方式决定“调用打到哪里”。
        canonical.append("url=").append(nullSafe(config.getUrl())).append('\n');
        canonical.append("transport=").append(nullSafe(config.getTransport())).append('\n');
        // 认证方式只取类型与 Header 名，绝不纳入任何凭据明文。
        McpServerConfig.Auth auth = config.getAuth();
        canonical.append("auth.type=")
                .append(auth == null ? "" : nullSafe(auth.getType())).append('\n');
        canonical.append("auth.headerName=")
                .append(auth == null ? "" : nullSafe(auth.getHeaderName())).append('\n');
        if (auth != null && McpServerConfig.Auth.STATIC_HEADERS.equals(auth.getType())
                && auth.getHeaders() != null) {
            for (Map.Entry<String, String> header : auth.getHeaders().entrySet()) {
                canonical.append("auth.header=").append(nullSafe(header.getKey())).append('\n');
            }
        }
        // 定义语义：模型据此决定是否调用、如何构造参数。
        canonical.append("serverKey=").append(nullSafe(serverKey)).append('\n');
        canonical.append("remoteName=").append(nullSafe(remoteName)).append('\n');
        canonical.append("title=").append(nullSafe(title)).append('\n');
        canonical.append("description=").append(nullSafe(description)).append('\n');
        appendSchema(canonical, inputSchema);
        if (annotations != null) {
            canonical.append("annotation.readOnly=").append(annotations.isReadOnlyHint()).append('\n');
            canonical.append("annotation.destructive=")
                    .append(annotations.isDestructiveHint()).append('\n');
            canonical.append("annotation.idempotent=")
                    .append(annotations.isIdempotentHint()).append('\n');
            canonical.append("annotation.requireConfirmation=")
                    .append(annotations.isRequireConfirmation()).append('\n');
        }
        for (String permissionKey : permission) {
            canonical.append("permission=").append(nullSafe(permissionKey)).append('\n');
        }
        return sha256Hex(canonical.toString());
    }

    /** 从导入定义的全名（namespaceRoot.serverKey.remoteName）中截取远端 Tool 名。 */
    static String remoteNameOf(ToolDefinition imported) {
        String fullName = imported.getName();
        // 前两段分别是命名空间根与 Server 键（均不含点）；远端名本身可以包含点。
        int first = fullName.indexOf('.');
        int second = fullName.indexOf('.', first + 1);
        return fullName.substring(second + 1);
    }

    /** 以确定性的键序递归序列化 JSON Schema，保证跨实例摘要一致。 */
    private static void appendSchema(StringBuilder canonical, Map<String, Object> schema) {
        canonical.append("schema.begin\n");
        if (schema != null) {
            for (Object key : sortedKeys(schema)) {
                canonical.append("schema.key=").append(String.valueOf(key)).append('\n');
                appendValue(canonical, schema.get(key));
            }
        }
        canonical.append("schema.end\n");
    }

    /** 递归序列化 Schema 值；List 与 Map 逐元素展开，标量按类型化文本写入。 */
    private static void appendValue(StringBuilder canonical, Object value) {
        if (value == null) {
            canonical.append("value.null\n");
            return;
        }
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            canonical.append("value.map.begin\n");
            for (Object key : sortedKeys(map)) {
                canonical.append("value.map.key=").append(String.valueOf(key)).append('\n');
                appendValue(canonical, map.get(key));
            }
            canonical.append("value.map.end\n");
            return;
        }
        if (value instanceof List) {
            canonical.append("value.list.begin\n");
            for (Object element : (List<?>) value) {
                appendValue(canonical, element);
            }
            canonical.append("value.list.end\n");
            return;
        }
        canonical.append("value.").append(value.getClass().getSimpleName())
                .append('=').append(value).append('\n');
    }

    /** 所有层级的对象键统一按键排序，键序差异不产生不同版本。 */
    private static List<Object> sortedKeys(Map<?, ?> map) {
        List<Object> keys = new ArrayList<Object>(map.keySet());
        Collections.sort(keys, (left, right) -> String.valueOf(left)
                .compareTo(String.valueOf(right)));
        return keys;
    }

    /** 计算 UTF-8 SHA-256 摘要的小写十六进制表示。 */
    private static String sha256Hex(String canonical) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // JDK 规范要求所有实现提供 SHA-256，缺失属于 JVM 配置错误而不是业务分支。
            throw new IllegalStateException("当前 JVM 不支持 SHA-256，无法计算 Tool 版本引用", e);
        }
        byte[] hashed = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(hashed.length * 2);
        for (byte item : hashed) {
            hex.append(Character.forDigit((item >> 4) & 0xF, 16));
            hex.append(Character.forDigit(item & 0xF, 16));
        }
        return hex.toString();
    }

    /** null 以空串参与摘要，避免“缺字段”与“空字段”产生不同哈希。 */
    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
