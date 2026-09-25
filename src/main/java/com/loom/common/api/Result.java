package com.loom.common.api;

import com.loom.common.trace.TraceContext;

/**
 * 统一响应结构。
 *
 * <p>所有 HTTP 接口都返回这个形状，前端只需处理一种结构，不用为每个接口写解析分支。
 *
 * <ul>
 *   <li>{@code code} —— 业务错误码，0 表示成功，见 {@link ErrorCode}
 *   <li>{@code message} —— 可直接展示给用户的提示
 *   <li>{@code data} —— 业务数据，失败时为 {@code null}
 *   <li>{@code traceId} —— 本次请求的追踪 ID；用户报错时凭它直接定位日志，不用问「你几点操作的」
 *   <li>{@code timestamp} —— 服务端时间戳（毫秒），排查客户端与服务端时钟偏差时有用
 * </ul>
 *
 * <p>刻意不提供 {@code isSuccess()} 这类方法：以 {@code is} 开头的无参方法会被 Jackson 识别成属性序列化出去，与 {@code code}
 * 表达重复信息。需要判断成功与否用 {@link #ok()}。
 */
public record Result<T>(int code, String message, T data, String traceId, long timestamp) {

    public static <T> Result<T> success(T data) {
        return new Result<>(
                ErrorCode.SUCCESS.code(),
                ErrorCode.SUCCESS.message(),
                data,
                TraceContext.currentTraceId(),
                System.currentTimeMillis());
    }

    public static Result<Void> success() {
        return new Result<>(
                ErrorCode.SUCCESS.code(),
                ErrorCode.SUCCESS.message(),
                null,
                TraceContext.currentTraceId(),
                System.currentTimeMillis());
    }

    public static <T> Result<T> failure(ErrorCode errorCode) {
        return failure(errorCode, errorCode.message());
    }

    public static <T> Result<T> failure(ErrorCode errorCode, String message) {
        return new Result<>(
                errorCode.code(),
                message,
                null,
                TraceContext.currentTraceId(),
                System.currentTimeMillis());
    }

    /** 是否成功。命名不用 {@code isXxx} / {@code getXxx}，避免被 Jackson 当作属性输出。 */
    public boolean ok() {
        return code == ErrorCode.SUCCESS.code();
    }
}
