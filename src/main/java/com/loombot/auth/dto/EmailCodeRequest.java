package com.loombot.auth.dto;

import com.loombot.auth.domain.EmailCodeScene;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 请求发送邮箱验证码。
 *
 * @param email 收件邮箱
 * @param scene 用途。注册与重置密码的验证码互不通用，见 {@link EmailCodeScene}
 */
public record EmailCodeRequest(
        @NotBlank(message = "邮箱不能为空")
                @Email(message = "邮箱格式不正确")
                @Size(max = 128, message = "邮箱最长 128 字符")
                String email,
        @NotNull(message = "验证码用途不能为空") EmailCodeScene scene) {}
