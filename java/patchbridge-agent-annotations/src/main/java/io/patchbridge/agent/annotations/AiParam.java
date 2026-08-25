package io.patchbridge.agent.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 描述 @AiTool 方法的某个业务参数，用于生成模型可见的 inputSchema。
 *
 * <p>注意：参数名的解析顺序为 {@code name()} 显式指定 → 编译期保留的形参名
 * （需 -parameters 编译，本框架父 POM 已开启；宿主项目若未开启则必须显式写 name）。
 * 解析不到参数名时启动直接失败，避免生成错误的 Schema 让模型传错参数。
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface AiParam {

    /** 参数的业务含义描述，给模型看。 */
    String value() default "";

    /** 参数名。缺省时按编译期形参名解析。 */
    String name() default "";

    /** 是否必填。只在此显式声明时才进入 required 列表，框架不做隐式推断。 */
    boolean required() default false;
}
