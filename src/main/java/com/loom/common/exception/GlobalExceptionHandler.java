package com.loom.common.exception;

import com.loom.common.api.ErrorCode;
import com.loom.common.api.Result;
import jakarta.validation.ConstraintViolationException;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理。
 *
 * <p>把散落在各处的 try-catch 收敛到一处，保证<b>任何</b>异常都返回 {@link Result} 结构， 前端不会拿到 Spring 默认的 HTML 错误页或裸堆栈。
 *
 * <h2>HTTP 状态码的取舍</h2>
 *
 * <p>刻意采用<b>混合策略</b>，而不是「一律 200」或「一律对应状态码」：
 *
 * <ul>
 *   <li><b>业务异常 → HTTP 200</b>。请求被正常处理了，只是业务规则拒绝（邮箱已存在、 插件已在运行）。这类「预期内的失败」如果返回 4xx，会被 APM /
 *       网关计入错误率， 导致告警在正常业务流上乱响。
 *   <li><b>协议层错误 → 对应的 4xx / 5xx</b>。参数格式错误、方法不支持、未认证、无权限、 未捕获异常 ——
 *       这些确实是协议层问题，需要被基础设施（负载均衡、网关、监控）识别出来。
 * </ul>
 *
 * <p>想要改成「一律 200」，只需把下面各方法里的 {@code respond(HttpStatus.XXX, ...)} 统一换成 {@code ok(...)} 即可。
 *
 * <h2>日志分级</h2>
 *
 * <ul>
 *   <li>业务异常 → WARN，不打堆栈（堆栈对可预期失败没有信息量，只会淹没日志）
 *   <li>参数校验失败 → WARN，带上具体字段
 *   <li>未捕获异常 → ERROR，<b>打完整堆栈</b>（这是唯一的线索，绝不能省）
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ---------- 业务异常 ----------

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException ex) {
        log.warn("业务异常: code={}, message={}", ex.errorCode().code(), ex.getMessage());
        return ok(Result.failure(ex.errorCode(), ex.getMessage()));
    }

    // ---------- 参数校验 ----------

    /** {@code @RequestBody @Valid} 校验失败。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex) {
        String detail = collectFieldErrors(ex);
        log.warn("请求体校验失败: {}", detail);
        return badRequest(detail);
    }

    /** 表单 / 查询参数绑定校验失败。 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBind(BindException ex) {
        String detail = collectFieldErrors(ex);
        log.warn("参数绑定校验失败: {}", detail);
        return badRequest(detail);
    }

    /** 方法参数上的 {@code @Validated} 约束失败（如 {@code @PathVariable @Min(1)}）。 */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Result<Void>> handleHandlerMethodValidation(
            HandlerMethodValidationException ex) {
        log.warn("方法参数校验失败: {}", ex.getMessage());
        return badRequest(ErrorCode.BAD_REQUEST.message());
    }

    /** Service 层 {@code @Validated} 触发的约束失败。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        String detail =
                ex.getConstraintViolations().stream()
                        .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                        .collect(Collectors.joining("; "));
        log.warn("约束校验失败: {}", detail);
        return badRequest(detail);
    }

    /** 缺少必填的请求参数。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParam(
            MissingServletRequestParameterException ex) {
        String detail = "缺少必填参数: " + ex.getParameterName();
        log.warn(detail);
        return badRequest(detail);
    }

    /** 请求体不是合法 JSON，或类型无法转换。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleNotReadable(HttpMessageNotReadableException ex) {
        log.warn("请求体解析失败: {}", ex.getMessage());
        return badRequest(ErrorCode.REQUEST_BODY_UNREADABLE.message());
    }

    // ---------- 协议层错误 ----------

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex) {
        log.warn("请求方法不支持: {}", ex.getMessage());
        return respond(HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex) {
        log.warn("媒体类型不支持: {}", ex.getMessage());
        return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    /** 静态资源 / 路径不存在。不处理的话会被下面的兜底捕获成 500，语义就错了。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResource(NoResourceFoundException ex) {
        log.warn("资源不存在: {}", ex.getResourcePath());
        return respond(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND);
    }

    /**
     * 已认证但无权限。
     *
     * <p>正常情况下由 Spring Security 的 AccessDeniedHandler 处理（走不到这里）， 但方法级 {@code @PreAuthorize}
     * 抛出的异常会经过 DispatcherServlet，因此仍需兜住。
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("权限不足: {}", ex.getMessage());
        return respond(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN);
    }

    // ---------- 兜底 ----------

    /**
     * 未捕获异常。
     *
     * <p><b>必须打完整堆栈</b> —— 这是排查线上问题的唯一线索。同时对外只返回通用提示， 不泄漏内部实现细节（表名、类名、SQL 片段都可能被攻击者利用）。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(Exception ex) {
        log.error("未捕获异常", ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR);
    }

    // ---------- 内部工具 ----------

    private static String collectFieldErrors(BindException ex) {
        String detail =
                ex.getBindingResult().getFieldErrors().stream()
                        .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining("; "));
        return detail.isEmpty() ? ErrorCode.BAD_REQUEST.message() : detail;
    }

    /** 业务异常：HTTP 200，错误信息放 body。 */
    private static ResponseEntity<Result<Void>> ok(Result<Void> body) {
        return ResponseEntity.ok(body);
    }

    private static ResponseEntity<Result<Void>> badRequest(String message) {
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, message);
    }

    private static ResponseEntity<Result<Void>> respond(HttpStatus status, ErrorCode errorCode) {
        return respond(status, errorCode, errorCode.message());
    }

    private static ResponseEntity<Result<Void>> respond(
            HttpStatus status, ErrorCode errorCode, String message) {
        return ResponseEntity.status(status).body(Result.failure(errorCode, message));
    }
}
