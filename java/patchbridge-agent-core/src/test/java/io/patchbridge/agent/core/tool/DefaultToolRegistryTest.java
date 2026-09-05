package io.patchbridge.agent.core.tool;

import io.patchbridge.agent.core.auth.AuthenticatedToolAccessPolicy;
import io.patchbridge.agent.core.auth.ToolAccessPolicy;
import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.error.ToolAccessDeniedException;
import io.patchbridge.agent.core.error.ToolExecutionException;
import io.patchbridge.agent.core.error.ToolVersionMismatchException;
import io.patchbridge.agent.core.user.UserContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 覆盖设计不变量：
 * 1. list 按 canDiscover 过滤，call 每次重新执行 canInvoke（浏览器不是安全边界）；
 * 2. 命名空间前缀路由正确（含 mcp.xxx 这类带点命名空间的最长匹配）；
 * 3. 命名空间冲突在构造期失败；
 * 4. call 携带的定义/路由版本引用必须与当前定义一致，过期引用明确失败（VA-01）。
 */
class DefaultToolRegistryTest {

    /** 可编程的假 Provider，用于断言路由、版本透传与调用。 */
    static class StubProvider implements ToolProvider {
        final String namespace;
        final List<ToolDefinition> tools;
        final AtomicInteger invoked = new AtomicInteger();
        /** 记录 Registry 透传的版本引用，供测试断言调用链完整性。 */
        final AtomicReference<String> lastVersion = new AtomicReference<String>();

        StubProvider(String namespace, String... toolNames) {
            this.namespace = namespace;
            this.tools = new java.util.ArrayList<ToolDefinition>();
            for (String toolName : toolNames) {
                tools.add(new ToolDefinition(namespace + "." + toolName, toolName, "desc",
                        Collections.<String, Object>emptyMap(),
                        new ToolAnnotations(true, false, true, false), ToolSource.LOCAL,
                        Collections.<String>emptyList(), null));
            }
        }

        /** 创建携带统一版本引用的 Provider，用于版本一致性用例。 */
        static StubProvider withVersion(String namespace, String version, String toolName) {
            StubProvider provider = new StubProvider(namespace, toolName);
            provider.tools.set(0, new ToolDefinition(
                    namespace + "." + toolName, toolName, "desc",
                    Collections.<String, Object>emptyMap(),
                    new ToolAnnotations(true, false, true, false), ToolSource.LOCAL,
                    Collections.<String>emptyList(), version));
            return provider;
        }

        @Override
        public String namespace() {
            return namespace;
        }

        @Override
        public List<ToolDefinition> list() {
            return tools;
        }

        @Override
        public ToolCallResult call(String toolName, String definitionVersion,
                                   Map<String, Object> arguments, AiRequestContext ctx) {
            invoked.incrementAndGet();
            lastVersion.set(definitionVersion);
            return ToolCallResult.ofText("executed:" + toolName);
        }
    }

    /** 只放行指定工具的假策略，用于验证过滤与逐次校验。 */
    static class AllowListPolicy implements ToolAccessPolicy {
        private final String allowedFullName;

        AllowListPolicy(String allowedFullName) {
            this.allowedFullName = allowedFullName;
        }

        @Override
        public boolean canDiscover(UserContext user, ToolDefinition tool) {
            return tool.getName().equals(allowedFullName);
        }

        @Override
        public boolean canInvoke(UserContext user, ToolDefinition tool) {
            return tool.getName().equals(allowedFullName);
        }
    }

    private final AiRequestContext ctx = new AiRequestContext(
            UserContext.builder().userId("u1").build(), "trace-1", null, null, null);

    @Test
    void listFiltersByDiscoverPolicy() {
        StubProvider local = new StubProvider("local", "device_get", "device_restart");
        DefaultToolRegistry registry = new DefaultToolRegistry(
                Arrays.<ToolProvider>asList(local), new AllowListPolicy("local.device_get"));

        List<ToolDefinition> visible = registry.list(ctx.getUser());
        assertEquals(1, visible.size());
        assertEquals("local.device_get", visible.get(0).getName());
    }

    @Test
    void longestNamespacePrefixRouting() throws Exception {
        StubProvider inventory = new StubProvider("mcp.inventory", "query_stock");
        StubProvider crm = new StubProvider("mcp.crm", "search_customer");
        DefaultToolRegistry registry = new DefaultToolRegistry(
                Arrays.<ToolProvider>asList(inventory, crm), new AuthenticatedToolAccessPolicy());

        assertEquals("executed:query_stock",
                registry.call("mcp.inventory.query_stock", null,
                        new HashMap<String, Object>(), ctx).getContent().get(0).getText());
        assertEquals("executed:search_customer",
                registry.call("mcp.crm.search_customer", null,
                        new HashMap<String, Object>(), ctx).getContent().get(0).getText());
        assertEquals(1, inventory.invoked.get());
        assertEquals(1, crm.invoked.get());
    }

    @Test
    void invokeRechecksPolicyEveryCall() {
        StubProvider local = new StubProvider("local", "device_restart");
        // 策略允许发现但不允许调用，验证 call 与 list 各自独立校验
        ToolAccessPolicy denyInvoke = new ToolAccessPolicy() {
            @Override
            public boolean canDiscover(UserContext user, ToolDefinition tool) {
                return true;
            }

            @Override
            public boolean canInvoke(UserContext user, ToolDefinition tool) {
                return false;
            }
        };
        DefaultToolRegistry registry = new DefaultToolRegistry(
                Arrays.<ToolProvider>asList(local), denyInvoke);

        assertEquals(1, registry.list(ctx.getUser()).size());
        assertThrows(ToolAccessDeniedException.class, () ->
                registry.call("local.device_restart", null, new HashMap<String, Object>(), ctx));
        assertEquals(0, local.invoked.get(), "被拒绝的调用不得触达 Provider");
    }

    @Test
    void unknownToolFails() {
        DefaultToolRegistry registry = new DefaultToolRegistry(
                Arrays.<ToolProvider>asList(new StubProvider("local", "a")),
                new AuthenticatedToolAccessPolicy());
        assertThrows(ToolExecutionException.class, () ->
                registry.call("local.missing", null, new HashMap<String, Object>(), ctx));
        assertNull(registry.find("local.missing"));
    }

    @Test
    void conflictingNamespacesRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DefaultToolRegistry(
                Arrays.<ToolProvider>asList(new StubProvider("mcp", "a"),
                        new StubProvider("mcp.inventory", "b")),
                new AuthenticatedToolAccessPolicy()));

        assertThrows(IllegalArgumentException.class, () -> new DefaultToolRegistry(
                Arrays.<ToolProvider>asList(new StubProvider("local", "a"),
                        new StubProvider("local", "b")),
                new AuthenticatedToolAccessPolicy()));
    }

    /** 版本一致时调用放行，且 Registry 把版本原样透传给 Provider。 */
    @Test
    void matchingVersionReachesProvider() throws Exception {
        StubProvider mcp = StubProvider.withVersion("mcp.inv", "v-1", "query_stock");
        DefaultToolRegistry registry = new DefaultToolRegistry(
                Arrays.<ToolProvider>asList(mcp), new AuthenticatedToolAccessPolicy());

        assertEquals("executed:query_stock",
                registry.call("mcp.inv.query_stock", "v-1",
                        new HashMap<String, Object>(), ctx).getContent().get(0).getText());
        assertEquals("v-1", mcp.lastVersion.get());
    }

    /**
     * VA-01：定义取得后同名 Tool 的定义/路由版本发生变化时，
     * 携带过期引用的调用必须明确失败，且不触达 Provider。
     */
    @Test
    void staleVersionFailsWithoutReachingProvider() {
        StubProvider mcp = StubProvider.withVersion("mcp.inv", "v-1", "query_stock");
        DefaultToolRegistry registry = new DefaultToolRegistry(
                Arrays.<ToolProvider>asList(mcp), new AuthenticatedToolAccessPolicy());

        assertThrows(ToolVersionMismatchException.class, () ->
                registry.call("mcp.inv.query_stock", "v-0",
                        new HashMap<String, Object>(), ctx));
        assertEquals(0, mcp.invoked.get(), "过期版本引用不得触达 Provider");
    }

    /** 过期版本引用不能成为免检凭证：版本校验不改变每次调用的授权检查。 */
    @Test
    void versionCheckDoesNotBypassInvocationPolicy() {
        StubProvider mcp = StubProvider.withVersion("mcp.inv", "v-1", "query_stock");
        DefaultToolRegistry registry = new DefaultToolRegistry(
                Arrays.<ToolProvider>asList(mcp), new AllowListPolicy("nothing"));

        assertThrows(ToolAccessDeniedException.class, () ->
                registry.call("mcp.inv.query_stock", "v-1",
                        new HashMap<String, Object>(), ctx));
        assertEquals(0, mcp.invoked.get());
    }

    /** 静态 Tool 版本为 null：null 对 null 一致放行，携带任意非 null 引用则失败。 */
    @Test
    void staticToolRequiresNullVersionReference() {
        StubProvider local = new StubProvider("local", "device_get");
        DefaultToolRegistry registry = new DefaultToolRegistry(
                Arrays.<ToolProvider>asList(local), new AuthenticatedToolAccessPolicy());

        assertThrows(ToolVersionMismatchException.class, () ->
                registry.call("local.device_get", "v-1",
                        new HashMap<String, Object>(), ctx));
        assertEquals(0, local.invoked.get());
    }

    /** Tool 权限元数据必须完整保留，并与调用方传入的可变列表隔离。 */
    @Test
    void permissionsAreImmutableDefensiveCopy() {
        List<String> permissions = new ArrayList<String>(
                Arrays.asList("device:read", "tenant:read"));
        ToolDefinition definition = new ToolDefinition(
                "local.read", "read", "desc", Collections.<String, Object>emptyMap(),
                new ToolAnnotations(true, false, true, false), ToolSource.LOCAL,
                permissions, null);

        permissions.clear();

        assertEquals(Arrays.asList("device:read", "tenant:read"),
                definition.getPermissions());
        assertThrows(UnsupportedOperationException.class,
                () -> definition.getPermissions().add("unexpected"));
    }

    /** Tool 声明是不可变对象，嵌套 Schema 也必须做深度防御复制。 */
    @Test
    @SuppressWarnings("unchecked")
    void inputSchemaIsDeeplyImmutableDefensiveCopy() {
        Map<String, Object> property = new HashMap<String, Object>();
        property.put("type", "string");
        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put("name", property);
        Map<String, Object> schema = new HashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", properties);

        ToolDefinition definition = new ToolDefinition(
                "local.read", "read", "desc", schema,
                new ToolAnnotations(true, false, true, false), ToolSource.LOCAL,
                Collections.<String>emptyList(), null);
        property.put("description", "late mutation");

        Map<String, Object> copiedProperties = (Map<String, Object>)
                definition.getInputSchema().get("properties");
        Map<String, Object> copiedProperty = (Map<String, Object>)
                copiedProperties.get("name");
        assertNull(copiedProperty.get("description"));
        assertThrows(UnsupportedOperationException.class,
                () -> copiedProperty.put("description", "unexpected"));
    }
}
