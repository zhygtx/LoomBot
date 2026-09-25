package com.loom.system.controller;

import com.loom.common.api.Result;
import com.loom.system.domain.SystemConfig;
import com.loom.system.dto.AuthOptionsResponse;
import com.loom.system.dto.BackendPermissionResponse;
import com.loom.system.dto.PermissionStatusUpdateRequest;
import com.loom.system.dto.SystemConfigUpdateRequest;
import com.loom.system.service.BackendPermissionCatalogService;
import com.loom.system.service.SystemConfigService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemManagementController {

    private final SystemConfigService configService;
    private final BackendPermissionCatalogService permissionCatalogService;

    public SystemManagementController(
            SystemConfigService configService,
            BackendPermissionCatalogService permissionCatalogService) {
        this.configService = configService;
        this.permissionCatalogService = permissionCatalogService;
    }

    /** 登录注册页可匿名读取的基础开关，不包含其他系统配置。 */
    @GetMapping("/public/auth-options")
    public Result<AuthOptionsResponse> authOptions() {
        return Result.success(configService.authOptions());
    }

    @GetMapping("/config")
    @PreAuthorize("@permission.has(authentication, 'system:config:list')")
    public Result<List<SystemConfig>> configs() {
        return Result.success(configService.list());
    }

    @PutMapping("/config/{key}")
    @PreAuthorize("@permission.has(authentication, 'system:config:update')")
    public Result<SystemConfig> updateConfig(
            @PathVariable String key, @Valid @RequestBody SystemConfigUpdateRequest request) {
        return Result.success(configService.update(key, request.value()));
    }

    /** 当前版本后端实际声明的权限要求，管理端可据此渲染角色授权项。 */
    @GetMapping("/permissions/backend-required")
    @PreAuthorize("@permission.has(authentication, 'system:permission:list')")
    public Result<List<BackendPermissionResponse>> backendRequiredPermissions() {
        return Result.success(permissionCatalogService.listRequired());
    }

    @PutMapping("/permissions/{id}/status")
    @PreAuthorize("@permission.has(authentication, 'system:permission:update')")
    public Result<Void> updatePermissionStatus(
            @PathVariable long id, @Valid @RequestBody PermissionStatusUpdateRequest request) {
        permissionCatalogService.setEnabled(id, request.enabled());
        return Result.success();
    }
}
