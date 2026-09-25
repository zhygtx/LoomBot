package com.loom.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.loom.common.api.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("业务异常")
class BusinessExceptionTest {

    @Test
    @DisplayName("单参构造应使用错误码自带提示")
    void shouldUseErrorCodeMessage() {
        BusinessException ex = new BusinessException(ErrorCode.PLUGIN_ALREADY_RUNNING);

        assertThat(ex.errorCode()).isEqualTo(ErrorCode.PLUGIN_ALREADY_RUNNING);
        assertThat(ex.getMessage()).isEqualTo("插件已在运行");
    }

    @Test
    @DisplayName("应允许覆盖提示文案")
    void shouldAllowCustomMessage() {
        BusinessException ex = new BusinessException(ErrorCode.USERNAME_EXISTS, "用户名 admin 已被占用");

        assertThat(ex.errorCode()).isEqualTo(ErrorCode.USERNAME_EXISTS);
        assertThat(ex.getMessage()).isEqualTo("用户名 admin 已被占用");
    }

    @Test
    @DisplayName("应保留原始异常作为 cause，便于排查底层原因")
    void shouldKeepCause() {
        IllegalStateException cause = new IllegalStateException("底层原因");
        BusinessException ex = new BusinessException(ErrorCode.PLUGIN_START_FAILED, "启动失败", cause);

        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.PLUGIN_START_FAILED);
    }
}
