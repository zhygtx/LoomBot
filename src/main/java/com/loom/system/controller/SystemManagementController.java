package com.loom.system.controller;

import com.loom.common.api.Result;
import com.loom.system.dto.AuthOptionsResponse;
import com.loom.system.dto.BackendPermissionResponse;
import com.loom.system.dto.SystemConfigBatchUpdateRequest;
import com.loom.system.dto.SystemConfigResponse;
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
    public Result<List<SystemConfigResponse>> configs() {
        return Result.success(configService.list());
    }

    @PutMapping("/config/{key}")
    @PreAuthorize("@permission.has(authentication, 'system:config:update')")
    public Result<SystemConfigResponse> updateConfig(
            @PathVariable String key, @Valid @RequestBody SystemConfigUpdateRequest request) {
        return Result.success(configService.update(key, request.value()));
    }

    /**
     * 系统配置页的批量保存。
     *
     * <p>整页改完一次提交，而不是每改一个开关就发一个请求：配置项会立刻影响线上的登录/注册行为， 攒到一起提交能给一次「反悔」的机会，也让一批修改落在一个事务里。
     */
    @PutMapping("/config")
    @PreAuthorize("@permission.has(authentication, 'system:config:update')")
    public Result<List<SystemConfigResponse>> updateConfigs(
            @Valid @RequestBody SystemConfigBatchUpdateRequest request) {
        return Result.success(configService.batchUpdate(request.updates()));
    }

    /** 当前版本后端实际声明的权限要求，管理端可据此渲染角色授权项。 */
    @GetMapping("/permissions/backend-required")
    @PreAuthorize("@permission.has(authentication, 'system:permission:list')")
    public Result<List<BackendPermissionResponse>> backendRequiredPermissions() {
        return Result.success(permissionCatalogService.listRequired());
    }

    /** 角色授权使用的完整权限目录，包含超级权限等非后端扫描记录。 */
    @GetMapping("/permissions")
    @PreAuthorize("@permission.has(authentication, 'system:role:list')")
    public Result<List<BackendPermissionResponse>> permissions() {
        return Result.success(permissionCatalogService.listAll());
    }
}
