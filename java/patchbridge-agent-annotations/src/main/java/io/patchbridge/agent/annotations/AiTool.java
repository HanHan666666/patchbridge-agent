package io.patchbridge.agent.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明一个 Native Tool：把当前方法暴露为浏览器 Agent 可发现、可调用的业务能力。
 *
 * <p>使用位置：任意应用框架托管的业务对象公共方法。annotations 模块只定义元数据，
 * 不依赖 Spring 或其他容器；对象发现、方法调用与 AOP 适配由各运行时 Adapter 实现。
 *
 * <p>设计要点：
 * <ul>
 *   <li>{@code permissions} 只是权限标识（metadata），框架不解析其语义；
 *       真正的可见性 / 可调用性判断由宿主应用提供的 {@code ToolAccessPolicy} 完成，
 *       以保证 AI Tool Call 与普通 API Call 复用同一套权限体系。</li>
 *   <li>{@code requireConfirmation} 会被透传到浏览器端，触发 Human-in-the-loop 审批 UI；
 *       服务端不强制（浏览器不是安全边界，最终约束仍在服务端权限校验）。</li>
 *   <li>方法签名中的 {@code AiRequestContext} 参数由服务器注入（userId / tenantId / roles 等），
 *       绝不进入模型可见的 inputSchema，防止 LLM 伪造可信上下文。</li>
 * </ul>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AiTool {

    /** Tool 名称（进入 LLM Tool Schema、RBAC、审计链路，注册后应保持稳定）。缺省取方法名。 */
    String name() default "";

    /** 给模型看的自然语言描述，应当说清楚“做什么、什么场景用”。 */
    String description() default "";

    /** 是否只读（不改变业务状态）。影响浏览器端审批策略与审计展示。 */
    boolean readOnly() default false;

    /** 是否具有破坏性（如删除、重启、覆盖）。建议与 requireConfirmation 同时开启。 */
    boolean destructive() default false;

    /** 是否幂等：重复调用同一参数是否产生相同结果。默认不承诺幂等，Tool 调用失败不自动重试。 */
    boolean idempotent() default false;

    /** 调用前是否要求用户在浏览器端确认（Human-in-the-loop）。 */
    boolean requireConfirmation() default false;

    /**
     * 权限标识列表，交给宿主应用的 ToolAccessPolicy 判读。
     * 框架完整透传但不规定 ALL、ANY 或表达式语义。
     */
    String[] permissions() default {};
}
