package com.loom.system.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

public record RelationUpdateRequest(
        @NotNull(message = "关系列表不能为空")
                List<@NotNull(message = "关系 ID 不能为空") @Positive(message = "关系 ID 必须为正数") Long>
                        ids) {}
