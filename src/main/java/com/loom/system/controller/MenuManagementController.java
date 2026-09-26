package com.loom.system.controller;

import com.loom.common.api.Result;
import com.loom.common.security.CurrentUser;
import com.loom.system.dto.MenuResponse;
import com.loom.system.dto.MenuSaveRequest;
import com.loom.system.dto.MenuStatusUpdateRequest;
import com.loom.system.service.MenuService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system/menus")
public class MenuManagementController {

    private final MenuService menuService;

    public MenuManagementController(MenuService menuService) {
        this.menuService = menuService;
    }

    @GetMapping("/navigation")
    public Result<List<MenuResponse>> navigation() {
        return Result.success(menuService.navigation(CurrentUser.requireId()));
    }

    /** 角色菜单关系编辑使用的目录，不要求菜单管理写权限。 */
    @GetMapping("/available")
    @PreAuthorize("@permission.has(authentication, 'system:role:list')")
    public Result<List<MenuResponse>> available() {
        return Result.success(menuService.list());
    }

    @GetMapping
    @PreAuthorize("@permission.has(authentication, 'system:menu:list')")
    public Result<List<MenuResponse>> list() {
        return Result.success(menuService.list());
    }

    @PostMapping
    @PreAuthorize("@permission.has(authentication, 'system:menu:create')")
    public Result<MenuResponse> create(@Valid @RequestBody MenuSaveRequest request) {
        return Result.success(menuService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@permission.has(authentication, 'system:menu:update')")
    public Result<MenuResponse> update(
            @PathVariable long id, @Valid @RequestBody MenuSaveRequest request) {
        return Result.success(menuService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@permission.has(authentication, 'system:menu:delete')")
    public Result<Void> delete(@PathVariable long id) {
        menuService.delete(id);
        return Result.success();
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("@permission.has(authentication, 'system:menu:update')")
    public Result<Void> updateStatus(
            @PathVariable long id, @Valid @RequestBody MenuStatusUpdateRequest request) {
        menuService.setEnabled(id, request.enabled());
        return Result.success();
    }
}
