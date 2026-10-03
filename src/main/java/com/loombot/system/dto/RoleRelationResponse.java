package com.loombot.system.dto;

import java.util.List;

public record RoleRelationResponse(
        Long id, String code, String name, List<Long> permissionIds, List<Long> menuIds) {}
