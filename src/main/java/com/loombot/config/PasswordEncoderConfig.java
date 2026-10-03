package com.loombot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码编码器。
 *
 * <h2>为什么单独一个类，而不是放在 {@code SecurityConfig} 里</h2>
 *
 * <p>因为它会造成一个**循环依赖**，而且是那种「看起来完全合理」的循环：
 *
 * <pre>
 * SecurityConfig ──需要──▶ JwtAuthenticationFilter
 *        ▲                        │
 *        │                        ▼
 *        └──PasswordEncoder── UserService
 * </pre>
 *
 * <p>{@code SecurityConfig} 要把过滤器挂进过滤器链，过滤器要读用户权限，读权限要用 {@code PasswordEncoder}（登录校验密码）—— 而它原本恰好定义在
 * {@code SecurityConfig} 里。 结果是：要造 SecurityConfig，得先造过滤器；要造过滤器，得先造 SecurityConfig。 Spring
 * 会直接拒绝启动（Boot 2.6 起循环引用默认禁止，不再自动兜底）。
 *
 * <p>把编码器挪出来是**结构性**的修法：编码器与过滤器链本来就不是一件事， 只是碰巧都跟 Spring Security 有关。相比之下 {@code
 * spring.main.allow-circular-references=true} 只是让启动通过 —— 循环还在，只是被藏起来了。
 *
 * <h2>为什么用 BCrypt</h2>
 *
 * <p>MD5 / SHA 是快速哈希，GPU 每秒能试数十亿次。BCrypt 自带盐值且计算成本可调， 是密码存储的当前标准。代价是单次校验约几十毫秒 —— 这正是它抗爆破的原因，
 * 也顺带解释了为什么登录接口不能拿它做批量化操作。
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
