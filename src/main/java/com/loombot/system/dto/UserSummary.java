package com.loombot.system.dto;

/**
 * 用户角色表里的一行。
 *
 * <p>{@code enabled} 在这里**保留**，因为它来自 {@code sys_user.status} —— 全库唯一留下的状态列。 它是账号开关（封号 /
 * 解封），不是记录有效性，语义上和被删掉的那些「配置启停」不是一回事。
 */
public record UserSummary(Long id, String email, boolean enabled) {}
