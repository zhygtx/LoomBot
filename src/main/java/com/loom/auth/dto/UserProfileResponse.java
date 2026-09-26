package com.loom.auth.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 当前用户信息。
 *
 * <p>同时用于 {@code GET /api/auth/me} 与登录响应里的 {@code user} 字段 —— 两处要求的是同一种 东西（「我是谁、我能做什么」），分成两个 DTO
 * 只会让「加了字段忘记同步」变成必然。
 *
 * <h2>刻意不含的字段</h2>
 *
 * <ul>
 *   <li>{@code password} —— 任何情况下都不出网，哪怕是哈希
 *   <li>昵称 / 头像 —— 数据库里没有这两列（D13：不涉及社区与社交展示）
 * </ul>
 *
 * @param id 用户 ID
 * @param email 邮箱（登录凭据）
 * @param status 1=正常 0=停用
 * @param roles 角色标识，如 {@code OWNER}
 * @param permissions 权限串，如 {@code connection:ws:list}；前端据此决定菜单与按钮
 * @param lastLoginTime 最后登录时间，本次登录之前的那一次
 * @param createTime 注册时间
 */
public record UserProfileResponse(
        Long id,
        String email,
        Integer status,
        List<String> roles,
        List<String> permissions,
        LocalDateTime lastLoginTime,
        LocalDateTime createTime) {}
