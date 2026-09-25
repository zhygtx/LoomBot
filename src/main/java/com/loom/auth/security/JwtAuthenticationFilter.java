package com.loom.auth.security;

import com.loom.auth.service.TokenService;
import com.loom.auth.service.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 从 {@code Authorization: Bearer <jwt>} 恢复认证上下文。
 *
 * <h2>为什么失败时不直接返回 401</h2>
 *
 * <p>本过滤器**不负责拒绝请求**，只负责「能认出来就认」。原因是一个令牌可能同时出现在 公开端点上：登录接口带着一个过期的旧令牌、注册接口带着别人发的链接里的令牌 —— 都很常见。
 * 在这些端点上因为令牌坏了就返回 401，会让「令牌过期」表现为「登录接口也打不开了」。
 *
 * <p>真正的拒绝由 {@code authorizeHttpRequests} 的规则 + {@code AuthenticationEntryPoint} 完成：需要认证的端点拿不到认证 →
 * 统一 401。这样「哪些端点需要登录」只有一处定义， 而不是在这个过滤器里再抄一份清单 —— 抄的那份迟早会漏。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final TokenService tokenService;
    private final UserService userService;

    public JwtAuthenticationFilter(TokenService tokenService, UserService userService) {
        this.tokenService = tokenService;
        this.userService = userService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String token = BearerTokens.resolve(request);
        // 已经有认证就跳过：SecurityContext 在一次请求内可能被更早的机制填过，
        // 覆盖它属于「悄悄换了调用者身份」这类最难查的问题
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            tokenService
                    .authenticate(token)
                    .ifPresent(
                            user -> {
                                UsernamePasswordAuthenticationToken authentication =
                                        new UsernamePasswordAuthenticationToken(
                                                user, null, userService.authorities(user.id()));
                                authentication.setDetails(
                                        new WebAuthenticationDetailsSource().buildDetails(request));
                                SecurityContextHolder.getContext()
                                        .setAuthentication(authentication);
                            });
        }
        filterChain.doFilter(request, response);
    }
}
