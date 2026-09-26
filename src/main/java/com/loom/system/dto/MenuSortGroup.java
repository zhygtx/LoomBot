package com.loom.system.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;

/**
 * 同一层级内的一次排序结果。
 *
 * <p>拖拽一次会同时影响两个层级（拖出旧父级、拖入新父级），所以接口收的是**分组列表**而不是单个层级： 一次请求、一个事务，不会出现「已经从旧父级摘掉、还没挂到新父级上」的中间态。
 *
 * @param parentId 这些菜单共同的父级；{@code 0} 表示顶级
 * @param ids 该父级下菜单 ID 的**完整**有序列表，顺序即最终展示顺序
 */
public record MenuSortGroup(
        @NotNull(message = "父级不能为空") @PositiveOrZero(message = "父级 ID 不能为负数") Long parentId,
        @NotNull(message = "菜单顺序不能为空") List<@NotNull(message = "菜单 ID 不能为空") Long> ids) {}
