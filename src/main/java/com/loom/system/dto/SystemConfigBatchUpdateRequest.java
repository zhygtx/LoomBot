package com.loom.system.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 系统配置的批量保存请求。整页改完一起提交，落在一个事务里。 */
public record SystemConfigBatchUpdateRequest(
        @NotEmpty(message = "更新列表不能为空") @Size(max = 200, message = "一次最多更新 200 条配置") @Valid
                List<SystemConfigUpdate> updates) {}
