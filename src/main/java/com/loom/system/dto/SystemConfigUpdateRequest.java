package com.loom.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SystemConfigUpdateRequest(
        @NotBlank(message = "配置值不能为空") @Size(max = 1024, message = "配置值最长 1024 字符") String value) {}
