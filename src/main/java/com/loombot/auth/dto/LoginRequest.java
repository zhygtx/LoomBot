package com.loombot.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求。
 *
 * <p>邮箱是注册时已经验证过的标识，也用于登录、验证码和找回密码，避免用户维护多套登录名称。
 *
 * <p>密码这里**只校验非空，不校验长度**。原因：长度规则属于「设置密码」时的约束 （注册 / 重置），登录端再校验一遍，只会在规则调整后把老用户挡在门外 ——
 * 而他们输入的密码本身可能完全正确。
 *
 * @param email 登录邮箱
 * @param password 明文密码
 */
public record LoginRequest(
        @NotBlank(message = "邮箱不能为空")
                @Email(message = "邮箱格式不正确")
                @Size(max = 128, message = "邮箱最长 128 字符")
                String email,
        @NotBlank(message = "密码不能为空") String password) {}
