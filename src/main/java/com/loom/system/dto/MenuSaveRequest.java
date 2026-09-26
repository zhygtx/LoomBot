package com.loom.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MenuSaveRequest(
        Long parentId,
        @NotBlank(message = "菜单类型不能为空") @Size(max = 16, message = "菜单类型最长 16 字符") String type,
        @NotBlank(message = "菜单名称不能为空") @Size(max = 128, message = "菜单名称最长 128 字符") String name,
        @Size(max = 128, message = "路由名称最长 128 字符") String routeName,
        @Size(max = 255, message = "路由地址最长 255 字符") String path,
        @Size(max = 128, message = "组件标识最长 128 字符") String componentKey,
        @Size(max = 64, message = "图标标识最长 64 字符") String iconKey,
        @Size(max = 255, message = "重定向地址最长 255 字符") String redirect,
        Integer sort,
        Boolean visible,
        Boolean keepAlive,
        @Size(max = 255, message = "备注最长 255 字符") String remark) {}
