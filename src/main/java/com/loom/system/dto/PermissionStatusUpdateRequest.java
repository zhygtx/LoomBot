package com.loom.system.dto;

import jakarta.validation.constraints.NotNull;

public record PermissionStatusUpdateRequest(@NotNull(message = "启用状态不能为空") Boolean enabled) {}
