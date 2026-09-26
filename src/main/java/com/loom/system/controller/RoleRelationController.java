package com.loom.system.controller;

import com.loom.common.api.Result;
import com.loom.system.dto.PermissionUpdateRequest;
import com.loom.system.dto.RelationUpdateRequest;
import com.loom.system.dto.RoleMenuResponse;
import com.loom.system.dto.RolePermissionResponse;
import com.loom.system.dto.RoleRelationResponse;
import com.loom.system.dto.RoleSummary;
import com.loom.system.dto.UserRoleBatchUpdateRequest;
import com.loom.system.dto.UserRoleResponse;
import com.loom.system.service.RoleRelationService;
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
@RequestMapping("/api/system/relations")
public class RoleRelationController {

    private final RoleRelationService service;

    public RoleRelationController(RoleRelationService service) {
        this.service = service;
    }

    @GetMapping("/roles")
    @PreAuthorize("@permission.has(authentication, 'system:role:list')")
    public Result<List<RoleRelationResponse>> roles() {
        return Result.success(service.roles());
    }

    @GetMapping("/roles/summaries")
    @PreAuthorize("@permission.has(authentication, 'system:role:list')")
    public Result<List<RoleSummary>> roleSummaries() {
        return Result.success(service.roleSummaries());
    }

    @GetMapping("/roles/permissions")
    @PreAuthorize("@permission.has(authentication, 'system:role:list')")
    public Result<List<RolePermissionResponse>> rolePermissions() {
        return Result.success(service.rolePermissions());
    }

    @GetMapping("/roles/menus")
    @PreAuthorize("@permission.has(authentication, 'system:role:list')")
    public Result<List<RoleMenuResponse>> roleMenus() {
        return Result.success(service.roleMenus());
    }

    @GetMapping("/users")
    @PreAuthorize("@permission.has(authentication, 'system:user:list')")
    public Result<List<UserRoleResponse>> users() {
        return Result.success(service.users());
    }

    @GetMapping("/users/roles")
    @PreAuthorize("@permission.has(authentication, 'system:user:list')")
    public Result<List<UserRoleResponse>> userRoles() {
        return Result.success(service.users());
    }

    @PutMapping("/users/{id}/roles")
    @PreAuthorize("@permission.has(authentication, 'system:user:update')")
    public Result<Void> userRoles(
            @PathVariable long id, @Valid @RequestBody RelationUpdateRequest request) {
        service.updateUserRoles(id, request.ids());
        return Result.success();
    }

    @PutMapping("/users/roles/batch")
    @PreAuthorize("@permission.has(authentication, 'system:user:update')")
    public Result<Void> userRolesBatch(@Valid @RequestBody UserRoleBatchUpdateRequest request) {
        service.updateUserRolesBatch(request.updates());
        return Result.success();
    }

    @PutMapping("/roles/{id}/permissions")
    @PreAuthorize("@permission.has(authentication, 'system:role:update')")
    public Result<Void> permissions(
            @PathVariable long id, @Valid @RequestBody PermissionUpdateRequest request) {
        service.updatePermissions(id, request.permissions());
        return Result.success();
    }

    @PutMapping("/roles/{id}/menus")
    @PreAuthorize("@permission.has(authentication, 'system:role:update')")
    public Result<Void> menus(
            @PathVariable long id, @Valid @RequestBody RelationUpdateRequest request) {
        service.updateMenus(id, request.ids());
        return Result.success();
    }
}
