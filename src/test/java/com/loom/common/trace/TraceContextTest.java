package com.loom.common.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TraceId 上下文")
class TraceContextTest {

    @AfterEach
    void clear() {
        TraceContext.clear();
    }

    @Test
    @DisplayName("set / currentTraceId / clear 应正确配合")
    void shouldSetReadAndClear() {
        assertThat(TraceContext.currentTraceId()).isNull();

        TraceContext.set("trace-1234");
        assertThat(TraceContext.currentTraceId()).isEqualTo("trace-1234");

        TraceContext.clear();
        assertThat(TraceContext.currentTraceId()).isNull();
    }

    @Test
    @DisplayName("wrap 应把 TraceId 传递到另一个线程 —— MDC 是 ThreadLocal，不包装会丢")
    void wrapShouldPropagateTraceIdToAnotherThread() throws InterruptedException {
        TraceContext.set("trace-5678");
        AtomicReference<String> seenInOtherThread = new AtomicReference<>();

        Thread worker =
                new Thread(
                        TraceContext.wrap(
                                () -> seenInOtherThread.set(TraceContext.currentTraceId())));
        worker.start();
        worker.join();

        assertThat(seenInOtherThread.get()).isEqualTo("trace-5678");
    }

    @Test
    @DisplayName("wrap 执行完应清理线程的 MDC，避免线程池复用时串味")
    void wrapShouldClearAfterRun() throws InterruptedException {
        TraceContext.set("trace-9999");
        AtomicReference<String> afterRun = new AtomicReference<>();

        Thread worker =
                new Thread(
                        TraceContext.wrap(
                                () -> {
                                    // 任务体内应能看到 TraceId
                                    assertThat(TraceContext.currentTraceId())
                                            .isEqualTo("trace-9999");
                                }));
        worker.start();
        worker.join();

        // 主线程的 TraceId 不受影响（wrap 只清理执行任务的线程）
        assertThat(TraceContext.currentTraceId()).isEqualTo("trace-9999");
        assertThat(afterRun.get()).isNull();
    }

    @Test
    @DisplayName("无 TraceId 时 wrap 也应正常执行，不应抛异常")
    void wrapShouldWorkWithoutTraceId() {
        TraceContext.clear();

        TraceContext.wrap(() -> {}).run();
    }
}
