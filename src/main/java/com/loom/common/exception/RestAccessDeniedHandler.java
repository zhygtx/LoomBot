package com.loom.common.exception;

import com.loom.common.api.ErrorCode;
import com.loom.common.api.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 已认证但无权限时的统一出口（HTTP 403）。
 *
 * <p>与 401 的区别：401 是「你是谁我不知道」，403 是「我知道你是谁，但你不能做这个」。 前端据此决定是跳登录页还是弹「无权限」提示 ——
 * 混用会导致用户在已登录状态下被反复踢回登录页。
 *
 * <p>同 {@link RestAuthenticationEntryPoint}，运行在过滤器链中，不走 {@code @RestControllerAdvice}。
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException)
            throws IOException {

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), Result.failure(ErrorCode.FORBIDDEN));
    }
}
