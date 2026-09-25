package com.loom.common.exception;

import com.loom.common.api.ErrorCode;

/**
 * 业务异常。
 *
 * <p>用于表达<b>可预期的业务失败</b>：用户名已存在、插件已在运行、角色被内置保护不允许删除等。 由 {@link GlobalExceptionHandler} 统一转成响应，日志按
 * WARN 记录且不打堆栈。
 *
 * <p><b>不要用它包装不可预期的异常</b>（空指针、连接超时、序列化失败）—— 那些应该走兜底处理并保留完整堆栈，否则线上排查时你会失去唯一的线索。
 */
public class BusinessException extends RuntimeException {

    private final transient ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.message());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
