package io.patchbridge.agent.starter.tool;

import io.patchbridge.agent.annotations.AiParam;
import io.patchbridge.agent.annotations.AiTool;
import io.patchbridge.agent.core.error.ToolExecutionException;
import io.patchbridge.agent.core.schema.SimpleReflectionSchemaGenerator;
import io.patchbridge.agent.core.tool.ToolCallResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AnnotatedToolProvider} 的严格扫描与参数绑定测试。
 *
 * <p>这些测试刻意直接使用 Spring BeanFactory 与 AOP 代理，避免完整 Boot 上下文
 * 掩盖反射边界问题，并确保 annotations 模块无需依赖 Spring 即可声明 Tool。
 */
class AnnotatedToolProviderTest {

    /** 泛型集合参数必须按元素类型反序列化，不能退化为 LinkedHashMap。 */
    @Test
    void shouldBindParameterizedCollectionElementType() throws Exception {
        AnnotatedToolProvider provider = providerWith(new BindingTools());
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("value", "alpha");

        ToolCallResult result = provider.call("generic",
                Collections.<String, Object>singletonMap("items", Collections.singletonList(item)),
                null);

        assertEquals("Payload:alpha", textOf(result));
        assertEquals(Arrays.asList("device:read", "tenant:read"),
                provider.list().stream()
                        .filter(tool -> "local.generic".equals(tool.getName()))
                        .findFirst().get().getPermissions());
    }

    /** 显式 required 参数缺失或为 null 时都必须在进入业务方法前失败。 */
    @Test
    void shouldRejectMissingOrNullRequiredArgument() {
        AnnotatedToolProvider provider = providerWith(new BindingTools());

        ToolExecutionException missing = assertThrows(ToolExecutionException.class,
                () -> provider.call("generic", Collections.<String, Object>emptyMap(), null));
        ToolExecutionException nullValue = assertThrows(ToolExecutionException.class,
                () -> provider.call("generic",
                        Collections.<String, Object>singletonMap("items", null), null));

        assertEquals("缺少必填参数: items", missing.getMessage());
        assertEquals("缺少必填参数: items", nullValue.getMessage());
    }

    /** 即使注解未标 required，Java 基本类型也不能接收缺失值或 null。 */
    @Test
    void shouldRejectMissingPrimitiveArgument() {
        AnnotatedToolProvider provider = providerWith(new BindingTools());

        ToolExecutionException failure = assertThrows(ToolExecutionException.class,
                () -> provider.call("primitive", Collections.<String, Object>emptyMap(), null));

        assertEquals("基本类型参数不能为空: count", failure.getMessage());
    }

    /** 模型参数对象必须拒绝 Schema 外字段，包括尝试伪造上下文的字段。 */
    @Test
    void shouldRejectUnknownArgument() {
        AnnotatedToolProvider provider = providerWith(new BindingTools());
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("count", 1);
        arguments.put("tenantId", "forged");

        ToolExecutionException failure = assertThrows(ToolExecutionException.class,
                () -> provider.call("primitive", arguments, null));

        assertTrue(failure.getMessage().contains("tenantId"));
    }

    /** 继承但未覆写的公共 Tool 方法必须能被子类 Bean 扫描并调用。 */
    @Test
    void shouldDiscoverInheritedToolMethod() throws Exception {
        AnnotatedToolProvider provider = providerWith(new InheritedTools());

        ToolCallResult result = provider.call("inherited",
                Collections.<String, Object>singletonMap("value", "ok"), null);

        assertEquals("parent:ok", textOf(result));
    }

    /** 泛型实现产生的桥接方法只注册一次，并必须通过 JDK 代理触发宿主切面。 */
    @Test
    void shouldInvokeGenericBridgeMethodThroughJdkProxy() throws Exception {
        AtomicInteger adviceCalls = new AtomicInteger();
        ProxyFactory proxyFactory = new ProxyFactory(new StringBridgeTools());
        proxyFactory.setInterfaces(GenericToolContract.class);
        proxyFactory.addAdvice((MethodInterceptor) invocation -> {
            adviceCalls.incrementAndGet();
            return invocation.proceed();
        });
        Object proxy = proxyFactory.getProxy();
        AnnotatedToolProvider provider = providerWith(proxy);

        ToolCallResult result = provider.call("bridge",
                Collections.<String, Object>singletonMap("value", "ok"), null);

        assertEquals(1, provider.list().size());
        assertEquals("bridge:ok", textOf(result));
        assertEquals(1, adviceCalls.get());
    }

    /** 非 public 的 Tool 声明必须导致启动失败，不能通过反射放宽访问权限。 */
    @Test
    void shouldRejectNonPublicToolMethod() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> providerWith(new NonPublicTools()));

        assertTrue(failure.getMessage().contains("public 方法"));
    }

    /** 公共方法若声明类本身不可访问，也必须在启动期明确拒绝。 */
    @Test
    void shouldRejectMethodWithoutPublicInvocationPath() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> providerWith(new PackagePrivateTools()));

        assertTrue(failure.getMessage().contains("无法通过 Spring Bean 的公共 API 调用"));
    }

    /** 业务异常详情只保留在日志，模型只能收到稳定的通用失败文案。 */
    @Test
    void shouldHideBusinessExceptionDetailsFromModel() throws Exception {
        AnnotatedToolProvider provider = providerWith(new FailingTools());

        ToolCallResult result = provider.call("failure",
                Collections.<String, Object>emptyMap(), null);

        assertTrue(result.isError());
        assertEquals("Tool 执行失败", textOf(result));
        assertFalse(textOf(result).contains("secret-token"));
    }

    /** Bean 创建失败必须中止扫描，不能被静默解释成该 Bean 没有 Tool。 */
    @Test
    void shouldPropagateBeanCreationFailure() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        RootBeanDefinition definition = new RootBeanDefinition(BindingTools.class);
        definition.setInstanceSupplier(() -> {
            throw new IllegalStateException("broken tool bean");
        });
        beanFactory.registerBeanDefinition("brokenTool", definition);
        AnnotatedToolProvider provider = new AnnotatedToolProvider(
                beanFactory, new SimpleReflectionSchemaGenerator(), new ObjectMapper(), "local");

        BeanCreationException failure = assertThrows(BeanCreationException.class,
                provider::afterSingletonsInstantiated);

        assertTrue(failure.getMessage().contains("brokenTool"));
    }

    /** 扫描器不得为了发现 Tool 强制初始化完全无关的 lazy Bean。 */
    @Test
    void shouldNotInstantiateUnrelatedLazyBean() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        AtomicInteger created = new AtomicInteger();
        RootBeanDefinition definition = new RootBeanDefinition(UnrelatedBean.class);
        definition.setLazyInit(true);
        definition.setInstanceSupplier(() -> {
            created.incrementAndGet();
            return new UnrelatedBean();
        });
        beanFactory.registerBeanDefinition("unrelatedLazyBean", definition);
        AnnotatedToolProvider provider = new AnnotatedToolProvider(
                beanFactory, new SimpleReflectionSchemaGenerator(), new ObjectMapper(), "local");

        provider.afterSingletonsInstantiated();

        assertEquals(0, created.get());
        assertTrue(provider.list().isEmpty());
    }

    /**
     * 以真实 BeanDefinition 构建待测 Provider，保证扫描路径与 Starter 启动期一致。
     *
     * @param beans 待扫描的业务 Bean 或代理
     * @return 已完成启动期扫描的 Provider
     */
    private AnnotatedToolProvider providerWith(Object... beans) {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        for (int index = 0; index < beans.length; index++) {
            Object bean = beans[index];
            RootBeanDefinition definition = new RootBeanDefinition(bean.getClass());
            definition.setInstanceSupplier(() -> bean);
            beanFactory.registerBeanDefinition("tool" + index, definition);
        }
        AnnotatedToolProvider provider = new AnnotatedToolProvider(
                beanFactory, new SimpleReflectionSchemaGenerator(), new ObjectMapper(), "local");
        provider.afterSingletonsInstantiated();
        return provider;
    }

    /** 从单文本块结果中取出断言内容。 */
    private String textOf(ToolCallResult result) {
        return result.getContent().get(0).getText();
    }

    /** 覆盖泛型、required 与基本类型绑定的业务 Tool。 */
    public static class BindingTools {

        /** 验证集合元素已经转换为明确 DTO 类型。 */
        @AiTool(name = "generic", permissions = {"device:read", "tenant:read"})
        public String generic(
                @AiParam(name = "items", required = true) List<Payload> items) {
            Payload first = items.get(0);
            return first.getClass().getSimpleName() + ":" + first.value;
        }

        /** 验证基本类型缺失不会以反射异常的形式延迟暴露。 */
        @AiTool(name = "primitive")
        public int primitive(@AiParam(name = "count") int count) {
            return count;
        }
    }

    /** 泛型集合的业务 DTO。 */
    public static class Payload {

        /** 用于验证 Jackson 泛型元素转换的测试值。 */
        public String value;
    }

    /** 声明可由子类直接继承的 Tool。 */
    public static class ParentTools {

        /** 返回继承调用标记。 */
        @AiTool(name = "inherited")
        public String inherited(@AiParam(name = "value", required = true) String value) {
            return "parent:" + value;
        }
    }

    /** 不覆写父类方法，用于验证继承扫描。 */
    public static class InheritedTools extends ParentTools {
    }

    /** 泛型 Tool 合约，用于构造 JDK 动态代理。 */
    public interface GenericToolContract<T> {

        /** 泛型转换操作。 */
        T convert(T value);
    }

    /** 编译后同时包含具体方法与 bridge 方法的业务实现。 */
    public static class StringBridgeTools implements GenericToolContract<String> {

        /** 通过 JDK 代理暴露的桥接 Tool。 */
        @Override
        @AiTool(name = "bridge")
        public String convert(@AiParam(name = "value", required = true) String value) {
            return "bridge:" + value;
        }
    }

    /** 包含非法非公共 Tool 方法的业务类型。 */
    public static class NonPublicTools {

        /** 此方法用于验证启动期可见性拒绝。 */
        @AiTool(name = "private")
        private String hidden() {
            return "hidden";
        }
    }

    /** 类本身不可公开访问，用于验证无 setAccessible 的调用边界。 */
    static class PackagePrivateTools {

        /** 方法虽为 public，但声明类不可从框架包访问。 */
        @AiTool(name = "unreachable")
        public String unreachable() {
            return "unreachable";
        }
    }

    /** 抛出包含敏感详情的业务异常。 */
    public static class FailingTools {

        /** 模拟下游系统把凭证写入异常消息。 */
        @AiTool(name = "failure")
        public String fail() {
            throw new IllegalStateException("downstream secret-token leaked");
        }
    }

    /** 不包含 Tool 的 lazy Bean。 */
    public static class UnrelatedBean {
    }
}
