package com.loom.connection;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明本测试方法运行时要持有的权限串。
 *
 * <h2>为什么不用 {@code @WithMockUser}</h2>
 *
 * <p>{@code @WithMockUser} 依赖 {@code WithSecurityContextTestExecutionListener} 把 {@code
 * SecurityContext} 塞进测试线程。那个监听器是在 {@code spring-security-test} 的 {@code META-INF/spring.factories}
 * 里注册的 —— <b>而 Spring Boot 4 只扫描 {@code META-INF/spring/*.imports}，不再加载 {@code
 * spring.factories}</b>。
 *
 * <p>即使手工把监听器加到 {@code @TestExecutionListeners} 上，{@code @WithMockUser} 在这个工程里仍然不生效（实测：13
 * 个用例全部以匿名身份发出、统一收到 401）。
 *
 * <p>这个失败模式极其危险，因为<b>它不报错</b>：看起来像「权限配错了」， 实际是「注解根本没被处理」。所以这里改用不依赖监听器机制的做法 —— 通过 MockMvc
 * 的后处理器显式注入认证，那条路径已被诊断用例证明是通的。
 *
 * <p>用法：把权限串写在方法上，测试基类在每次请求时统一注入。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface WithAuthorities {

    /** 该方法应持有的权限串，例如 {@code connection:ws:list}。 */
    String[] value();
}
