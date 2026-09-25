package com.loom.system.dto;

public record AuthOptionsResponse(
        boolean registerEnabled,
        boolean loginEnabled,
        boolean emailCodeEnabled,
        boolean passwordResetEnabled) {}
