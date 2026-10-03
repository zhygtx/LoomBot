package com.loombot.system.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record UserRoleBatchUpdateRequest(
        @NotNull(message = "用户角色变更不能为空") List<@Valid UserRoleUpdate> updates) {}
