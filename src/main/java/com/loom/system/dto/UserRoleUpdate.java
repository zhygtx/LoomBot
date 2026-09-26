package com.loom.system.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

public record UserRoleUpdate(
        @NotNull(message = "用户 ID 不能为空") @Positive(message = "用户 ID 必须为正数") Long userId,
        @NotNull(message = "用户启用状态不能为空") Boolean enabled,
        @NotNull(message = "角色列表不能为空")
                List<@NotNull(message = "角色 ID 不能为空") @Positive(message = "角色 ID 必须为正数") Long>
                        roleIds) {}
