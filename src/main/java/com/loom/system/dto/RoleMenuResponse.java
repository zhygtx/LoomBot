package com.loom.system.dto;

import java.util.List;

public record RoleMenuResponse(
        Long id, String code, String name, boolean enabled, List<Long> menuIds) {}
