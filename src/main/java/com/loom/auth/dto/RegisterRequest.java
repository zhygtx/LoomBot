package com.loom.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。
 *
 * @param email 邮箱，同时是登录凭据
 * @param code 发到该邮箱的 6 位验证码
 * @param password 明文密码，落库前 BCrypt 哈希
 */
public record RegisterRequest(
        @NotBlank(message = "邮箱不能为空")
                @Email(message = "邮箱格式不正确")
                @Size(max = 128, message = "邮箱最长 128 字符")
                String email,
        @NotBlank(message = "验证码不能为空") @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字")
                String code,
        @NotBlank(message = "密码不能为空") @Size(min = 6, max = 20, message = "密码长度需为 6~20 位")
                String password) {}
