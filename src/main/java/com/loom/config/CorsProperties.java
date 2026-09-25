package com.loom.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 跨域来源白名单。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>前端开发服务器在 {@code http://localhost:5173}，后端在 {@code http://localhost:8080}， 端口不同即不同源 ——
 * 浏览器会先发预检请求，后端不回 CORS 响应头，登录请求根本发不出去。 这和「用不用 Spring Security」无关，但配置要挂到安全过滤器链上才生效 （见 {@code
 * SecurityConfig}）。
 *
 * <h2>为什么默认值是这两个，而不是 {@code *}</h2>
 *
 * <p>{@code allowedOrigins("*")} 在本项目里风险不高（令牌在请求头而不是 Cookie，浏览器
 * 不会自动携带），但它会让「谁都能从任意网页调我的接口」变成默认状态。默认值应当等于 「本机开发能跑通的最小集合」，要放开是部署时的显式动作。
 *
 * @param allowedOrigins 允许的来源。生产部署时用环境变量或配置文件覆盖。
 */
@ConfigurationProperties(prefix = "loom.cors")
public record CorsProperties(
        @DefaultValue({"http://localhost:5173", "http://127.0.0.1:5173"})
                List<String> allowedOrigins) {}
