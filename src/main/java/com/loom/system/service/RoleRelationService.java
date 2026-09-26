package com.loom.system.service;

import com.loom.auth.service.UserService;
import com.loom.common.api.ErrorCode;
import com.loom.common.exception.BusinessException;
import com.loom.system.dto.RoleMenuPair;
import com.loom.system.dto.RoleMenuResponse;
import com.loom.system.dto.RolePermissionPair;
import com.loom.system.dto.RolePermissionResponse;
import com.loom.system.dto.RoleRelationResponse;
import com.loom.system.dto.RoleSummary;
import com.loom.system.dto.UserRolePair;
import com.loom.system.dto.UserRoleResponse;
import com.loom.system.mapper.RoleRelationMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoleRelationService {

    private final RoleRelationMapper mapper;
    private final UserService userService;

    public RoleRelationService(RoleRelationMapper mapper, UserService userService) {
        this.mapper = mapper;
        this.userService = userService;
    }

    public List<RoleRelationResponse> roles() {
        Map<Long, List<Long>> permissionIds = groupRolePermissions();
        Map<Long, List<Long>> menuIds = groupRoleMenus();
        return mapper.selectRoles().stream()
                .map(
                        role ->
                                new RoleRelationResponse(
                                        role.id(),
                                        role.code(),
                                        role.name(),
                                        role.enabled(),
                                        permissionIds.getOrDefault(role.id(), List.of()),
                                        menuIds.getOrDefault(role.id(), List.of())))
                .toList();
    }

    public List<UserRoleResponse> users() {
        Map<Long, List<Long>> roleIds = groupUserRoles();
        return mapper.selectUsers().stream()
                .map(
                        user ->
                                new UserRoleResponse(
                                        user.id(),
                                        user.email(),
                                        user.enabled(),
                                        roleIds.getOrDefault(user.id(), List.of())))
                .toList();
    }

    public List<RoleSummary> roleSummaries() {
        return mapper.selectRoles();
    }

    public List<RolePermissionResponse> rolePermissions() {
        Map<Long, List<Long>> permissionIds = groupRolePermissions();
        return mapper.selectRoles().stream()
                .map(
                        role ->
                                new RolePermissionResponse(
                                        role.id(),
                                        role.code(),
                                        role.name(),
                                        role.enabled(),
                                        permissionIds.getOrDefault(role.id(), List.of())))
                .toList();
    }

    public List<RoleMenuResponse> roleMenus() {
        Map<Long, List<Long>> menuIds = groupRoleMenus();
        return mapper.selectRoles().stream()
                .map(
                        role ->
                                new RoleMenuResponse(
                                        role.id(),
                                        role.code(),
                                        role.name(),
                                        role.enabled(),
                                        menuIds.getOrDefault(role.id(), List.of())))
                .toList();
    }

    private Map<Long, List<Long>> groupUserRoles() {
        return mapper.selectUserRoleRelations().stream()
                .collect(
                        Collectors.groupingBy(
                                UserRolePair::userId,
                                Collectors.mapping(UserRolePair::roleId, Collectors.toList())));
    }

    private Map<Long, List<Long>> groupRolePermissions() {
        return mapper.selectRolePermissionRelations().stream()
                .collect(
                        Collectors.groupingBy(
                                RolePermissionPair::roleId,
                                Collectors.mapping(
                                        RolePermissionPair::permissionId, Collectors.toList())));
    }

    private Map<Long, List<Long>> groupRoleMenus() {
        return mapper.selectRoleMenuRelations().stream()
                .collect(
                        Collectors.groupingBy(
                                RoleMenuPair::roleId,
                                Collectors.mapping(RoleMenuPair::menuId, Collectors.toList())));
    }

    @Transactional
    public void updateUserRoles(long userId, List<Long> ids) {
        requireUser(userId);
        requireRoles(ids);
        protectLastOwner(userId, ids);
        mapper.clearUserRoles(userId);
        ids.stream().distinct().forEach(id -> mapper.addUserRole(userId, id));
        userService.evictAuthorizationCache(userId);
    }

    @Transactional
    public void updatePermissions(long roleId, List<Long> ids) {
        requireRole(roleId);
        requirePermissions(ids);
        protectOwnerWildcard(roleId, ids);
        mapper.clearPermissions(roleId);
        ids.stream().distinct().forEach(id -> mapper.addPermission(roleId, id));
        userService.evictUsersWithRole(roleId);
    }

    @Transactional
    public void updateMenus(long roleId, List<Long> ids) {
        requireRole(roleId);
        Set<Long> menuIds = normalizeMenuIds(ids);
        mapper.clearMenus(roleId);
        menuIds.forEach(id -> mapper.addMenu(roleId, id));
        userService.evictUsersWithRole(roleId);
    }

    private void requireUser(long userId) {
        if (mapper.userExists(userId) != 1)
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户不存在");
    }

    private void requireRoles(List<Long> ids) {
        for (Long id : ids) {
            if (mapper.roleExists(id) != 1)
                throw new BusinessException(ErrorCode.BAD_REQUEST, "角色不存在: " + id);
        }
    }

    private void requirePermissions(List<Long> ids) {
        for (Long id : ids) {
            if (mapper.permissionExists(id) != 1)
                throw new BusinessException(ErrorCode.BAD_REQUEST, "权限不存在: " + id);
        }
    }

    private Set<Long> normalizeMenuIds(List<Long> ids) {
        Set<Long> result = new HashSet<>();
        for (Long id : ids) {
            if (mapper.menuExists(id) != 1)
                throw new BusinessException(ErrorCode.BAD_REQUEST, "菜单不存在: " + id);
            long current = id;
            while (current > 0 && result.add(current)) {
                Long parentId = mapper.selectMenuParentId(current);
                if (parentId == null) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "菜单不存在: " + current);
                }
                current = parentId;
            }
        }
        return result;
    }

    private void protectLastOwner(long userId, List<Long> ids) {
        Long ownerRoleId = mapper.selectOwnerRoleId();
        if (ownerRoleId == null
                || mapper.userHasRole(userId, ownerRoleId) != 1
                || ids.contains(ownerRoleId)
                || mapper.countUsersWithRole(ownerRoleId) > 1) {
            return;
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "系统至少需要保留一名站长");
    }

    private void protectOwnerWildcard(long roleId, List<Long> ids) {
        if (!"OWNER".equals(mapper.selectRoleCode(roleId))) return;
        Long wildcardId = mapper.selectWildcardPermissionId();
        if (wildcardId != null && !ids.contains(wildcardId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "站长角色必须保留超级权限 *");
        }
    }

    private void requireRole(long roleId) {
        if (mapper.roleExists(roleId) != 1)
            throw new BusinessException(ErrorCode.BAD_REQUEST, "角色不存在");
    }
}
