package com.loom.system.mapper;

import com.loom.system.dto.BackendPermissionResponse;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface BackendPermissionMapper {

    @Update("UPDATE sys_permission SET backend_required = 0 WHERE backend_required = 1")
    int clearBackendRequired();

    @Insert(
            """
            INSERT INTO sys_permission
                (id, name, type, perm, status, backend_required, last_seen_time, remark)
            VALUES
                (#{id}, #{permission}, 'API', #{permission}, 1, 1, CURRENT_TIMESTAMP,
                 '后端启动扫描自动发现')
            ON DUPLICATE KEY UPDATE
                   name = VALUES(name),
                   type = 'API',
                   backend_required = 1,
                last_seen_time = CURRENT_TIMESTAMP
            """)
    int upsertBackendPermission(@Param("id") long id, @Param("permission") String permission);

    @Select(
            """
            SELECT id,
                   name,
                   perm AS permission,
                   status = 1 AS enabled,
                   backend_required = 1 AS backendRequired,
                   last_seen_time AS lastSeenTime
              FROM sys_permission
             WHERE backend_required = 1 AND deleted = 0 AND perm IS NOT NULL
             ORDER BY perm
            """)
    List<BackendPermissionResponse> selectBackendRequiredPermissions();

    @Select(
            """
            SELECT id,
                   name,
                   perm AS permission,
                   status = 1 AS enabled,
                   backend_required = 1 AS backendRequired,
                   last_seen_time AS lastSeenTime
              FROM sys_permission
             WHERE deleted = 0 AND perm IS NOT NULL
             ORDER BY perm
            """)
    List<BackendPermissionResponse> selectAllPermissions();

    @Select(
            """
            SELECT perm
              FROM sys_permission
             WHERE backend_required = 1
               AND status = 1
               AND deleted = 0
               AND perm IS NOT NULL
            """)
    List<String> selectEnabledBackendPermissionStrings();

    @Select(
            """
            SELECT perm
              FROM sys_permission
             WHERE id = #{permissionId}
               AND backend_required = 1
               AND deleted = 0
               AND perm IS NOT NULL
            """)
    String selectBackendRequiredPermissionString(@Param("permissionId") long permissionId);

    @Select(
            """
            SELECT DISTINCT ur.user_id
              FROM sys_role_permission rp
              JOIN sys_user_role ur ON ur.role_id = rp.role_id
             WHERE rp.permission_id = #{permissionId}
            """)
    List<Long> selectAffectedUserIds(@Param("permissionId") long permissionId);

    @Update(
            """
            UPDATE sys_permission
               SET status = #{status}, update_time = CURRENT_TIMESTAMP
             WHERE id = #{permissionId} AND deleted = 0
               AND backend_required = 1
            """)
    int updateStatus(@Param("permissionId") long permissionId, @Param("status") int status);
}
