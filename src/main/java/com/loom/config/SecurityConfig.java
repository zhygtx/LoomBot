package com.loom.config;

import com.loom.auth.security.JwtAuthenticationFilter;
import com.loom.common.exception.RestAccessDeniedHandler;
import com.loom.common.exception.RestAuthenticationEntryPoint;
import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

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
 * <h2>放行清单的判据</h2>
 *
 * <p>只有一个判据：<b>这个端点在调用时，调用者是否可能还没有令牌</b>。登录、注册、 发验证码、重置密码 —— 用户此刻必然没有令牌，这是它们存在的意义。除此之外一律 {@code
 * authenticated()}，包括「登出」和「我是谁」。清单越短越好，每多一条都要能回答这个问题。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CorsProperties corsProperties;

    public SecurityConfig(
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            CorsProperties corsProperties) {
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.corsProperties = corsProperties;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) {
        http.csrf(AbstractHttpConfigurer::disable)
                // CORS 必须挂在安全链上。只注册一个 CorsFilter bean 也能工作，
                // 但那样预检请求会先撞上认证规则 —— 预检请求不带 Authorization 头，
                // 于是浏览器看到 401，控制台报的是「CORS 错误」，与真实原因差了十万八千里。
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth
                                        // 认证入口：调用它们时用户必然还没有令牌
                                        .requestMatchers(
                                                "/api/auth/login",
                                                "/api/auth/register",
                                                "/api/auth/email-code",
                                                "/api/auth/password/reset")
                                        .permitAll()
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
                                        .accessDeniedHandler(accessDeniedHandler))
                // 放在用户名密码过滤器之前：我们是无状态的，那个过滤器在本工程里永远是空跑，
                // 但位置决定了「认证在授权之前完成」这件事的顺序
                .addFilterBefore(
                        jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 阻止 {@link JwtAuthenticationFilter} 被 Servlet 容器**再注册一次**。
     *
     * <p>它是 {@code @Component}，Boot 会把所有 {@code Filter} bean 自动注册到 Servlet 容器上， 于是它会执行两遍：一遍在 Spring
     * Security 过滤器链里（我们想要的位置），一遍在所有请求上 （任何路径、包括静态资源）。第二遍的后果不只是浪费 —— 它会在 SecurityContext 被清理之后
     * 又塞回一个认证，出现「同一个请求，两处看到的身份不一样」。
     *
     * <p>这个坑的隐蔽之处在于：功能测试全过，只有并发或审计日志里才看得出异常。 传统写法是给过滤器去掉 {@code @Component} 再手工 new，但那样它就不能注入依赖了。
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * 跨域配置。
     *
     * <p>{@code allowCredentials} 保持 {@code false}：认证靠 {@code Authorization} 请求头， 不依赖 Cookie。设为
     * {@code true} 只会在「来源白名单 + 凭据」的组合上引入一类 需要额外小心的场景，而我们不需要它。
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.allowedOrigins());
        configuration.setAllowedMethods(
                List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(false);
        // 预检结果缓存 1 小时：否则每个跨域请求前都要多一次往返，
        // 在「登录 → 立刻拉用户信息 → 再拉权限」这种连击下体感很明显
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
