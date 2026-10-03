package com.loombot.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** Role permission patterns submitted by the permission matrix. */
public record PermissionUpdateRequest(
        @NotNull(message = "权限列表不能为空") List<@NotBlank(message = "权限串不能为空") String> permissions) {}
