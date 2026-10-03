package com.loombot.system.dto;

import java.util.List;

public record RoleMenuResponse(Long id, String code, String name, List<Long> menuIds) {}
