package com.loom.auth.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 从请求头里取出 Bearer 令牌。
 *
 * <h2>为什么单独抽一个类</h2>
 *
 * <p>两处需要它：认证过滤器（每个请求）和登出接口（要拿到原始令牌才能吊销对应的 jti）。 复制粘贴的后果是两处对大小写、对多余空格的处理不一致 —— 而这种不一致只在 「前端换了个 HTTP
 * 库」之后才暴露，届时没人会想到是这里的问题。
 */
public final class BearerTokens {

    private static final String PREFIX = "Bearer ";

    private BearerTokens() {}

    /**
     * 取出令牌；请求头缺失或格式不对时返回 {@code null}。
     *
     * <p>前缀比较**忽略大小写**：RFC 6750 规定 scheme 是大小写不敏感的， 而各种客户端库在这件事上并不统一（{@code bearer} / {@code
     * Bearer} 都见过）。
     */
    public static String resolve(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || header.length() <= PREFIX.length()) {
            return null;
        }
        if (!header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return null;
        }
        String token = header.substring(PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
