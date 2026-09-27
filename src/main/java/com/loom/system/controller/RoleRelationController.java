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
import org.springframework.web.bind.annotation.DeleteMapping;
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

    /**
     * 删除权限目录里的一条定义（**真删**）。
     *
     * <p>会连带清掉所有角色对它的授权。放在这个 Controller 而不是 {@code SystemManagementController}：
     * 它改的是「谁拥有什么」，属于关系管理；那边只管目录的读取与配置。
     */
    @DeleteMapping("/permissions/{id}")
    @PreAuthorize("@permission.has(authentication, 'system:permission:update')")
    public Result<Void> deletePermission(@PathVariable long id) {
        service.deletePermission(id);
        return Result.success();
    }

    /**
     * 删除一个角色（**真删**）。
     *
     * <p>会连带清掉它的权限授权、菜单授权和用户绑定。内置角色与「仍有启用用户」的角色会被拒绝。
     */
    @DeleteMapping("/roles/{id}")
    @PreAuthorize("@permission.has(authentication, 'system:role:update')")
    public Result<Void> deleteRole(@PathVariable long id) {
        service.deleteRole(id);
        return Result.success();
    }
}
