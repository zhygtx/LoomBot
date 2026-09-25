package com.loom.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.loom.common.trace.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("统一响应结构 Result")
class ResultTest {

    @AfterEach
    void clearTraceContext() {
        TraceContext.clear();
    }

    @Test
    @DisplayName("success 应携带数据、code 为 0、ok() 为 true")
    void successShouldCarryData() {
        Result<String> result = Result.success("hello");

        assertThat(result.code()).isZero();
        assertThat(result.message()).isEqualTo("成功");
        assertThat(result.data()).isEqualTo("hello");
        assertThat(result.ok()).isTrue();
        assertThat(result.timestamp()).isPositive();
    }

    @Test
    @DisplayName("success 无参时 data 为 null")
    void successWithoutData() {
        Result<Void> result = Result.success();

        assertThat(result.code()).isZero();
        assertThat(result.data()).isNull();
        assertThat(result.ok()).isTrue();
    }

    @Test
    @DisplayName("failure 应使用错误码自带的提示")
    void failureShouldUseErrorCodeMessage() {
        Result<Void> result = Result.failure(ErrorCode.USER_NOT_FOUND);

        assertThat(result.code()).isEqualTo(ErrorCode.USER_NOT_FOUND.code());
        assertThat(result.message()).isEqualTo("用户不存在");
        assertThat(result.data()).isNull();
        assertThat(result.ok()).isFalse();
    }

    @Test
    @DisplayName("failure 应允许覆盖提示文案，但保留错误码")
    void failureShouldAllowCustomMessage() {
        Result<Void> result = Result.failure(ErrorCode.USER_NOT_FOUND, "ID 为 42 的用户不存在");

        assertThat(result.code()).isEqualTo(ErrorCode.USER_NOT_FOUND.code());
        assertThat(result.message()).isEqualTo("ID 为 42 的用户不存在");
    }

    @Test
    @DisplayName("应从 MDC 带上当前 TraceId —— 用户报错时凭它定位日志")
    void shouldCarryTraceIdFromMdc() {
        TraceContext.set("abc12345");

        assertThat(Result.success().traceId()).isEqualTo("abc12345");
        assertThat(Result.failure(ErrorCode.INTERNAL_ERROR).traceId()).isEqualTo("abc12345");
    }

    @Test
    @DisplayName("不在请求线程中时 TraceId 为 null，不应抛异常")
    void shouldTolerateMissingTraceId() {
        assertThat(Result.success().traceId()).isNull();
    }
}
