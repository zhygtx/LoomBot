package com.loombot.config;

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
 * <h2>为什么默认值是这几个，而不是 {@code *}</h2>
 *
 * <p>{@code allowedOrigins("*")} 在本项目里风险不高（令牌在请求头而不是 Cookie，浏览器
 * 不会自动携带），但它会让「谁都能从任意网页调我的接口」变成默认状态。默认值应当等于 「本机开发能跑通的最小集合」，要放开是部署时的显式动作。
 *
 * <h2>手机连局域网调试时最常见的坑</h2>
 *
 * <p>来源不在白名单里时，Spring 的 {@code CorsFilter} 会返回 <b>403 且响应体是 {@code "Invalid CORS
 * request"}</b>。前端把它当成普通 403，显示成「没有操作权限」—— <b>看起来像权限问题，实际是跨域</b>。排查时先看响应体里有没有这句话。
 *
 * <p>另外要注意：手机访问 {@code http://<本机IP>:5173} 时，{@code /api} 请求由 Vite 代理转发， 浏览器视角只有一个源、不会触发
 * CORS。所以「手机能开页面但登录 403」通常意味着请求走了 绝对地址（比如 {@code VITE_API_BASE_URL} 指到了 8080），那种情况下才必须在这里列出来源。
 *
 * @param allowedOrigins 允许的来源。本机 IP 会随网络变化；生产部署时用环境变量覆盖。
 */
@ConfigurationProperties(prefix = "loombot.cors")
public record CorsProperties(
        @DefaultValue({
                    "http://localhost:5173",
                    "http://127.0.0.1:5173",
                    "http://10.54.78.167:5173"
                })
                List<String> allowedOrigins) {}
