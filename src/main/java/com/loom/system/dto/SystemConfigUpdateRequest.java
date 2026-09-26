package com.loom.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SystemConfigUpdateRequest(
        @NotBlank(message = "配置值不能为空")
                @Size(max = SystemConfigUpdate.MAX_VALUE_LENGTH, message = "配置值过长")
                String value) {}
