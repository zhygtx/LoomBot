package com.loom.system.dto;

import java.util.List;

public record UserRoleResponse(Long id, String email, boolean enabled, List<Long> roleIds) {}
