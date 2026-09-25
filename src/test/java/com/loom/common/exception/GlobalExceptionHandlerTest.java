package com.loom.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.loom.common.api.ErrorCode;
import com.loom.common.api.Result;
import jakarta.validation.ConstraintViolationException;
import java.lang.reflect.Method;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;

@DisplayName("全局异常处理")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    // ------------------------------------------------------------------
    // 业务异常 → HTTP 200
    // ------------------------------------------------------------------

    @Test
    @DisplayName("业务异常应返回 HTTP 200，业务码放在 body —— 避免污染监控错误率")
    void businessExceptionShouldReturnOkWithBusinessCode() {
        ResponseEntity<Result<Void>> response =
                handler.handleBusiness(new BusinessException(ErrorCode.USER_NOT_FOUND));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.USER_NOT_FOUND.code());
        assertThat(response.getBody().message()).isEqualTo("用户不存在");
        assertThat(response.getBody().ok()).isFalse();
    }

    // ------------------------------------------------------------------
    // 参数校验 → 400
    // ------------------------------------------------------------------

    @Test
    @DisplayName("请求体校验失败应返回 400，并带上具体字段名")
    void methodArgumentNotValidShouldReturnBadRequestWithField() throws Exception {
        BeanPropertyBindingResult bindingResult =
                new BeanPropertyBindingResult(new SampleForm(), "sampleForm");
        bindingResult.addError(new FieldError("sampleForm", "username", "不能为空"));
        Method method = SampleForm.class.getDeclaredMethod("getUsername");
        MethodParameter parameter = new MethodParameter(method, -1);

        ResponseEntity<Result<Void>> response =
                handler.handleMethodArgumentNotValid(
                        new MethodArgumentNotValidException(parameter, bindingResult));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.BAD_REQUEST.code());
        assertThat(response.getBody().message()).contains("username").contains("不能为空");
    }

    @Test
    @DisplayName("参数绑定失败应返回 400 并列出字段")
    void bindExceptionShouldReturnBadRequest() {
        BeanPropertyBindingResult bindingResult =
                new BeanPropertyBindingResult(new SampleForm(), "sampleForm");
        bindingResult.addError(new FieldError("sampleForm", "pageSize", "必须大于 0"));

        ResponseEntity<Result<Void>> response =
                handler.handleBind(new BindException(bindingResult));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("pageSize");
    }

    @Test
    @DisplayName("缺少必填参数应返回 400 并指出参数名")
    void missingParamShouldReturnBadRequestWithName() {
        ResponseEntity<Result<Void>> response =
                handler.handleMissingParam(
                        new MissingServletRequestParameterException("userId", "Long"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("userId");
    }

    @Test
    @DisplayName("约束校验失败应返回 400；空违规集合也不应崩溃")
    void constraintViolationShouldReturnBadRequest() {
        ResponseEntity<Result<Void>> response =
                handler.handleConstraintViolation(new ConstraintViolationException(Set.of()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.BAD_REQUEST.code());
    }

    // ------------------------------------------------------------------
    // 协议层错误 → 对应 4xx
    // ------------------------------------------------------------------

    @Test
    @DisplayName("请求方法不支持应返回 405")
    void methodNotSupportedShouldReturn405() {
        ResponseEntity<Result<Void>> response =
                handler.handleMethodNotSupported(
                        new HttpRequestMethodNotSupportedException("DELETE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.METHOD_NOT_ALLOWED.code());
    }

    @Test
    @DisplayName("媒体类型不支持应返回 415")
    void mediaTypeNotSupportedShouldReturn415() {
        ResponseEntity<Result<Void>> response =
                handler.handleMediaTypeNotSupported(
                        new HttpMediaTypeNotSupportedException("不支持的媒体类型: application/xml"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE.code());
    }

    @Test
    @DisplayName("权限不足应返回 403")
    void accessDeniedShouldReturn403() {
        ResponseEntity<Result<Void>> response =
                handler.handleAccessDenied(new AccessDeniedException("缺少 plugin:manage:add"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.FORBIDDEN.code());
    }

    // ------------------------------------------------------------------
    // 兜底 → 500，且不泄漏内部细节
    // ------------------------------------------------------------------

    @Test
    @DisplayName("未捕获异常应返回 500")
    void unexpectedShouldReturn500() {
        ResponseEntity<Result<Void>> response =
                handler.handleUnexpected(new NullPointerException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.INTERNAL_ERROR.code());
    }

    @Test
    @DisplayName("未捕获异常不得把内部细节（表名 / 类名）泄漏给调用方")
    void unexpectedShouldNotLeakInternalDetails() {
        RuntimeException internal = new IllegalStateException("Table 'bot.sys_user' doesn't exist");

        ResponseEntity<Result<Void>> response = handler.handleUnexpected(internal);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message())
                .isEqualTo(ErrorCode.INTERNAL_ERROR.message())
                .doesNotContain("sys_user")
                .doesNotContain("bot.");
    }

    /** 仅用于构造 MethodParameter 的样例类。 */
    static class SampleForm {
        public String getUsername() {
            return null;
        }
    }
}
