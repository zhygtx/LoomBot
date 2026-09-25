package com.loom.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求。
 *
 * <h2>为什么用邮箱而不是账号登录</h2>
 *
 * <p>邮箱是用户已经记住的那个标识（注册时本来就要收验证码），少一个要记的东西。 账号仍然存在，但它的职责是「稳定标识」，不是凭据 —— 这样即使将来允许改账号， 也不会影响任何人的登录习惯。
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
