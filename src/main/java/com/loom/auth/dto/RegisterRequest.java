package com.loom.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。
 *
 * <h2>为什么注册要填「账号」，而登录却不用它</h2>
 *
 * <p>登录凭据是**邮箱**（用户记一个就够了），但 {@code sys_user.account} 是 NOT NULL + 唯一， 它承担的是「稳定标识」这个职责 ——
 * 邮箱可以改，账号不行。这不是重复字段： 两者的生命周期不同。理由见 {@code V3__rename_username_to_account.sql}。
 *
 * <h2>为什么这里没有「用户名 / 昵称」</h2>
 *
 * <p>本项目没有用户之间的交流，没有展示名这一层。曾经的前端表单里有个「用户名」输入框， 但数据库里从来没有对应的列 —— 那正是这次要消除的歧义之一。
 *
 * @param account 账号，3~20 位，字母开头，只含字母 / 数字 / 下划线
 * @param email 邮箱，同时是登录凭据
 * @param code 发到该邮箱的 6 位验证码
 * @param password 明文密码，落库前 BCrypt 哈希
 */
public record RegisterRequest(
        @NotBlank(message = "账号不能为空")
                @Pattern(
                        regexp = "^[a-zA-Z][a-zA-Z0-9_]{2,19}$",
                        message = "账号需 3~20 位，以字母开头，只含字母、数字、下划线")
                String account,
        @NotBlank(message = "邮箱不能为空")
                @Email(message = "邮箱格式不正确")
                @Size(max = 128, message = "邮箱最长 128 字符")
                String email,
        @NotBlank(message = "验证码不能为空") @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字")
                String code,
        @NotBlank(message = "密码不能为空") @Size(min = 6, max = 20, message = "密码长度需为 6~20 位")
                String password) {}
