package com.loombot.system.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.loombot.auth.service.UserService;
import com.loombot.common.api.ErrorCode;
import com.loombot.common.exception.BusinessException;
import com.loombot.common.security.CurrentUser;
import com.loombot.system.dto.RoleMenuPair;
import com.loombot.system.dto.RoleMenuResponse;
import com.loombot.system.dto.RolePermissionPair;
import com.loombot.system.dto.RolePermissionResponse;
import com.loombot.system.dto.RoleRelationResponse;
import com.loombot.system.dto.RoleSummary;
import com.loombot.system.dto.UserRolePair;
import com.loombot.system.dto.UserRoleResponse;
import com.loombot.system.dto.UserRoleUpdate;
import com.loombot.system.mapper.RoleRelationMapper;
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
    private final NavigationCache navigationCache;

    public RoleRelationService(
            RoleRelationMapper mapper, UserService userService, NavigationCache navigationCache) {
        this.mapper = mapper;
        this.userService = userService;
        this.navigationCache = navigationCache;
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
        protectOwnerBinding(userId, ids);
        protectLastOwner(userId, ids);
        mapper.clearUserRoles(userId);
        ids.stream().distinct().forEach(id -> mapper.addUserRole(userId, id));
        userService.evictAuthorizationCache(userId);
        navigationCache.invalidateAllAfterCommit();
    }

    /**
     * 一次事务内保存「用户启用状态 + 角色绑定」。
     *
     * <h2>为什么合成一个接口，而不是给启停单开一个</h2>
     *
     * <p>管理页上启停勾选框和角色是同一行的两列，用户点一次保存只该产生一次网络请求；更要紧的是不能出现 「角色改成功了、启停失败」这种半成品状态 ——
     * 那会让界面显示的东西和库里的不一致，而下次刷新就变成了 一个说不清的现象。
     *
     * <p>这里的 {@code enabled} 是 {@code sys_user.status}，也就是**封号开关**。它是全库唯一 保留的状态列 ——
     * 其他几张表的启停都随各自的状态列一起删掉了（见 {@code V1__bootstrap_schema.sql} 末尾）。
     * 所以这个批量接口保留原样是刻意的，它处理的本来就不是「配置启停」那件事。
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
                    userService.evictAuthenticationStateAfterCommit(update.userId());
                });
    }

    /**
     * 真删一个角色。
     *
     * <h2>为什么必须显式清三张关联表</h2>
     *
     * <p>没有外键，数据库不会替我们级联。漏掉任何一张的后果都不是报错，而是**孤儿关系行**： {@code sys_user_role} 里留着指向已删角色的行，算权限时 join
     * 出空结果 —— 用户会表现为 「我的权限莫名其妙少了一块」，而库里没有任何一行看起来是错的。
     *
     * <h2>两条守卫，保护的是两件不同的事</h2>
     *
     * <p><b>内置角色不许删</b>（{@code builtin = 1}）：{@code USER} / {@code ADMIN} / {@code OWNER}
     * 是代码和种子数据都依赖的锚点 —— 注册时按 {@code OWNER} 绑默认角色、{@code protectOwnerWildcard} 按 code
     * 找站长。删掉它们不会立刻报错，而是让这些路径在**下一次**运行时找不到锚点。
     *
     * <p><b>仍有启用用户的角色不许删</b>：这条守卫原来挂在「停用角色」上（防止把一个还有人在用的角色 停掉），角色状态列删掉之后它平移到了删除上，保护的是同一件事 ——
     * 不要让一次删除把别人正在用的 权限悄无声息地抽走。
     *
     * <p>判据用**启用**用户数（{@code countEnabledUsersWithRole}）而不是绑定数：停用用户虽然还挂在
     * 角色上，但他已经登不进来，不该被算作「这个角色还有人在用」。
     *
     * <h2>为什么这里不清授权缓存</h2>
     *
     * <p>被影响的人已经在上面的守卫里排除了足够多，剩下的只有停用用户 —— 他们登不进来， 缓存会在 TTL 内自然过期。这和 {@link #deletePermission}
     * 不一样：那边删的是被任意多个角色 引用的定义，无法反查受影响用户，只能全量清。
     */
    @Transactional
    public void deleteRole(long roleId) {
        requireRole(roleId);
        if (Integer.valueOf(1).equals(mapper.selectRoleBuiltin(roleId))) {
            throw new BusinessException(ErrorCode.BUILTIN_ROLE_READONLY);
        }
        if (mapper.countEnabledUsersWithRole(roleId) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该角色仍有启用用户，请先解除绑定");
        }
        mapper.clearPermissions(roleId);
        mapper.clearMenus(roleId);
        mapper.clearUsersOfRole(roleId);
        if (mapper.deleteRole(roleId) != 1) {
            throw new BusinessException(ErrorCode.ROLE_NOT_FOUND);
        }
        navigationCache.invalidateAllAfterCommit();
    }

    /**
     * 真删一个权限定义。
     *
     * <p>先清 {@code sys_role_permission}：权限被删而授权行还在，会让「角色拥有的权限」在 join 时少一条而不报错。清完之后所有角色的授权里自然就没有它了。
     */
    @Transactional
    public void deletePermission(long permissionId) {
        if (mapper.permissionExists(permissionId) != 1) {
            throw new BusinessException(ErrorCode.PERMISSION_NOT_FOUND);
        }
        Long wildcardId = mapper.selectWildcardPermissionId();
        if (wildcardId != null && wildcardId.equals(permissionId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "超级权限 *:*:* 不允许删除");
        }
        mapper.clearRolePermissionsByPermission(permissionId);
        if (mapper.deletePermission(permissionId) != 1) {
            throw new BusinessException(ErrorCode.PERMISSION_NOT_FOUND);
        }
        // 删的是一条**定义**，它可能被任意多个角色引用，而这里只清了关联行、没记下被影响了谁。
        // 逐个反查受影响用户要先把关联关系读出来，而这张表本来就不大 —— 全量清一遍更简单，
        // 也不会漏（漏掉的后果是「删了权限却还能调接口」，直到 TTL 到期）。
        userService.evictAllAuthorizationCaches();
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
        requireOwnerForOwnerRole(roleId, "只有站长可以调整站长的权限");
        List<String> normalized = normalizePermissionPatterns(patterns);
        requireOwnerToGrantWildcard(normalized);
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
        requireOwnerForOwnerRole(roleId, "只有站长可以调整站长的菜单");
        Set<Long> menuIds = normalizeMenuIds(ids);
        mapper.clearMenus(roleId);
        menuIds.forEach(id -> mapper.addMenu(roleId, id));
        userService.evictUsersWithRole(roleId);
        navigationCache.invalidateAllAfterCommit();
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

    /**
     * 拦住「摘掉最后一名站长的角色」。
     *
     * <p>这不是防呆，而是防自锁：最后一个站长一旦失去 {@code OWNER}，他自己的权限当场失效， 而「重新授权」这个动作又只有站长做得了 —— 界面上再也点不回去，只能改库。
     */
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

    /**
     * 拦住「非站长改动站长的角色绑定」。
     *
     * <p>这拦的是**授权链的两端**：既包括把 {@code OWNER} 授予某个用户（升权）， 也包括调整一个已经是站长的用户（比如把他从别的角色里摘出去、或者停用他）。
     * 判据只看当前调用者是不是站长，因此管理员即使握着 {@code system:user:update} 也改不动站长这一列。
     *
     * <p>为什么放在角色绑定这一层，而不是靠前端把复选框置灰：置灰只是别让人点错， 接口才是边界。删掉前端限制、直接构造请求同样要落到这个守卫上。
     */
    private void protectOwnerBinding(long userId, List<Long> ids) {
        Long ownerRoleId = mapper.selectOwnerRoleId();
        if (ownerRoleId == null || currentUserIsOwner()) {
            return;
        }
        if (ids.contains(ownerRoleId) || mapper.userHasRole(userId, ownerRoleId) == 1) {
            throw new BusinessException(ErrorCode.OWNER_PROTECTED, "只有站长可以调整站长的角色");
        }
    }

    /** 非站长不能修改 {@code OWNER} 角色自身的授权。 */
    private void requireOwnerForOwnerRole(long roleId, String message) {
        if ("OWNER".equals(mapper.selectRoleCode(roleId)) && !currentUserIsOwner()) {
            throw new BusinessException(ErrorCode.OWNER_PROTECTED, message);
        }
    }

    /**
     * 拦住「非站长把超级权限 {@code *:*:*} 授给别的角色」。
     *
     * <p>不拦的话，管理员可以通过「改一个自己控制的角色 + 勾上超级权限」把自己变成站长： 角色编辑这条路径绕过了 {@link #protectOwnerBinding}。所以超级权限的
     * **授予**本身也要限定为站长专属。
     */
    private void requireOwnerToGrantWildcard(List<String> normalized) {
        if (normalized.contains("*:*:*") && !currentUserIsOwner()) {
            throw new BusinessException(ErrorCode.OWNER_PROTECTED, "只有站长可以授予超级权限 *:*:*");
        }
    }

    /** 当前调用者是否持有 {@code OWNER} 角色。 */
    private boolean currentUserIsOwner() {
        Long ownerRoleId = mapper.selectOwnerRoleId();
        if (ownerRoleId == null) {
            return false;
        }
        return mapper.userHasRole(CurrentUser.requireId(), ownerRoleId) == 1;
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
