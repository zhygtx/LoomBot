package com.loom.common.exception;

import com.loom.common.api.ErrorCode;
import com.loom.common.api.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 未认证时的统一出口（HTTP 401）。
 *
 * <p>不配这个的话，Spring Security 默认会返回一个 HTML 登录页或空响应体 —— 前端拿到一坨 HTML 解析失败，报错信息毫无意义。
 *
 * <p>注意：这里运行在 Spring Security 过滤器链中，<b>不经过 DispatcherServlet</b>， 所以 {@link GlobalExceptionHandler}
 * 拦不到，必须单独实现。
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException)
            throws IOException {

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), Result.failure(ErrorCode.UNAUTHORIZED));
    }
}
