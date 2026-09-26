package com.loom.system.mapper;

import com.loom.system.dto.RoleMenuPair;
import com.loom.system.dto.RolePermissionPair;
import com.loom.system.dto.RoleSummary;
import com.loom.system.dto.UserRolePair;
import com.loom.system.dto.UserSummary;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RoleRelationMapper {

    @Select(
            "SELECT id, code, name, status = 1 AS enabled FROM sys_role WHERE deleted = 0 ORDER BY sort, id")
    List<RoleSummary> selectRoles();

    @Select("SELECT id, email, status = 1 AS enabled FROM sys_user WHERE deleted = 0 ORDER BY id")
    List<UserSummary> selectUsers();

    @Select(
            "SELECT ur.user_id AS userId, ur.role_id AS roleId FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id AND u.deleted = 0 JOIN sys_role r ON r.id = ur.role_id AND r.deleted = 0")
    List<UserRolePair> selectUserRoleRelations();

    @Select(
            "SELECT rp.role_id AS roleId, rp.permission_id AS permissionId FROM sys_role_permission rp JOIN sys_role r ON r.id = rp.role_id AND r.deleted = 0 JOIN sys_permission p ON p.id = rp.permission_id AND p.deleted = 0")
    List<RolePermissionPair> selectRolePermissionRelations();

    @Select(
            "SELECT rm.role_id AS roleId, rm.menu_id AS menuId FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id AND r.deleted = 0 JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0")
    List<RoleMenuPair> selectRoleMenuRelations();

    @Select("SELECT COUNT(1) FROM sys_user WHERE id = #{userId} AND deleted = 0")
    int userExists(@Param("userId") long userId);

    @Select("SELECT role_id FROM sys_user_role WHERE user_id = #{userId}")
    List<Long> selectRoleIds(@Param("userId") long userId);

    @Delete("DELETE FROM sys_user_role WHERE user_id = #{userId}")
    int clearUserRoles(@Param("userId") long userId);

    @Insert("INSERT INTO sys_user_role (user_id, role_id) VALUES (#{userId}, #{id})")
    int addUserRole(@Param("userId") long userId, @Param("id") long id);

    @Select("SELECT permission_id FROM sys_role_permission WHERE role_id = #{roleId}")
    List<Long> selectPermissionIds(@Param("roleId") long roleId);

    @Select("SELECT menu_id FROM sys_role_menu WHERE role_id = #{roleId}")
    List<Long> selectMenuIds(@Param("roleId") long roleId);

    @Delete("DELETE FROM sys_role_permission WHERE role_id = #{roleId}")
    int clearPermissions(@Param("roleId") long roleId);

    @Insert("INSERT INTO sys_role_permission (role_id, permission_id) VALUES (#{roleId}, #{id})")
    int addPermission(@Param("roleId") long roleId, @Param("id") long id);

    @Delete("DELETE FROM sys_role_menu WHERE role_id = #{roleId}")
    int clearMenus(@Param("roleId") long roleId);

    @Insert("INSERT INTO sys_role_menu (role_id, menu_id) VALUES (#{roleId}, #{id})")
    int addMenu(@Param("roleId") long roleId, @Param("id") long id);

    @Select("SELECT COUNT(1) FROM sys_role WHERE id = #{roleId} AND deleted = 0")
    int roleExists(@Param("roleId") long roleId);

    @Select("SELECT code FROM sys_role WHERE id = #{roleId} AND deleted = 0")
    String selectRoleCode(@Param("roleId") long roleId);

    @Select("SELECT COUNT(1) FROM sys_permission WHERE id = #{permissionId} AND deleted = 0")
    int permissionExists(@Param("permissionId") long permissionId);

    @Select("SELECT COUNT(1) FROM sys_menu WHERE id = #{menuId} AND deleted = 0")
    int menuExists(@Param("menuId") long menuId);

    @Select("SELECT parent_id FROM sys_menu WHERE id = #{menuId} AND deleted = 0")
    Long selectMenuParentId(@Param("menuId") long menuId);

    @Select("SELECT id FROM sys_role WHERE code = 'OWNER' AND deleted = 0 LIMIT 1")
    Long selectOwnerRoleId();

    @Select(
            "SELECT COUNT(1) FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id AND u.deleted = 0 WHERE ur.role_id = #{roleId}")
    long countUsersWithRole(@Param("roleId") long roleId);

    @Select("SELECT COUNT(1) FROM sys_user_role WHERE user_id = #{userId} AND role_id = #{roleId}")
    int userHasRole(@Param("userId") long userId, @Param("roleId") long roleId);

    @Select("SELECT id FROM sys_permission WHERE perm = '*' AND deleted = 0 LIMIT 1")
    Long selectWildcardPermissionId();
}
