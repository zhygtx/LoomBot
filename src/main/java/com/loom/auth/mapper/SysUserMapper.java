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
 * <h2>为什么把「角色、权限未删除」写进 JOIN 条件（现在改成了什么都不用写）</h2>
 *
 * <p>原文的理由是「停用一个角色必须立刻让它下面的权限失效」。现在角色和权限都没有 status， 也没有逻辑删除，这条 JOIN 条件整个消失了 —— 能出现在 {@code
 * sys_role} 里的行就是有效的行。
 *
 * <p>这不是把防线去掉了，而是把「什么时候算无效」从**到处重复的 SQL 条件**收回到**删除动作本身**：
 * 删一个角色时在同一个事务里清掉它的授权关系，于是「存在即有效」成为一条不需要每条查询 各自维护的不变量。原来那套条件最难的地方在于「忘了写不报错」—— 少一个 {@code status =
 * 1} 就是一次静默的权限泄漏。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /** 用户拥有的角色标识（{@code sys_role.code}）。 */
    @Select(
            """
            SELECT r.code
              FROM sys_user_role ur
              JOIN sys_role r ON r.id = ur.role_id
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
              JOIN sys_role r ON r.id = ur.role_id
              JOIN sys_role_permission rp ON rp.role_id = ur.role_id
              JOIN sys_permission p ON p.id = rp.permission_id
             WHERE ur.user_id = #{userId} AND p.perm IS NOT NULL
            """)
    List<String> selectPermissionStrings(@Param("userId") Long userId);

    @Select(
            """
            SELECT DISTINCT m.id
              FROM sys_user_role ur
              JOIN sys_role r ON r.id = ur.role_id
              JOIN sys_role_menu rm ON rm.role_id = ur.role_id
              JOIN sys_menu m ON m.id = rm.menu_id AND m.visible = 1
             WHERE ur.user_id = #{userId}
            """)
    List<Long> selectMenuIds(@Param("userId") Long userId);

    @Select("SELECT user_id FROM sys_user_role WHERE role_id = #{roleId}")
    List<Long> selectUserIdsByRole(@Param("roleId") Long roleId);

    /**
     * 按标识取角色 ID。找不到返回 {@code null}（种子数据缺失时应当报错，见调用方）。
     *
     * <p>{@code LIMIT 1} 保留着，但现在它不再是「在若干条里挑一条活着的」，而是防御 {@code uk_role_code} 被手工删掉之后出现的重复行 —— 那种情况下
     * {@code selectOne} 会直接抛异常， 而报错信息里不会有「谁重复了」，排查成本远高于多这一个 LIMIT。
     */
    @Select("SELECT id FROM sys_role WHERE code = #{code} LIMIT 1")
    Long selectRoleIdByCode(@Param("code") String code);

    /** 绑定用户与角色。重复绑定会被联合主键拒绝，由调用方处理。 */
    @Insert("INSERT INTO sys_user_role (user_id, role_id) VALUES (#{userId}, #{roleId})")
    int insertUserRole(@Param("userId") Long userId, @Param("roleId") Long roleId);
}
