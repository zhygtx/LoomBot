package com.loom.system.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.loom.auth.service.UserService;
import com.loom.common.api.ErrorCode;
import com.loom.common.exception.BusinessException;
import com.loom.system.dto.BackendPermissionResponse;
import com.loom.system.mapper.BackendPermissionMapper;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BackendPermissionCatalogService {

    private static final Set<String> CONTROL_PLANE_PERMISSIONS =
            Set.of(
                    "system:permission:list",
                    "system:permission:update",
                    "system:role:list",
                    "system:role:update",
                    "system:user:list",
                    "system:user:update");

    private final BackendPermissionMapper mapper;
    private final UserService userService;
    private volatile Set<String> enabledRequiredPermissions = Set.of();

    public BackendPermissionCatalogService(
            BackendPermissionMapper mapper, UserService userService) {
        this.mapper = mapper;
        this.userService = userService;
    }

    @Transactional
    public void synchronize(Set<String> permissions) {
        mapper.clearBackendRequired();
        permissions.stream()
                .sorted()
                .forEach(
                        permission -> mapper.upsertBackendPermission(IdWorker.getId(), permission));
        refreshEnabledRequirements();
    }

    /** 后端要求的具体权限点只有在目录中启用时才能被任何 glob 授权命中。 */
    public boolean isEnabledRequirement(String permission) {
        return enabledRequiredPermissions.contains(permission);
    }

    public List<BackendPermissionResponse> listRequired() {
        return mapper.selectBackendRequiredPermissions();
    }

    public List<BackendPermissionResponse> listAll() {
        return mapper.selectAllPermissions();
    }

    @Transactional
    public void setEnabled(long permissionId, boolean enabled) {
        String permission = mapper.selectBackendRequiredPermissionString(permissionId);
        if (permission == null) {
            throw new BusinessException(ErrorCode.PERMISSION_NOT_FOUND);
        }
        if (!enabled && CONTROL_PLANE_PERMISSIONS.contains(permission)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "权限管理入口不能停用，否则管理端将无法恢复权限");
        }
        List<Long> affectedUserIds = mapper.selectAffectedUserIds(permissionId);
        if (mapper.updateStatus(permissionId, enabled ? 1 : 0) != 1) {
            throw new BusinessException(ErrorCode.PERMISSION_NOT_FOUND);
        }
        refreshEnabledRequirements();
        affectedUserIds.forEach(userService::evictAuthorizationCache);
    }

    private void refreshEnabledRequirements() {
        enabledRequiredPermissions = Set.copyOf(mapper.selectEnabledBackendPermissionStrings());
    }
}
