/**
 * 系统管理模块 —— 用户、角色、权限的增删改查。
 *
 * <h2>职责</h2>
 *
 * <ul>
 *   <li>用户管理：增删改查、启用停用、重置密码、分配角色
 *   <li>角色管理：增删改查、内置角色保护、分配权限
 *   <li>权限管理：菜单树维护（{@code sys_permission} 同时是菜单表）、权限串维护
 *   <li>连通性探针 {@code /api/system/ping}
 * </ul>
 *
 * <h2>表归属</h2>
 *
 * <p>{@code sys_user} / {@code sys_role} / {@code sys_permission} / {@code sys_user_role} / {@code
 * sys_role_permission} —— 五张 RBAC 表。
 *
 * <p>注意：这些表被本模块与 {@code auth} 模块<b>共用</b>。这是当前单体的合理状态， 但也是拆分时最需要处理的地方（见 {@link com.loom.auth}
 * 的拆分说明）。
 *
 * <h2>边界规则</h2>
 *
 * <p>只允许依赖 {@code common}。其他模块需要用户信息时，应通过接口而非直接引用 {@code system.entity.User} —— 直接引用实体是拆分时最大的阻碍。
 */
package com.loom.system;
