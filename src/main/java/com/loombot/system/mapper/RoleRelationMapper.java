package com.loombot.system.mapper;

import com.loombot.system.dto.RoleMenuPair;
import com.loombot.system.dto.RolePermissionPair;
import com.loombot.system.dto.RoleSummary;
import com.loombot.system.dto.UserRolePair;
import com.loombot.system.dto.UserSummary;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 用户 / 角色 / 权限 / 菜单之间关系的持久层。
 *
 * <h2>这些 SQL 里为什么既没有 {@code deleted = 0} 也没有 {@code status = 1}</h2>
 *
 * <p>两条过滤条件都随「取消逻辑删除」和「删掉配置类状态列」一起消失了（理由见 {@code V1__bootstrap_schema.sql}
 * 末尾那一节）。这里记的是**消失之后为什么仍然是安全的**：
 *
 * <ul>
 *   <li>删除是真删，所以表里不可能有「已经删掉但还在」的行，不需要再加条件把它们挡掉；
 *   <li>角色 / 权限 / 菜单不再有启停概念，所以「这条算不算数」不再是一个需要在每条 SQL 里重复回答的问题。
 * </ul>
 *
 * <p>唯一还在做状态过滤的是 {@code sys_user.status} —— 那是个**账号开关**，不是记录有效性。 只有真正需要区分「这个人还算不算数」的地方才加它（见 {@link
 * #countEnabledUsersWithRole}）， 而不是无差别地加到每一张 JOIN 上。
 */
@Mapper
public interface RoleRelationMapper {

    @Select("SELECT id, code, name FROM sys_role ORDER BY sort, id")
    List<RoleSummary> selectRoles();

    @Select("SELECT id, email, status = 1 AS enabled FROM sys_user ORDER BY id")
    List<UserSummary> selectUsers();

    @Select(
            "SELECT ur.user_id AS userId, ur.role_id AS roleId FROM sys_user_role ur ORDER BY ur.user_id, ur.role_id")
    List<UserRolePair> selectUserRoleRelations();

    @Select(
            "SELECT rp.role_id AS roleId, rp.permission_id AS permissionId FROM sys_role_permission rp ORDER BY rp.role_id, rp.permission_id")
    List<RolePermissionPair> selectRolePermissionRelations();

    @Select(
            "SELECT rm.role_id AS roleId, rm.menu_id AS menuId FROM sys_role_menu rm ORDER BY rm.role_id, rm.menu_id")
    List<RoleMenuPair> selectRoleMenuRelations();

    @Select("SELECT COUNT(1) FROM sys_user WHERE id = #{userId}")
    int userExists(@Param("userId") long userId);

    @Select("SELECT role_id FROM sys_user_role WHERE user_id = #{userId}")
    List<Long> selectRoleIds(@Param("userId") long userId);

    @Delete("DELETE FROM sys_user_role WHERE user_id = #{userId}")
    int clearUserRoles(@Param("userId") long userId);

    @Insert("INSERT INTO sys_user_role (user_id, role_id) VALUES (#{userId}, #{id})")
    int addUserRole(@Param("userId") long userId, @Param("id") long id);

    @Update(
            "UPDATE sys_user SET status = #{status}, update_time = CURRENT_TIMESTAMP WHERE id = #{userId}")
    int updateUserStatus(@Param("userId") long userId, @Param("status") int status);

    @Select("SELECT permission_id FROM sys_role_permission WHERE role_id = #{roleId}")
    List<Long> selectPermissionIds(@Param("roleId") long roleId);

    @Select("SELECT menu_id FROM sys_role_menu WHERE role_id = #{roleId}")
    List<Long> selectMenuIds(@Param("roleId") long roleId);

    /** 清掉一个角色现有的全部权限授权（角色权限页保存前先清后插）。 */
    @Delete("DELETE FROM sys_role_permission WHERE role_id = #{roleId}")
    int clearPermissions(@Param("roleId") long roleId);

    @Insert("INSERT INTO sys_role_permission (role_id, permission_id) VALUES (#{roleId}, #{id})")
    int addPermission(@Param("roleId") long roleId, @Param("id") long id);

    /**
     * 补建一条通配权限串（角色授权里提交的 {@code module:*:*} 之类）。
     *
     * <p>{@code ON DUPLICATE KEY UPDATE} 只更新 {@code name}，不再需要把 {@code status} 或 {@code deleted} 复位
     * —— 那两列已经不存在了。留着它们会让人以为「这里曾经需要复活一条被软删的记录」。
     */
    @Insert(
            "INSERT INTO sys_permission (id, name, type, perm, backend_required, remark) "
                    + "VALUES (#{id}, #{permission}, 'API', #{permission}, 0, '角色授权通配模式') "
                    + "ON DUPLICATE KEY UPDATE name = VALUES(name)")
    int upsertPermissionPattern(@Param("id") long id, @Param("permission") String permission);

    @Select(
            "<script>"
                    + "SELECT id FROM sys_permission WHERE perm IN "
                    + "<foreach item='permission' collection='permissions' open='(' separator=',' close=')'>"
                    + "#{permission}</foreach>"
                    + "</script>")
    List<Long> selectPermissionIdsByPatterns(@Param("permissions") List<String> permissions);

    @Delete("DELETE FROM sys_role_menu WHERE role_id = #{roleId}")
    int clearMenus(@Param("roleId") long roleId);

    @Insert("INSERT INTO sys_role_menu (role_id, menu_id) VALUES (#{roleId}, #{id})")
    int addMenu(@Param("roleId") long roleId, @Param("id") long id);

    @Select("SELECT COUNT(1) FROM sys_role WHERE id = #{roleId}")
    int roleExists(@Param("roleId") long roleId);

    @Select("SELECT code FROM sys_role WHERE id = #{roleId}")
    String selectRoleCode(@Param("roleId") long roleId);

    @Select("SELECT COUNT(1) FROM sys_permission WHERE id = #{permissionId}")
    int permissionExists(@Param("permissionId") long permissionId);

    @Select("SELECT COUNT(1) FROM sys_menu WHERE id = #{menuId}")
    int menuExists(@Param("menuId") long menuId);

    @Select("SELECT parent_id FROM sys_menu WHERE id = #{menuId}")
    Long selectMenuParentId(@Param("menuId") long menuId);

    @Select("SELECT id FROM sys_role WHERE code = 'OWNER' LIMIT 1")
    Long selectOwnerRoleId();

    /** 角色的 {@code builtin} 标记。1 = 内置角色，不允许删除。 */
    @Select("SELECT builtin FROM sys_role WHERE id = #{roleId}")
    Integer selectRoleBuiltin(@Param("roleId") long roleId);

    // ------------------------------------------------------------------
    // 删除一个主体时的显式级联
    // ------------------------------------------------------------------
    //
    // 没有外键，数据库不会替我们清关系行。漏掉的后果不是报错，而是留下**孤儿关系行**：
    // 主体已经不在了，关联表里却还挂着一行，下次 join 出空结果，前端表现为「勾选的东西莫名少了」。
    // 所以每个删除入口都要显式调一次下面这几个方法，且和主体的删除在同一个事务里。

    /** 删权限时清掉所有角色对它的引用。 */
    @Delete("DELETE FROM sys_role_permission WHERE permission_id = #{permissionId}")
    int clearRolePermissionsByPermission(@Param("permissionId") long permissionId);

    @Delete("DELETE FROM sys_user_role WHERE role_id = #{roleId}")
    int clearUsersOfRole(@Param("roleId") long roleId);

    /** 删除菜单时清理它在角色菜单里的引用。 */
    @Delete("DELETE FROM sys_role_menu WHERE menu_id = #{menuId}")
    int clearRolesOfMenu(@Param("menuId") long menuId);

    @Delete("DELETE FROM sys_role WHERE id = #{roleId}")
    int deleteRole(@Param("roleId") long roleId);

    @Delete("DELETE FROM sys_permission WHERE id = #{permissionId}")
    int deletePermission(@Param("permissionId") long permissionId);

    @Select(
            "SELECT COUNT(1) FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id WHERE ur.role_id = #{roleId}")
    long countUsersWithRole(@Param("roleId") long roleId);

    /**
     * 持有某角色的**启用**用户数。
     *
     * <p>与 {@link #countUsersWithRole} 的区别只在 {@code u.status = 1}：停用用户虽然还挂在角色上，
     * 但他已经登不进来，不该被算作「这个角色还有人在用」。
     */
    @Select(
            "SELECT COUNT(1) FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id AND u.status = 1 WHERE ur.role_id = #{roleId}")
    long countEnabledUsersWithRole(@Param("roleId") long roleId);

    @Select("SELECT COUNT(1) FROM sys_user_role WHERE user_id = #{userId} AND role_id = #{roleId}")
    int userHasRole(@Param("userId") long userId, @Param("roleId") long roleId);

    /**
     * 该用户当前是否是「启用状态且持有该角色」。
     *
     * <p>少了 {@code u.status = 1} 会把「已经停用的站长」也算成站长，于是一个纯粹的重复保存会被误判成 「正在停用最后一名站长」而拒绝。
     */
    @Select(
            "SELECT COUNT(1) FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id AND u.status = 1 WHERE ur.user_id = #{userId} AND ur.role_id = #{roleId}")
    int enabledUserHasRole(@Param("userId") long userId, @Param("roleId") long roleId);

    @Select("SELECT id FROM sys_permission WHERE perm = '*:*:*' LIMIT 1")
    Long selectWildcardPermissionId();
}
