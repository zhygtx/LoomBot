package com.loom.config;

import com.loom.common.exception.RestAccessDeniedHandler;
import com.loom.common.exception.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security 全局配置。
 *
 * <h2>为什么是无状态（STATELESS）</h2>
 *
 * <p>不创建 HttpSession，认证状态完全由客户端携带的 token 表达。原因有三：
 *
 * <ul>
 *   <li>这是给机器人平台用的后端，客户端不止浏览器（还有 NapCat、脚本、其他服务）
 *   <li>无状态便于横向扩容 —— 多实例时不需要会话粘滞或共享 Session
 *   <li>WebSocket 长连接场景下，Session 与连接的对应关系很别扭
 * </ul>
 *
 * <h2>为什么关掉 CSRF</h2>
 *
 * <p>CSRF 攻击依赖浏览器自动携带 Cookie。我们不用 Cookie 认证（token 放在请求头）， 因此 CSRF 不成立。这是无状态 API
 * 的标准做法，不是「为了省事关掉安全功能」。
 *
 * <h2>当前放行清单</h2>
 *
 * <p>注意这里目前是<b>地基阶段的最小配置</b>，真正的权限判定（{@code @ss.hasPermi(...)}） 要等 auth 模块落地。届时 {@code
 * anyRequest().authenticated()} 会细化到每个接口。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler) {
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) {
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth
                                        // 连通性探针
                                        .requestMatchers("/api/system/ping")
                                        .permitAll()
                                        // 健康检查：供容器探针与监控使用，不能要求认证
                                        .requestMatchers("/actuator/health", "/actuator/health/**")
                                        .permitAll()
                                        // WebSocket 握手：鉴权由连接表里的 token 自行完成，
                                        // 不走 Spring Security 的认证链（见 docs/database.md 的
                                        // ws_connection）
                                        .requestMatchers("/ws/**")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .exceptionHandling(
                        e ->
                                e.authenticationEntryPoint(authenticationEntryPoint)
                                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    /**
     * 密码编码器。
     *
     * <p>用 BCrypt 而非 MD5/SHA —— 后者是快速哈希，GPU 可以每秒尝试数十亿次。 BCrypt 自带盐值且可调计算成本，是密码存储的当前标准。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
