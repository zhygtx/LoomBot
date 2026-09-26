package com.loom.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 批量更新里的一条配置。
 *
 * <p>只有键和值 —— **没有单独的启用状态**。开关是值里的一个键（约定叫 {@code enabled}）， 不是独立于值之外的状态。表里也没有 status 列。
 *
 * <p>{@code value} 是 **JSON 文本**，统一是对象：{@code {"enabled": true}}、 {@code {"enabled": true,
 * "maxUsers": 3}}、{@code {"seconds": 30}}。 每个键的意思由这条配置自己决定。
 */
public record SystemConfigUpdate(
        @NotBlank(message = "配置键不能为空") String key,
        @NotBlank(message = "配置值不能为空") @Size(max = MAX_VALUE_LENGTH, message = "配置值过长")
                String value) {

    /**
     * 配置值长度上限。
     *
     * <p>{@code sys_config.config_value} 是 {@code TEXT}，上限 65535 **字节**；utf8mb4 下最坏情况 4 字节/字符，约
     * 16000 字符。这里按字符数卡在 16000 —— 直接写 65535 会看起来能过校验、 实际被数据库拒绝或截断。
     *
     * <p>必须是 {@code static final} 才能用在注解参数里（注解要求编译期常量），而 record 本来也只允许静态字段。
     */
    public static final int MAX_VALUE_LENGTH = 16000;
}
