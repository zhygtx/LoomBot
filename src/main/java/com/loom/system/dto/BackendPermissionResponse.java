package com.loom.system.dto;

import java.time.LocalDateTime;

public record BackendPermissionResponse(
        Long id,
        String name,
        String permission,
        boolean enabled,
        boolean backendRequired,
        LocalDateTime lastSeenTime) {}
