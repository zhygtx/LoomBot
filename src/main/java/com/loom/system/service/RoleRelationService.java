package com.loom.system.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
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
import com.loom.system.dto.UserRoleUpdate;
import com.loom.system.mapper.RoleRelationMapper;
import java.util.HashSet;
import java.util.LinkedHashSet;
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

    /**
     * 一次事务内保存「用户启用状态 + 角色绑定」。
     *
     * <h2>为什么合成一个接口，而不是给启停单开一个</h2>
     *
     * <p>管理页上启停勾选框和角色是同一行的两列，用户点一次保存只该产生一次网络请求；更要紧的是不能出现 「角色改成功了、启停失败」这种半成品状态 ——
     * 那会让界面显示的东西和库里的不一致，而下次刷新就变成了 一个说不清的现象。
     *
     * <p>循环里逐条调用 {@link #updateUserRoles}。它是 {@code @Transactional} 的同类调用（自调用不走代理），
     * 事务由本方法这层提供，这正是我们想要的：任意一条变更失败，整批一起回滚。
     */
    @Transactional
    public void updateUserRolesBatch(List<UserRoleUpdate> updates) {
        updates.forEach(
                update -> {
                    // 保护必须在改之前做：改完之后「他原本是不是启用站长」就查不出来了
                    protectLastEnabledOwner(update.userId(), update.enabled());
                    updateUserRoles(update.userId(), update.roleIds());
                    if (mapper.updateUserStatus(update.userId(), update.enabled() ? 1 : 0) != 1) {
                        throw new BusinessException(ErrorCode.BAD_REQUEST, "用户不存在");
                    }
                });
    }

    /**
     * 拦住「停用最后一名启用站长」。
     *
     * <p>这不是防呆，而是防自锁：{@code TokenService.authenticate} 每个请求都会回查用户状态，最后一个启用站长
     * 一旦被停用，他自己的令牌当场失效，而「重新启用」这个动作又只有站长做得了 —— 界面上再也点不回去，只能改库。 角色侧的同类保护见 {@link #protectLastOwner}。
     */
    private void protectLastEnabledOwner(long userId, boolean enabled) {
        if (enabled) return;
        Long ownerRoleId = mapper.selectOwnerRoleId();
        if (ownerRoleId == null) return;
        // 他现在不是「启用站长」，停用他并不会减少可用站长的数量
        if (mapper.enabledUserHasRole(userId, ownerRoleId) != 1) return;
        if (mapper.countEnabledUsersWithRole(ownerRoleId) > 1) return;
        throw new BusinessException(ErrorCode.BAD_REQUEST, "系统至少需要保留一名启用的站长");
    }

    @Transactional
    public void updatePermissions(long roleId, List<String> patterns) {
        requireRole(roleId);
        List<String> normalized = normalizePermissionPatterns(patterns);
        if (normalized.isEmpty()) {
            protectOwnerWildcard(roleId, List.of());
            mapper.clearPermissions(roleId);
            userService.evictUsersWithRole(roleId);
            return;
        }
        normalized.forEach(pattern -> mapper.upsertPermissionPattern(IdWorker.getId(), pattern));
        List<Long> ids = mapper.selectPermissionIdsByPatterns(normalized);
        if (ids.size() != normalized.size()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "存在无效权限串");
        }
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

    private List<String> normalizePermissionPatterns(List<String> patterns) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String pattern : patterns) {
            String value = pattern == null ? "" : pattern.trim();
            String[] segments = value.split(":", -1);
            if (segments.length != 3
                    || segments[0].isBlank()
                    || segments[1].isBlank()
                    || segments[2].isBlank()
                    || !isPatternSegment(segments[0])
                    || !isPatternSegment(segments[1])
                    || !isPatternSegment(segments[2])) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "权限串必须是三段式: " + value);
            }
            normalized.add(value);
        }
        return List.copyOf(normalized);
    }

    private boolean isPatternSegment(String segment) {
        return "*".equals(segment) || segment.matches("[A-Za-z0-9_.-]+");
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
            throw new BusinessException(ErrorCode.BAD_REQUEST, "站长角色必须保留超级权限 *:*:*");
        }
    }

    private void requireRole(long roleId) {
        if (mapper.roleExists(roleId) != 1)
            throw new BusinessException(ErrorCode.BAD_REQUEST, "角色不存在");
    }
}
