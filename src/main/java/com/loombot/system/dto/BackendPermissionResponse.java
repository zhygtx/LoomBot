package com.loombot.system.dto;

import java.time.LocalDateTime;

/**
 * 权限目录里的一条。
 *
 * <h2>没有 {@code enabled}</h2>
 *
 * <p>原来有一个，来自 {@code sys_permission.status}。那一列已经删除（见 {@code V1__bootstrap_schema.sql} 末尾），所以这里也没有了
 * —— 留着它只会是一个恒为 {@code true} 的字段，前端照着它写判断的人 会以为某个权限真的可能被停用。
 *
 * <p>权限是否生效现在完全由**角色授权**决定：没被任何角色勾选，就等于没人拥有。
 */
public record BackendPermissionResponse(
        Long id,
        String name,
        String permission,
        boolean backendRequired,
        LocalDateTime lastSeenTime) {}
