package io.patchbridge.agent.starter.tool;

import io.patchbridge.agent.annotations.AiParam;
import io.patchbridge.agent.annotations.AiTool;
import io.patchbridge.agent.core.context.AiRequestContext;
import io.patchbridge.agent.core.error.ToolExecutionException;
import io.patchbridge.agent.core.schema.ToolSchemaGenerator;
import io.patchbridge.agent.core.tool.ToolAnnotations;
import io.patchbridge.agent.core.tool.ToolCallResult;
import io.patchbridge.agent.core.tool.ToolDefinition;
import io.patchbridge.agent.core.tool.ToolProvider;
import io.patchbridge.agent.core.tool.ToolSource;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本地 {@link AiTool} 的 Spring 扫描适配器与执行入口。
 *
 * <p>注解定义仍位于零 Spring 依赖的 annotations 模块；本类只负责把 Spring Bean
 * 适配到 Core 的 {@link ToolProvider} 契约。扫描发生在单例初始化完成后，继承方法、
 * 编译器桥接方法与 AOP 代理都统一解析到一个业务方法，并通过代理可调用的方法执行，
 * 以保证事务、鉴权等宿主切面不会被绕开。
 *
 * <p>模型参数采用严格对象边界：拒绝未知字段，校验必填与基本类型参数，并使用 Jackson
 * {@link JavaType} 保留集合等泛型信息。业务代码若抛异常，完整详情只写服务端日志，
 * 返回给模型的是稳定的通用错误，避免泄露内部实现与敏感数据。
 */
public class AnnotatedToolProvider implements ToolProvider, SmartInitializingSingleton {

    /** 返回给模型的业务异常文案；具体原因只能出现在服务端日志中。 */
    private static final String SAFE_EXECUTION_ERROR = "Tool 执行失败";

    /** 当前适配器的日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(AnnotatedToolProvider.class);

    /** 宿主 Bean 容器，用于启动期发现本地 Tool。 */
    private final ListableBeanFactory beanFactory;

    /** 将业务方法签名转换为模型可见 JSON Schema 的 Core SPI。 */
    private final ToolSchemaGenerator schemaGenerator;

    /** 负责严格参数转换与业务返回值序列化。 */
    private final ObjectMapper objectMapper;

    /** 当前 Provider 对外暴露的稳定命名空间。 */
    private final String namespace;

    /** 扫描结果；单例初始化完成后不再修改。 */
    private final Map<String, ToolMethod> toolsByName = new LinkedHashMap<String, ToolMethod>();

    /**
     * 创建 Spring 本地 Tool 适配器。
     *
     * @param beanFactory Spring Bean 容器
     * @param schemaGenerator 方法 Schema 生成器
     * @param objectMapper 参数与结果转换器
     * @param namespace 本地 Tool 命名空间
     */
    public AnnotatedToolProvider(ListableBeanFactory beanFactory,
                                 ToolSchemaGenerator schemaGenerator,
                                 ObjectMapper objectMapper,
                                 String namespace) {
        this.beanFactory = beanFactory;
        this.schemaGenerator = schemaGenerator;
        this.objectMapper = objectMapper;
        this.namespace = namespace;
    }

    /** 返回本 Provider 的 Tool 命名空间。 */
    @Override
    public String namespace() {
        return namespace;
    }

    /** 返回启动期已经校验并注册的本地 Tool 定义。 */
    @Override
    public List<ToolDefinition> list() {
        List<ToolDefinition> definitions = new ArrayList<ToolDefinition>(toolsByName.size());
        for (ToolMethod tool : toolsByName.values()) {
            definitions.add(tool.definition);
        }
        return definitions;
    }

    /**
     * 严格绑定模型参数并通过 Spring 代理调用业务方法。
     *
     * @param localName Provider 内的 Tool 名称
     * @param arguments 模型生成的业务参数
     * @param requestContext 服务端可信请求上下文
     * @return 可直接返回给模型的调用结果
     * @throws ToolExecutionException Tool 不存在、参数不合法或反射调用失败
     */
    @Override
    public ToolCallResult call(String localName, Map<String, Object> arguments,
                               AiRequestContext requestContext) throws ToolExecutionException {
        ToolMethod tool = toolsByName.get(localName);
        if (tool == null) {
            throw new ToolExecutionException("本地 Tool 不存在: " + localName);
        }
        Object[] args = bindArguments(tool, arguments == null
                ? Collections.<String, Object>emptyMap() : arguments, requestContext);
        try {
            Object result = tool.invocableMethod.invoke(tool.bean, args);
            return toResult(result);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException() == null ? e : e.getTargetException();
            log.warn("@AiTool [{}] 业务执行异常", localName, cause);
            return ToolCallResult.ofError(SAFE_EXECUTION_ERROR);
        } catch (IllegalAccessException e) {
            throw new ToolExecutionException("Tool 方法不可调用: " + localName, e);
        } catch (IllegalArgumentException e) {
            throw new ToolExecutionException("Tool 调用参数与方法签名不匹配: " + localName, e);
        }
    }

    /**
     * 扫描 Bean 并在启动期完成全部注册校验。
     *
     * <p>不吞掉 Bean 创建异常：扫描器不能把宿主容器异常伪装成“没有 Tool”。
     */
    @Override
    public void afterSingletonsInstantiated() {
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            Object bean = resolveCandidateBean(beanName);
            if (bean == null) {
                continue;
            }
            Class<?> targetClass = AopProxyUtils.ultimateTargetClass(bean);
            for (ToolCandidate candidate : findCandidates(targetClass)) {
                register(bean, targetClass, candidate);
            }
        }
        log.info("@AiTool 扫描完成，共注册 {} 个本地 Tool（命名空间 {}）",
                toolsByName.size(), namespace);
    }

    /**
     * 获取需要检查的 Bean，同时避免为了扫描 Tool 强制初始化无关注解的 lazy Bean。
     *
     * <p>非 lazy 单例在该生命周期点本就已经创建；lazy Bean 先检查 Spring 可预测类型，
     * 只有存在 Tool 候选时才实例化。无法预测类型的 lazy FactoryBean 不会被框架擅自触发，
     * 如需暴露 Tool 应通过公开接口声明注解或改为非 lazy Bean。
     *
     * @param beanName Spring Bean 名称
     * @return 需要扫描的 Bean；确认无 Tool 的 lazy Bean 返回 null
     */
    private Object resolveCandidateBean(String beanName) {
        if (!(beanFactory instanceof ConfigurableListableBeanFactory)) {
            return beanFactory.getBean(beanName);
        }
        ConfigurableListableBeanFactory configurable =
                (ConfigurableListableBeanFactory) beanFactory;
        if (configurable.containsSingleton(beanName)) {
            return beanFactory.getBean(beanName);
        }
        if (!configurable.containsBeanDefinition(beanName)
                || !configurable.getBeanDefinition(beanName).isLazyInit()) {
            return beanFactory.getBean(beanName);
        }
        Class<?> predictedType = beanFactory.getType(beanName);
        if (predictedType == null) {
            log.debug("跳过无法预测类型的 lazy Bean [{}]；@AiTool 扫描不会触发其初始化", beanName);
            return null;
        }
        if (findCandidates(predictedType).isEmpty()) {
            return null;
        }
        return beanFactory.getBean(beanName);
    }

    /**
     * 收集目标类型、父类与接口上的 Tool 声明，并按最终业务方法去重桥接方法。
     *
     * @param targetClass AOP 代理背后的业务类型
     * @return 已解析到最具体业务方法的候选项
     */
    private List<ToolCandidate> findCandidates(Class<?> targetClass) {
        Map<Method, ToolCandidate> candidates = new LinkedHashMap<Method, ToolCandidate>();
        collectCandidates(targetClass, targetClass, candidates, new HashSet<Class<?>>());
        return new ArrayList<ToolCandidate>(candidates.values());
    }

    /**
     * 递归遍历类型层次。最具体类型优先，因此覆盖方法自己声明的注解不会被父类型覆盖。
     *
     * @param source 当前遍历类型
     * @param targetClass 最终业务类型
     * @param candidates 已发现候选项
     * @param visited 防止接口菱形继承造成重复遍历
     */
    private void collectCandidates(Class<?> source, Class<?> targetClass,
                                   Map<Method, ToolCandidate> candidates,
                                   Set<Class<?>> visited) {
        if (source == null || source == Object.class || !visited.add(source)) {
            return;
        }
        for (Method declaredMethod : source.getDeclaredMethods()) {
            AiTool annotation = AnnotatedElementUtils.findMergedAnnotation(declaredMethod, AiTool.class);
            if (annotation == null) {
                continue;
            }
            validatePublicDeclaration(declaredMethod);
            Method specificMethod = AopUtils.getMostSpecificMethod(declaredMethod, targetClass);
            Method bridgedMethod = BridgeMethodResolver.findBridgedMethod(specificMethod);
            validatePublicDeclaration(bridgedMethod);
            if (!candidates.containsKey(bridgedMethod)) {
                candidates.put(bridgedMethod,
                        new ToolCandidate(declaredMethod, bridgedMethod, annotation));
            }
        }
        collectCandidates(source.getSuperclass(), targetClass, candidates, visited);
        for (Class<?> contract : source.getInterfaces()) {
            collectCandidates(contract, targetClass, candidates, visited);
        }
    }

    /**
     * 拒绝非公共 Tool 声明。扫描器不会通过 setAccessible 绕过 Java 可见性边界。
     *
     * @param method 待验证方法
     */
    private void validatePublicDeclaration(Method method) {
        if (!Modifier.isPublic(method.getModifiers())) {
            throw new IllegalStateException("@AiTool 只允许声明在 public 方法上: "
                    + method.toGenericString());
        }
    }

    /**
     * 注册一个候选 Tool，并分别保留 Schema 方法和代理调用方法。
     *
     * @param bean Spring 暴露的原始对象或代理对象
     * @param targetClass 代理背后的业务类型
     * @param candidate Tool 声明及其最具体业务方法
     */
    private void register(Object bean, Class<?> targetClass, ToolCandidate candidate) {
        Method schemaMethod = candidate.businessMethod;
        Method invocableMethod = resolveInvocableMethod(bean, targetClass, candidate);
        String localName = candidate.annotation.name().isEmpty()
                ? schemaMethod.getName() : candidate.annotation.name();
        if (toolsByName.containsKey(localName)) {
            throw new IllegalStateException("@AiTool 名称重复: " + localName);
        }

        Map<String, Object> schema = schemaGenerator.generate(schemaMethod);
        ToolDefinition definition = new ToolDefinition(
                namespace + "." + localName, localName, candidate.annotation.description(), schema,
                new ToolAnnotations(candidate.annotation.readOnly(),
                        candidate.annotation.destructive(), candidate.annotation.idempotent(),
                        candidate.annotation.requireConfirmation()),
                ToolSource.LOCAL, Arrays.asList(candidate.annotation.permissions()));

        List<ParamBinding> bindings = createBindings(schemaMethod, candidate.declarationMethod);
        Set<String> argumentNames = new LinkedHashSet<String>();
        for (ParamBinding binding : bindings) {
            if (!binding.context) {
                argumentNames.add(binding.name);
            }
        }
        toolsByName.put(localName, new ToolMethod(
                definition, bean, invocableMethod, bindings, argumentNames));
    }

    /**
     * 解析一个可以在实际 Spring Bean 上调用的方法，确保 JDK 代理与桥接方法仍经过宿主切面。
     *
     * @param bean Spring 暴露对象
     * @param targetClass 代理背后的业务类型
     * @param candidate Tool 候选项
     * @return 无需放宽反射可见性即可调用的方法
     */
    private Method resolveInvocableMethod(Object bean, Class<?> targetClass,
                                          ToolCandidate candidate) {
        List<Method> attempts = new ArrayList<Method>();
        attempts.add(candidate.businessMethod);
        attempts.add(candidate.declarationMethod);
        for (Method method : targetClass.getDeclaredMethods()) {
            if (method.isBridge()
                    && BridgeMethodResolver.findBridgedMethod(method)
                    .equals(candidate.businessMethod)) {
                attempts.add(method);
            }
        }
        for (Method attempt : attempts) {
            try {
                Method invocable = AopUtils.selectInvocableMethod(attempt, bean.getClass());
                if (Modifier.isPublic(invocable.getModifiers())
                        && Modifier.isPublic(invocable.getDeclaringClass().getModifiers())) {
                    return invocable;
                }
            } catch (IllegalStateException ignored) {
                // 当前候选签名无法穿过此代理，继续尝试它对应的接口或桥接方法。
            }
        }
        throw new IllegalStateException("@AiTool 方法无法通过 Spring Bean 的公共 API 调用: "
                + candidate.businessMethod.toGenericString());
    }

    /**
     * 从业务方法创建参数绑定表，声明方法仅用于继承场景下补充参数注解。
     *
     * @param businessMethod 最具体业务方法
     * @param declarationMethod 注解最初声明的方法
     * @return 与业务方法参数顺序一致的绑定表
     */
    private List<ParamBinding> createBindings(Method businessMethod, Method declarationMethod) {
        List<ParamBinding> bindings = new ArrayList<ParamBinding>();
        Parameter[] parameters = businessMethod.getParameters();
        Parameter[] declaredParameters = declarationMethod.getParameters();
        for (int index = 0; index < parameters.length; index++) {
            Parameter parameter = parameters[index];
            boolean context = AiRequestContext.class.isAssignableFrom(parameter.getType());
            AiParam annotation = parameter.getAnnotation(AiParam.class);
            if (annotation == null && index < declaredParameters.length) {
                annotation = declaredParameters[index].getAnnotation(AiParam.class);
            }
            String name = context ? null : parameterName(businessMethod, parameter, annotation);
            JavaType javaType = objectMapper.getTypeFactory()
                    .constructType(parameter.getParameterizedType());
            boolean required = annotation != null && annotation.required();
            bindings.add(new ParamBinding(
                    name, javaType, parameter.getType().isPrimitive(), required, context));
        }
        return bindings;
    }

    /**
     * 按显式注解、编译期形参名的顺序解析模型参数名。
     *
     * @param method 参数所属业务方法
     * @param parameter 业务参数
     * @param annotation 当前参数的注解，可为空
     * @return 模型参数名
     */
    private String parameterName(Method method, Parameter parameter, AiParam annotation) {
        if (annotation != null && !annotation.name().isEmpty()) {
            return annotation.name();
        }
        if (parameter.isNamePresent()) {
            return parameter.getName();
        }
        throw new IllegalStateException(String.format(
                "@AiTool 方法 %s#%s 参数名不可解析（请显式声明 @AiParam.name 或开启 -parameters 编译）",
                method.getDeclaringClass().getSimpleName(), method.getName()));
    }

    /**
     * 校验参数对象并按完整泛型类型转换为 Java 形参。
     *
     * @param tool 已注册 Tool
     * @param arguments 模型参数对象
     * @param requestContext 服务端可信上下文
     * @return 可用于反射调用的参数数组
     * @throws ToolExecutionException 参数缺失、多余或类型不合法
     */
    private Object[] bindArguments(ToolMethod tool, Map<String, Object> arguments,
                                   AiRequestContext requestContext) throws ToolExecutionException {
        Set<String> unknownNames = new LinkedHashSet<String>(arguments.keySet());
        unknownNames.removeAll(tool.argumentNames);
        if (!unknownNames.isEmpty()) {
            throw new ToolExecutionException("存在未声明的 Tool 参数: " + unknownNames);
        }

        Object[] args = new Object[tool.bindings.size()];
        for (int index = 0; index < tool.bindings.size(); index++) {
            ParamBinding binding = tool.bindings.get(index);
            if (binding.context) {
                args[index] = requestContext;
                continue;
            }
            boolean present = arguments.containsKey(binding.name);
            Object raw = arguments.get(binding.name);
            if ((!present || raw == null) && binding.required) {
                throw new ToolExecutionException("缺少必填参数: " + binding.name);
            }
            if ((!present || raw == null) && binding.primitive) {
                throw new ToolExecutionException("基本类型参数不能为空: " + binding.name);
            }
            if (!present || raw == null) {
                args[index] = null;
                continue;
            }
            try {
                args[index] = objectMapper.convertValue(raw, binding.javaType);
            } catch (IllegalArgumentException e) {
                log.debug("@AiTool 参数 [{}] 转换失败", binding.name, e);
                throw new ToolExecutionException("参数 [" + binding.name + "] 类型不合法", e);
            }
        }
        return args;
    }

    /**
     * 将业务返回值规范化为统一 Tool 结果。
     *
     * @param result 业务方法返回值
     * @return 统一 Tool 调用结果
     * @throws ToolExecutionException 非标准对象无法安全序列化
     */
    private ToolCallResult toResult(Object result) throws ToolExecutionException {
        if (result == null) {
            return ToolCallResult.ofText("OK");
        }
        if (result instanceof ToolCallResult) {
            return (ToolCallResult) result;
        }
        if (result instanceof String) {
            return ToolCallResult.ofText((String) result);
        }
        try {
            return ToolCallResult.ofText(objectMapper.writeValueAsString(result));
        } catch (Exception e) {
            throw new ToolExecutionException("Tool 返回值无法序列化", e);
        }
    }

    /** Tool 注解声明与其最具体业务方法的启动期解析结果。 */
    private static final class ToolCandidate {

        /** 提供 Tool 与参数元数据的原始声明方法。 */
        private final Method declarationMethod;

        /** 用于 Schema 与强类型参数绑定的最具体非桥接方法。 */
        private final Method businessMethod;

        /** 当前 Tool 的合并注解元数据。 */
        private final AiTool annotation;

        /** 创建不可变候选项。 */
        private ToolCandidate(Method declarationMethod, Method businessMethod,
                              AiTool annotation) {
            this.declarationMethod = declarationMethod;
            this.businessMethod = businessMethod;
            this.annotation = annotation;
        }
    }

    /** 一个业务形参的严格绑定规则。 */
    private static final class ParamBinding {

        /** 模型参数名；可信上下文参数没有名称。 */
        private final String name;

        /** 保留 ParameterizedType 信息的 Jackson 目标类型。 */
        private final JavaType javaType;

        /** 是否为不能接收 null 的 Java 基本类型。 */
        private final boolean primitive;

        /** 是否由 {@link AiParam#required()} 显式声明为必填。 */
        private final boolean required;

        /** 是否为只能由服务端注入的可信请求上下文。 */
        private final boolean context;

        /** 创建不可变参数绑定。 */
        private ParamBinding(String name, JavaType javaType, boolean primitive,
                             boolean required, boolean context) {
            this.name = name;
            this.javaType = javaType;
            this.primitive = primitive;
            this.required = required;
            this.context = context;
        }
    }

    /** 一个完成启动期校验、可直接执行的本地 Tool。 */
    private static final class ToolMethod {

        /** 对浏览器与模型公开的 Tool 定义。 */
        private final ToolDefinition definition;

        /** Spring 暴露的业务 Bean，可能是 AOP 代理。 */
        private final Object bean;

        /** 可在 Bean 公共 API 上调用的方法。 */
        private final Method invocableMethod;

        /** 与方法形参顺序一致的绑定规则。 */
        private final List<ParamBinding> bindings;

        /** 模型允许提供的全部参数名，用于拒绝未知字段。 */
        private final Set<String> argumentNames;

        /** 创建不可变的可执行 Tool。 */
        private ToolMethod(ToolDefinition definition, Object bean, Method invocableMethod,
                           List<ParamBinding> bindings, Set<String> argumentNames) {
            this.definition = definition;
            this.bean = bean;
            this.invocableMethod = invocableMethod;
            this.bindings = bindings;
            this.argumentNames = Collections.unmodifiableSet(
                    new LinkedHashSet<String>(argumentNames));
        }
    }
}
