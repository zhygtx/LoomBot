package com.loom.common.trace;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("TraceId 过滤器")
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void clear() {
        TraceContext.clear();
    }

    @Test
    @DisplayName("请求未带 TraceId 时应生成 32 位无连字符 ID，并写回响应头")
    void shouldGenerateTraceIdWhenAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> seenInChain.set(TraceContext.currentTraceId());

        filter.doFilter(request, response, chain);

        assertThat(seenInChain.get()).isNotBlank().hasSize(32);
        assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER)).isEqualTo(seenInChain.get());
    }

    @Test
    @DisplayName("请求带合法 TraceId 时应沿用，实现全链路串联")
    void shouldReuseValidIncomingTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "abc12345");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> seenInChain.set(TraceContext.currentTraceId());

        filter.doFilter(request, response, chain);

        assertThat(seenInChain.get()).isEqualTo("abc12345");
    }

    @Test
    @DisplayName("非法 TraceId 应被丢弃并重新生成 —— 防止日志注入")
    void shouldRejectIllegalIncomingTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "bad id\n伪造日志行");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> seenInChain.set(TraceContext.currentTraceId());

        filter.doFilter(request, response, chain);

        assertThat(seenInChain.get()).doesNotContain("伪造").hasSize(32);
    }

    @Test
    @DisplayName("过短的 TraceId 也应被拒绝")
    void shouldRejectTooShortTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "abc");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> seenInChain.set(TraceContext.currentTraceId());

        filter.doFilter(request, response, chain);

        assertThat(seenInChain.get()).hasSize(32);
    }

    @Test
    @DisplayName("请求结束后应清理 MDC —— 否则 Tomcat 复用线程时会串味")
    void shouldClearMdcAfterRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {});

        assertThat(TraceContext.currentTraceId()).isNull();
    }

    @Test
    @DisplayName("业务抛异常时也必须清理 MDC")
    void shouldClearMdcEvenOnException() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain =
                (req, res) -> {
                    throw new IllegalStateException("模拟业务异常");
                };

        try {
            filter.doFilter(request, response, chain);
        } catch (Exception ignored) {
            // 预期抛出，重点验证 finally 是否生效
        }

        assertThat(TraceContext.currentTraceId()).isNull();
    }
}
