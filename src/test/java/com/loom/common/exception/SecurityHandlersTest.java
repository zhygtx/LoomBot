package com.loom.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.loom.common.api.ErrorCode;
import com.loom.common.trace.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import tools.jackson.databind.ObjectMapper;

/**
 * 401 / 403 统一出口。
 *
 * <p>这两个类运行在 Spring Security 过滤器链中，不走 {@code @RestControllerAdvice}， 所以必须单独验证 —— 否则很容易出现「Controller
 * 的异常格式对了，但认证失败仍返回 HTML」。
 */
@DisplayName("安全异常统一出口")
class SecurityHandlersTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clear() {
        TraceContext.clear();
    }

    @Test
    @DisplayName("未认证应返回 401 且响应体为统一 JSON 结构")
    void entryPointShouldReturn401WithJsonBody() throws Exception {
        RestAuthenticationEntryPoint entryPoint = new RestAuthenticationEntryPoint(objectMapper);
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(
                new MockHttpServletRequest(),
                response,
                new InsufficientAuthenticationException("未登录"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).contains("application/json");
        assertThat(response.getContentAsString())
                .contains("\"code\":" + ErrorCode.UNAUTHORIZED.code());
    }

    @Test
    @DisplayName("无权限应返回 403 且响应体为统一 JSON 结构")
    void deniedHandlerShouldReturn403WithJsonBody() throws Exception {
        RestAccessDeniedHandler deniedHandler = new RestAccessDeniedHandler(objectMapper);
        MockHttpServletResponse response = new MockHttpServletResponse();

        deniedHandler.handle(
                new MockHttpServletRequest(), response, new AccessDeniedException("无权限"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).contains("application/json");
        assertThat(response.getContentAsString())
                .contains("\"code\":" + ErrorCode.FORBIDDEN.code());
    }

    @Test
    @DisplayName("401 与 403 必须可区分 —— 混用会让已登录用户被反复踢回登录页")
    void unauthorizedAndForbiddenMustBeDistinguishable() throws Exception {
        RestAuthenticationEntryPoint entryPoint = new RestAuthenticationEntryPoint(objectMapper);
        RestAccessDeniedHandler deniedHandler = new RestAccessDeniedHandler(objectMapper);

        MockHttpServletResponse unauthorized = new MockHttpServletResponse();
        entryPoint.commence(
                new MockHttpServletRequest(),
                unauthorized,
                new InsufficientAuthenticationException("x"));

        MockHttpServletResponse forbidden = new MockHttpServletResponse();
        deniedHandler.handle(
                new MockHttpServletRequest(), forbidden, new AccessDeniedException("x"));

        assertThat(unauthorized.getStatus()).isNotEqualTo(forbidden.getStatus());
        assertThat(unauthorized.getContentAsString()).isNotEqualTo(forbidden.getContentAsString());
    }

    @Test
    @DisplayName("响应体应带上当前 TraceId，便于用户报错时定位日志")
    void shouldCarryTraceId() throws Exception {
        TraceContext.set("trace-abc123");
        RestAuthenticationEntryPoint entryPoint = new RestAuthenticationEntryPoint(objectMapper);
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(
                new MockHttpServletRequest(),
                response,
                new InsufficientAuthenticationException("x"));

        assertThat(response.getContentAsString()).contains("trace-abc123");
    }
}
