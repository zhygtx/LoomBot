package com.loom.common.security;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前调用者的统一入口。
 *
 * <h2>为什么单独抽一个类，而不是在 Controller 里直接读 SecurityContext</h2>
 *
 * <p>因为「用户名 → 用户 ID」的映射现在**还不存在**：auth 模块没落地，登录接口没有， {@code sys_user} 表里也还没有任何用户（V1 只种了角色和一条 {@code
 * *:*:*} 权限）。
 *
 * <p>于是「取当前用户 ID」这件事今天必然返回空。与其把这个空值散落到每个 Service 里 （半年后你会分不清哪些空值是「还没实现」、哪些是 bug），不如集中在这一个类里： auth
 * 模块落地时**只需要改这一个文件**。
 *
 * <p>{@link #name()} 是现在唯一真正可用的信息 —— 它来自 Spring Security 的 {@link Authentication}。
 */
public final class CurrentUser {

    private CurrentUser() {}

    /** 当前调用者的用户名；未认证时为 {@code null}。 */
    public static String name() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? null : authentication.getName();
    }

    /**
     * 当前调用者的用户 ID。
     *
     * <p><b>目前恒为 {@link Optional#empty()}。</b>不是忘了实现，而是没有数据来源： 认证主体现在只是一个用户名字符串，没有对应的 {@code
     * sys_user.id}。
     *
     * <p>TODO(auth): auth 模块落地后要做三件事，缺一不可：
     *
     * <ol>
     *   <li>登录接口签发 token，principal 换成携带 userId 的对象
     *   <li>本方法从 principal 里取出 ID（**只需改这一个文件**，这正是把它抽出来的目的）
     *   <li>迁移 {@code ws_connection.owner_user_id} 从 NULL 改回 NOT NULL，并回填已有数据的归属
     * </ol>
     *
     * <p>在那之前，{@code owner_user_id} 允许为 NULL —— 见 {@code V2__init_ws_connection.sql} 文件头的说明。
     */
    public static Optional<Long> id() {
        return Optional.empty();
    }
}
