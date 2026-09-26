package com.loom.system.dto;

public record MenuResponse(
        Long id,
        Long parentId,
        String type,
        String name,
        String routeName,
        String path,
        String componentKey,
        String iconKey,
        String redirect,
        Integer sort,
        boolean visible,
        boolean keepAlive,
        boolean enabled,
        String remark) {}
