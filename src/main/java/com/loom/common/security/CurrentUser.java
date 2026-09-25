package com.loom.common.security;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前调用者的统一入口。
 *
 * <h2>为什么单独抽一个类，而不是在 Controller 里直接读 SecurityContext</h2>
 *
 * <p>因为「谁在调」这件事有两个来源、一个形状：来源是 JWT 过滤器塞进来的 {@link AuthUser}， 形状是「可能没有」（未认证的匿名请求）。把这个空值散落到每个 Service
 * 里， 半年后你会分不清哪些空值是「本就不该有」、哪些是 bug。集中在这里，判据只有一处。
 *
 * <h2>之前为什么恒为空，现在为什么不是</h2>
 *
 * <p>本类曾经明写「{@link #id()} 恒为 {@link Optional#empty()}」—— 那时 auth 模块没落地， 认证主体只是一个用户名字符串，没有对应 {@code
 * sys_user.id}。现在 JWT 过滤器把 {@link AuthUser} 放进 {@code SecurityContext}，ID 就有了真实来源。
 *
 * <p>{@code ws_connection.owner_user_id} 因此也具备了从 NULL 改回 NOT NULL 的条件 （见 {@code
 * V2__init_ws_connection.sql} 文件头）；迁移本身不在本次变更范围内。
 */
public final class CurrentUser {

    private CurrentUser() {}

    /**
     * 当前调用者；未认证时为 {@link Optional#empty()}。
     *
     * <p>匿名请求也会有一个 {@code Authentication}（内容是字符串 {@code anonymousUser}）， 所以这里用 principal
     * 的**类型**判断，而不是「Authentication 是否为 null」—— 后者在匿名请求上会误判为已认证。
     */
    public static Optional<AuthUser> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthUser user)) {
            return Optional.empty();
        }
        return Optional.of(user);
    }

    /** 当前调用者的用户 ID；未认证时为 {@link Optional#empty()}。 */
    public static Optional<Long> id() {
        return current().map(AuthUser::id);
    }

    /**
     * 当前调用者的账号；未认证时为 {@code null}。
     *
     * <p>注意它返回的是**账号**，不是「用户名」—— 见 V3 迁移与 docs/auth.md 关于这次改名的说明：本项目没有用户间的交流，所以不存在「展示名」这一层，
     * 这一列只能是账号。
     */
    public static String account() {
        return current().map(AuthUser::account).orElse(null);
    }
}
