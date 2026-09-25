package com.loom.common.trace;

import org.slf4j.MDC;

/**
 * TraceId 上下文。
 *
 * <p>基于 SLF4J 的 {@link MDC}，把 TraceId 绑定到当前线程；日志 pattern 里用 {@code %X{traceId}}
 * 输出，于是同一次请求产生的所有日志自动带上同一个 ID。
 *
 * <p><b>为什么这个项目特别需要它</b>：工作流引擎、WebSocket 连接、Python 插件子进程 —— 一次用户操作产生的日志会散落在多个线程甚至多个进程里。没有 TraceId
 * 根本拼不出因果链。
 *
 * <p><b>重要限制</b>：MDC 底层是 ThreadLocal，<b>不会自动传递到异步线程 / 线程池</b>。 提交异步任务时必须显式包装，用 {@link
 * #wrap(Runnable)}：
 *
 * <pre>{@code
 * executor.submit(TraceContext.wrap(() -> doSomething()));
 * }</pre>
 *
 * <p>接入 {@code @Async}、工作流线程池、插件进程通信时都要注意这一点。
 */
public final class TraceContext {

    /** MDC 中的 key，需与 logback-spring.xml 里的 %X{traceId} 保持一致。 */
    public static final String TRACE_ID_KEY = "traceId";

    private TraceContext() {}

    public static void set(String traceId) {
        MDC.put(TRACE_ID_KEY, traceId);
    }

    /** 当前线程的 TraceId；不在请求线程中（如定时任务）时可能为 {@code null}。 */
    public static String currentTraceId() {
        return MDC.get(TRACE_ID_KEY);
    }

    public static void clear() {
        MDC.remove(TRACE_ID_KEY);
    }

    /**
     * 把当前 TraceId 捕获进 Runnable，供线程池提交时使用。
     *
     * <p>注意是「捕获当前值」而非「执行时再取」—— 任务真正跑起来时已经换了线程，取不到原值。
     */
    public static Runnable wrap(Runnable task) {
        String traceId = currentTraceId();
        return () -> {
            try {
                if (traceId != null) {
                    set(traceId);
                }
                task.run();
            } finally {
                clear();
            }
        };
    }
}
