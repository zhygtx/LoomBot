package com.loom.system.mapper;

import com.loom.system.dto.BackendPermissionResponse;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 后端权限目录的同步与读取。
 *
 * <h2>为什么查询里既没有 {@code deleted = 0}，也没有 {@code status = 1}</h2>
 *
 * <p>两条过滤条件都随「取消逻辑删除」和「删掉权限的 status 列」一起消失了。这不是放宽， 而是把一件本来就该由**授权关系**回答的事还回去：
 *
 * <p>原来的 {@code status} 想让「某个权限暂时失效」，但权限失效的正确做法是在角色授权里取消勾选 ——
 * 那才是「谁拥有什么」这件事的落点。放在权限定义上，停用一个权限会让**所有**角色一起失灵 （包括持有 {@code *:*:*} 的站长），是全局性的副作用，还逼着每条查询都记得带上
 * {@code status = 1}，漏一条就静默地泄漏权限。
 */
@Mapper
public interface BackendPermissionMapper {

    @Insert(
            """
            <script>
            INSERT INTO sys_permission
                (id, name, type, perm, backend_required, last_seen_time, remark)
            VALUES
            <foreach collection="permissions" item="item" separator=",">
                (#{item.id}, #{item.permission}, 'API', #{item.permission}, 1,
                 CURRENT_TIMESTAMP, '后端启动扫描自动发现')
            </foreach>
            ON DUPLICATE KEY UPDATE
                   name = VALUES(name),
                   type = 'API',
                   backend_required = 1,
                last_seen_time = CURRENT_TIMESTAMP
            </script>
            """)
    int upsertBackendPermissions(@Param("permissions") List<BackendPermissionSeed> permissions);

    @Select(
            """
            SELECT id,
                   name,
                   perm AS permission,
                   backend_required = 1 AS backendRequired,
                   last_seen_time AS lastSeenTime
              FROM sys_permission
             WHERE backend_required = 1 AND perm IS NOT NULL
             ORDER BY perm
            """)
    List<BackendPermissionResponse> selectBackendRequiredPermissions();

    @Select(
            """
            SELECT id,
                   name,
                   perm AS permission,
                   backend_required = 1 AS backendRequired,
                   last_seen_time AS lastSeenTime
              FROM sys_permission
             WHERE perm IS NOT NULL
             ORDER BY perm
            """)
    List<BackendPermissionResponse> selectAllPermissions();

    @Select(
            """
            SELECT DISTINCT ur.user_id
              FROM sys_role_permission rp
              JOIN sys_user_role ur ON ur.role_id = rp.role_id
             WHERE rp.permission_id = #{permissionId}
            """)
    List<Long> selectAffectedUserIds(@Param("permissionId") long permissionId);

    record BackendPermissionSeed(long id, String permission) {}
}
