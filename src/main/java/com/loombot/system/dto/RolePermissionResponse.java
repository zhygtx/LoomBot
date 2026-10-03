package com.loombot.system.dto;

import java.util.List;

public record RolePermissionResponse(Long id, String code, String name, List<Long> permissionIds) {}
