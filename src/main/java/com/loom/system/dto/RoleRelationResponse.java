package com.loom.system.dto;

import java.util.List;

public record RoleRelationResponse(
        Long id,
        String code,
        String name,
        boolean enabled,
        List<Long> permissionIds,
        List<Long> menuIds) {}
