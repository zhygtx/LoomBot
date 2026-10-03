package com.loombot.common.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 为每个 HTTP 请求生成（或透传）TraceId，写入 MDC 与响应头。
 *
 * <p>行为：
 *
 * <ol>
 *   <li>请求头带 {@code X-Trace-Id} 且格式合法 → 沿用（支持上游网关 / 前端传入，实现全链路串联）
 *   <li>否则生成一个新的 32 位无连字符 UUID
 *   <li>写回响应头，便于前端把 TraceId 展示在错误提示里
 *   <li>请求结束后清理 MDC，防止线程复用导致 TraceId 串味
 * </ol>
 *
 * <p>顺序设为 {@link Ordered#HIGHEST_PRECEDENCE}，确保它排在 Spring Security 过滤器链之前 —— 否则认证失败（401）的日志会没有
 * TraceId，而那恰恰是最需要排查的场景。
 *
 * <p>对传入的 TraceId 做格式校验，是为了防止日志注入：直接把用户输入写进日志， 攻击者可以塞换行符伪造日志行。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /** 只接受字母、数字、连字符，长度 8–64。其余一律重新生成。 */
    private static final Pattern VALID_TRACE_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String traceId = resolveTraceId(request);
        TraceContext.set(traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            TraceContext.clear();
        }
    }

    private String resolveTraceId(HttpServletRequest request) {
        String incoming = request.getHeader(TRACE_ID_HEADER);
        if (incoming != null && VALID_TRACE_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
