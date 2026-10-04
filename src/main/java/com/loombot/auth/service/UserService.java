package com.loombot.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.loombot.auth.AuthProperties;
import com.loombot.auth.domain.SysUser;
import com.loombot.auth.dto.UserProfileResponse;
import com.loombot.auth.mapper.SysUserMapper;
import com.loombot.common.api.ErrorCode;
import com.loombot.common.exception.BusinessException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 用户持久化与授权加载。
 *
 * <h2>本模块为什么自己持有 {@code sys_user} 的 Mapper</h2>
 *
 * <p>{@code auth} 的边界规则是「只允许依赖 {@code common}」，而认证必须读 {@code sys_user}。 两条同时成立，只有一个解：**身份相关的持久化归
 * auth**。{@code system} 模块的 package-info 里写的是「五张表被两者共用」，那是当前单体的既成事实，不是一条能靠 「谁 import 谁」实现的规则 ——
 * 与其让规则和代码对不上，不如把归属写清楚：
 *
 * <ul>
 *   <li>身份与授权读取（{@code sys_user} / {@code sys_user_role}）→ 本模块
 *   <li>角色与权限的**定义与维护**（{@code sys_role} / {@code sys_permission} / {@code sys_role_permission}）→
 *       将来归 {@code system}
 * </ul>
 *
 * <p>将来 {@code system} 要做用户管理界面时需要用户数据的检索能力，届时它应当依赖 本模块暴露的**接口**，而不是直接引用 {@link
 * SysUser}（直接引用实体是拆分时最大的阻碍）。
 *
 * <h2>密码</h2>
 *
 * <p>只以 BCrypt 哈希形态进出本类。{@link #updatePassword} 收到的明文**用完即弃**， 不写日志、不放进异常消息。
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /**
     * 授权缓存键。版本前缀用于角色模型发生不兼容变化时整体废弃旧缓存。
     *
     * <h2>为什么用单个字符串键存集合，而不是 Redis Set</h2>
     *
     * <p>因为要区分「没缓存」和「缓存了空集合」，而这两件事对刚注册的用户都会发生： 他有角色但（按 V1 的种子数据）没有任何权限点。用 Set 结构时，成员为空与键不存在 在
     * {@code members()} 上返回同一个结果 —— 于是「空权限」永远命中不了缓存， 每个普通用户的**每个请求**都要回一次库，正好是最常见的那条路径。
     *
     * <p>字符串键则天然区分：{@code GET} 返回 {@code null} = 未缓存，返回 {@code ""} = 缓存了空集合。 权限串的字符集是 {@code
     * 域:资源:操作} 加通配 {@code *}，不含逗号，可以安全地用逗号连接。
     */
    private static final String KEY_PERMISSIONS = "perm:v2:user:";

    private static final String KEY_ROLES = "role:v2:user:";

    /**
     * 令牌校验所需的用户状态快照。
     *
     * <p>过滤器只关心 {@code email + enabled}，却在升级前每个请求回查一次完整的 {@code sys_user}。
     * 这个键把高频状态读取降到用户级一次；用户停用或改密时由显式失效立即删除。
     */
    private static final String KEY_AUTH_STATE = "auth:state:v1:";

    private static final String KEY_AUTH_STATE_VERSION = "auth:state:v1:version:user:";

    private static final String CACHE_SEPARATOR = ",";

    private final SysUserMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redis;
    private final AuthProperties properties;

    public UserService(
            SysUserMapper mapper,
            PasswordEncoder passwordEncoder,
            StringRedisTemplate redis,
            AuthProperties properties) {
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
        this.redis = redis;
        this.properties = properties;
    }

    // ==================================================================
    // 注册
    // ==================================================================

    /**
     * 落库一个新用户并绑定默认角色。
     *
     * <p>先做一次友好的邮箱重复检查，但仍然捕获 {@link DuplicateKeyException} —— 两个请求同时通过检查时，
     * 唯一索引是最后一道防线，而这道防线的报错必须被翻译成人话。
     *
     * @return 新用户（{@code id} 已由 {@code id-type: assign_id} 回填）
     */
    @Transactional
    public SysUser register(String email, String rawPassword) {
        email = normalizeEmail(email);
        if (existsByEmail(email)) {
            throw new BusinessException(ErrorCode.EMAIL_EXISTS);
        }

        SysUser user = new SysUser();
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setStatus(SysUser.STATUS_ENABLED);
        try {
            mapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 落到这里说明并发通过了上面的检查 —— 唯一索引是最后一道防线。
            // 再查一次是为了把错误说准，而不是把数据库异常暴露给调用方。
            log.warn("注册时邮箱唯一索引冲突: email={}", email);
            if (existsByEmail(email)) {
                throw new BusinessException(ErrorCode.EMAIL_EXISTS);
            }
            // 都查不到说明冲突来自并发删除或手工改库，仍返回统一的邮箱冲突错误。
            throw new BusinessException(ErrorCode.EMAIL_EXISTS);
        }

        bindDefaultRole(user.getId());
        // 新用户的授权缓存要清掉：注册前的失败尝试可能已经把「空权限」写进缓存，
        // 不清的话这个用户在 TTL 内会一直是「没有角色」。
        evictAuthorizationCache(user.getId());
        log.info("新用户注册: id={}, email={}", user.getId(), email);
        return user;
    }

    /**
     * 绑定注册默认角色。角色 code 来自 {@code loombot.auth.registration-default-role-code}： dev profile 是 {@code
     * OWNER}（本机测试要能进管理端），prod profile 是 {@code USER}（普通用户）。
     *
     * <p>取不到角色时**必须报错**，不能默默跳过：那样用户能登录、却没有任何角色， 表现为「功能全是 403」，而根因（种子数据缺失）在日志里一个字都没有。
     */
    private void bindDefaultRole(Long userId) {
        String roleCode = properties.registrationDefaultRoleCode();
        Long roleId = mapper.selectRoleIdByCode(roleCode);
        if (roleId == null) {
            log.error(
                    "默认角色 {} 不存在，注册无法绑定角色。请检查 V1 迁移的种子数据，或 loombot.auth.registration-default-role-code 配置。",
                    roleCode);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        mapper.insertUserRole(userId, roleId);
    }

    // ==================================================================
    // 查询
    // ==================================================================

    public Optional<SysUser> findByEmail(String email) {
        return Optional.ofNullable(
                mapper.selectOne(
                        Wrappers.<SysUser>lambdaQuery()
                                .eq(SysUser::getEmail, normalizeEmail(email))));
    }

    public Optional<SysUser> findById(Long id) {
        return Optional.ofNullable(mapper.selectById(id));
    }

    /** 认证过滤器使用的轻量用户状态；Redis 未命中时只查询 id/email/status。 */
    public Optional<AuthenticationState> findAuthenticationState(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        String key = null;
        try {
            key = authenticationStateKey(id);
            String cached = redis.opsForValue().get(key);
            AuthenticationState parsed = parseAuthenticationState(id, key, cached);
            if (parsed != null) {
                return Optional.of(parsed);
            }
        } catch (RuntimeException e) {
            log.warn("读取用户认证快照失败，回退数据库: userId={}", id, e);
        }

        SysUser user = mapper.selectAuthenticationStateById(id);
        if (user == null) {
            return Optional.empty();
        }
        AuthenticationState state =
                new AuthenticationState(user.getId(), user.getEmail(), user.enabled());
        try {
            redis.opsForValue()
                    .set(
                            key,
                            (state.enabled() ? "1" : "0") + ":" + state.email(),
                            properties.authenticationCacheTtl());
        } catch (RuntimeException e) {
            log.warn("写入用户认证快照失败: userId={}", id, e);
        }
        return Optional.of(state);
    }

    public boolean existsByEmail(String email) {
        return mapper.exists(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getEmail, normalizeEmail(email)));
    }

    // ==================================================================
    // 修改
    // ==================================================================

    /** 改密码。调用方负责在改完之后吊销该用户的所有令牌。 */
    @Transactional
    public void updatePassword(Long userId, String rawPassword) {
        SysUser update = new SysUser();
        update.setId(userId);
        update.setPassword(passwordEncoder.encode(rawPassword));
        if (mapper.updateById(update) != 1) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        evictAuthenticationStateAfterCommit(userId);
        log.info("用户密码已重置: id={}", userId);
    }

    /**
     * 记录本次登录的时间与来源 IP。
     *
     * <p>刻意不做成「登录失败也要记」—— 那需要一张登录流水表，而它的价值要到「排查异常登录」时才能兑现。当前先只记最后一次成功登录。
     */
    public void recordLoginSuccess(Long userId, String ip) {
        SysUser update = new SysUser();
        update.setId(userId);
        update.setLastLoginTime(LocalDateTime.now());
        update.setLastLoginIp(ip);
        mapper.updateById(update);
    }

    // ==================================================================
    // 授权加载
    // ==================================================================

    /** 用户的 Spring Security 权限集合：角色加 {@code ROLE_} 前缀，权限串原样。 */
    public List<GrantedAuthority> authorities(Long userId) {
        List<GrantedAuthority> result = new ArrayList<>();
        for (String role : roleCodes(userId)) {
            result.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
        for (String permission : permissionStrings(userId)) {
            result.add(new SimpleGrantedAuthority(permission));
        }
        return List.copyOf(result);
    }

    public List<String> roleCodes(Long userId) {
        return cached(userId, KEY_ROLES, () -> mapper.selectRoleCodes(userId));
    }

    public List<String> permissionStrings(Long userId) {
        return cached(userId, KEY_PERMISSIONS, () -> mapper.selectPermissionStrings(userId));
    }

    public java.util.Set<Long> menuIds(Long userId) {
        return new java.util.HashSet<>(mapper.selectMenuIds(userId));
    }

    /**
     * 清掉某个用户的授权缓存。
     *
     * <p>这是 docs/auth.md 里「主动失效」那一项的落点：改角色 / 改权限之后必须调用它， 否则权限变更要等到 TTL 到期才生效。
     *
     * <p>后端权限启停接口已经调用本方法；后续角色绑定管理也必须复用它。否则授权变更要等缓存 TTL 到期， 表现会变成「管理端明明改了权限，为什么用户仍然能访问」。
     */
    public void evictAuthorizationCache(Long userId) {
        redis.delete(List.of(KEY_PERMISSIONS + userId, KEY_ROLES + userId));
    }

    public void evictUsersWithRole(long roleId) {
        mapper.selectUserIdsByRole(roleId).forEach(this::evictAuthorizationCache);
    }

    /** 用户停用/启用后清掉认证状态快照，提交后再清以避免被并发请求回填旧值。 */
    public void evictAuthenticationStateAfterCommit(Long userId) {
        if (userId == null) {
            return;
        }
        afterCommit(
                () -> {
                    try {
                        redis.opsForValue().increment(KEY_AUTH_STATE_VERSION + userId);
                    } catch (RuntimeException e) {
                        log.warn("推进用户认证快照版本失败，旧快照将等 TTL 自然过期: userId={}", userId, e);
                    }
                });
    }

    /**
     * 清掉**所有**用户的授权缓存。
     *
     * <p>只在一种场景下用：删除一条权限**定义**。它可能被任意多个角色引用，而删除动作发生前 关联行就已经被清掉了 —— 那时再想反查「哪些用户受影响」已经查不到，只能全量清。
     *
     * <p>这是一条**刻意保守**的路径，代价说清楚：所有用户的授权缓存被清空，下一次请求各自回库 重新加载一次权限。项目当前是单机、用户量小，这个代价远小于「删了权限但旧缓存里还留着」——
     * 后者表现为「接口已经没人该有权调了，却还能调通」，直到 TTL 到期为止。
     *
     * <p>没有做成「扫 Redis 的 perm:/role: 前缀」是因为那两个键名是本类的实现细节， 加一个 SCAN
     * 会让别的模块也能伸手进来改缓存布局。这里显式走一遍用户表，边界更清楚。
     */
    public void evictAllAuthorizationCaches() {
        mapper.selectList(Wrappers.<SysUser>lambdaQuery().select(SysUser::getId))
                .forEach(user -> evictAuthorizationCache(user.getId()));
    }

    private List<String> cached(Long userId, String keyPrefix, Supplier<List<String>> loader) {
        String key = keyPrefix + userId;
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            return cached.isEmpty() ? List.of() : List.of(cached.split(CACHE_SEPARATOR));
        }
        List<String> loaded = loader.get();
        redis.opsForValue()
                .set(key, String.join(CACHE_SEPARATOR, loaded), properties.permissionCacheTtl());
        return loaded;
    }

    private AuthenticationState parseAuthenticationState(Long userId, String key, String cached) {
        if (cached == null) {
            return null;
        }
        int separator = cached.indexOf(':');
        if (separator != 1 || cached.length() < 2) {
            redis.delete(key);
            return null;
        }
        String marker = cached.substring(0, 1);
        if (!"0".equals(marker) && !"1".equals(marker)) {
            redis.delete(key);
            return null;
        }
        return new AuthenticationState(userId, cached.substring(2), "1".equals(marker));
    }

    private String authenticationStateKey(Long userId) {
        String version = redis.opsForValue().get(KEY_AUTH_STATE_VERSION + userId);
        return KEY_AUTH_STATE + (version == null ? "0" : version) + ":user:" + userId;
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            action.run();
                        }
                    });
            return;
        }
        action.run();
    }

    // ==================================================================
    // 视图映射
    // ==================================================================

    /** 组装对外的用户信息。{@code password} 不在其中，将来也不会在。 */
    public UserProfileResponse profile(SysUser user) {
        return new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getStatus(),
                roleCodes(user.getId()),
                permissionStrings(user.getId()),
                user.getLastLoginTime(),
                user.getCreateTime());
    }

    public static String normalizeEmail(String email) {
        return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }

    /** 供认证层复用，不包含密码或审计信息。 */
    public record AuthenticationState(Long id, String email, boolean enabled) {}
}
