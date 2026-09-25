package com.loom.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.loom.auth.domain.SysUser;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户与授权关系持久层。
 *
 * <h2>为什么几张表的 SQL 挤在一个 Mapper 里</h2>
 *
 * <p>{@code sys_user} 是本模块唯一有实体、能走 {@code BaseMapper} 的表（单主键）。 其余三张是联合主键或只读的关联查询（见
 * D14），MyBatis-Plus 的 {@code BaseMapper} 对它们不可用。为了这三条只读查询 + 一条插入各建一个 Mapper 接口， 换来的是「类多到看不出哪条 SQL
 * 服务于哪个流程」。所以按**用途**聚合： 这里全部是「认证这条链路要用到的查询」。
 *
 * <p>当 {@code system} 模块开始做用户/角色管理界面时，管理向的 SQL 应当另建 {@code system} 自己的 Mapper ——
 * 不要把管理界面要的分页、模糊搜索塞进这里。
 *
 * <h2>为什么把「角色启用、权限启用、未删除」写进 JOIN 条件</h2>
 *
 * <p>停用一个角色必须立刻让它下面的权限失效，否则「停用」只是菜单上看不见， 拿着旧令牌的人照样能调接口。这类条件写在 SQL 里比在 Java 里过滤更难漏 ——
 * 忘了过滤是静默的权限泄漏，不会报错。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /** 用户拥有的角色标识（{@code sys_role.code}），只含启用且未删除的角色。 */
    @Select(
            """
            SELECT r.code
              FROM sys_user_role ur
              JOIN sys_role r ON r.id = ur.role_id AND r.status = 1 AND r.deleted = 0
             WHERE ur.user_id = #{userId}
            """)
    List<String> selectRoleCodes(@Param("userId") Long userId);

    /**
     * 用户拥有的权限串（{@code sys_permission.perm}）。
     *
     * <p>{@code p.perm IS NOT NULL} 是必须的：目录类记录（{@code type=CATALOG}）的 {@code perm} 允许为空，而空的权限串塞进
     * {@code GrantedAuthority} 是非法值，Spring Security 会直接抛 {@code IllegalArgumentException}。
     */
    @Select(
            """
            SELECT DISTINCT p.perm
              FROM sys_user_role ur
              JOIN sys_role r ON r.id = ur.role_id AND r.status = 1 AND r.deleted = 0
              JOIN sys_role_permission rp ON rp.role_id = ur.role_id
              JOIN sys_permission p ON p.id = rp.permission_id AND p.status = 1 AND p.deleted = 0
             WHERE ur.user_id = #{userId} AND p.perm IS NOT NULL
            """)
    List<String> selectPermissionStrings(@Param("userId") Long userId);

    /** 按标识取角色 ID。找不到返回 {@code null}（种子数据缺失时应当报错，见调用方）。 */
    @Select("SELECT id FROM sys_role WHERE code = #{code} AND deleted = 0 LIMIT 1")
    Long selectRoleIdByCode(@Param("code") String code);

    /** 绑定用户与角色。重复绑定会被联合主键拒绝，由调用方处理。 */
    @Insert("INSERT INTO sys_user_role (user_id, role_id) VALUES (#{userId}, #{roleId})")
    int insertUserRole(@Param("userId") Long userId, @Param("roleId") Long roleId);
}
