package com.loom.system.dto;

/**
 * 菜单树里的一条。
 *
 * <h2>没有 {@code enabled}</h2>
 *
 * <p>原来有一个，来自 {@code sys_menu.status}，配合 {@code PUT /api/system/menus/{id}/status} 做菜单启停。
 * 那一列和那个接口都已删除（见 {@code V1__bootstrap_schema.sql} 末尾）：菜单「停用」和「删除」的结果 没有任何区别，多出来的只是一个必须到处补的过滤条件。
 *
 * <p>{@code visible} <b>保留</b>，而且它和删除不是一回事：它是**显式声明**的「这条留着，但不要出现在 侧边栏」。侧边栏可见性现在完全由 {@code visible}
 * 一个字段决定。
 */
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
        String remark) {}
