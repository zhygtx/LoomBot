/**
 * 系统管理模块 —— 用户、角色、权限的增删改查。
 *
 * <h2>职责</h2>
 *
 * <ul>
 *   <li>用户管理：增删改查、启用停用、重置密码、分配角色
 *   <li>角色管理：增删改查、内置角色保护、分配权限
 *   <li>权限管理：{@code sys_permission} 维护权限串，{@code sys_menu} 独立维护菜单树
 *   <li>连通性探针 {@code /api/system/ping}
 * </ul>
 *
 * <h2>表归属（与 auth 的分工）</h2>
 *
 * <p>五张 RBAC 表被本模块与 {@code auth} 模块**共用**，但共用的部分不同：
 *
 * <table border="1">
 *   <caption>分工</caption>
 *   <tr><th>表</th><th>认证侧（{@code auth}）</th><th>管理侧（本模块）</th></tr>
 *   <tr><td>{@code sys_user}</td><td>✅ 读（认邮箱、校验密码、加载权限）、写（注册、改密）</td>
 *       <td>独有：列表 / 检索 / 停用 / 删除</td></tr>
 *   <tr><td>{@code sys_user_role}</td><td>✅ 读（算权限）、写（注册时绑默认角色）</td>
 *       <td>独有：重新分配角色</td></tr>
 *   <tr><td>{@code sys_role} / {@code sys_permission} / {@code sys_role_permission}</td>
 *       <td>只读（算权限）</td><td>独有：定义与维护</td></tr>
 * </table>
 *
 * <p>⚠️ {@code auth} 的边界规则是「只允许依赖 {@code common}」，所以它**不可能** import 本模块。本模块要用用户数据时，正确做法是依赖 {@code
 * auth} 暴露的**接口**， 而不是直接引用 {@code auth.domain.SysUser} 实体 —— 直接引用实体是拆分时最大的阻碍。
 *
 * <h2>边界规则</h2>
 *
 * <p>只允许依赖 {@code common}（对本模块尚未落地的部分而言，这条依然成立）。
 */
package com.loom.system;
