package com.loombot.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 重置密码请求（忘记密码）。
 *
 * <h2>为什么只需要邮箱</h2>
 *
 * <p>找回密码的入口凭证是「能收到那个邮箱的信」这一事实本身，邮箱唯一确定一行用户。额外要求其他登录名称不能提供更多证明力，反而增加操作成本。
 *
 * @param email 注册邮箱
 * @param code 发到该邮箱的 6 位验证码
 * @param newPassword 新密码明文，落库前 BCrypt 哈希
 */
public record ResetPasswordRequest(
        @NotBlank(message = "邮箱不能为空")
                @Email(message = "邮箱格式不正确")
                @Size(max = 128, message = "邮箱最长 128 字符")
                String email,
        @NotBlank(message = "验证码不能为空") @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字")
                String code,
        @NotBlank(message = "新密码不能为空") @Size(min = 6, max = 20, message = "密码长度需为 6~20 位")
                String newPassword) {}
