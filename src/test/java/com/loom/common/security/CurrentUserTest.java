package com.loom.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * {@link CurrentUser} 的单元测试。
 *
 * <p>这个类是「取当前用户」的唯一入口，auth 模块落地时只需要改它一个文件，所以它的行为 必须有测试锁住 —— 尤其是 {@code id()}
 * 目前**故意**返回空这件事：一旦有人误以为那是 bug 而「修」成抛异常，所有写操作的归属逻辑都会在无认证场景下炸掉。
 */
@DisplayName("当前用户")
class CurrentUserTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(String username) {
        Authentication auth = new UsernamePasswordAuthenticationToken(username, "n/a");
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    @DisplayName("已认证时返回用户名")
    void shouldReturnNameWhenAuthenticated() {
        authenticateAs("alice");

        assertThat(CurrentUser.name()).isEqualTo("alice");
    }

    @Test
    @DisplayName("未认证时用户名为 null，而不是抛异常")
    void shouldReturnNullNameWhenAnonymous() {
        SecurityContextHolder.clearContext();

        assertThat(CurrentUser.name()).isNull();
    }

    @Test
    @DisplayName("id() 目前恒为空 —— 这是有意的过渡状态")
    void shouldReturnEmptyIdUntilAuthModuleLands() {
        authenticateAs("alice");

        // 认证主体现在只有用户名，没有 sys_user.id 的来源。
        // 注意断言的是 empty 而不是异常：无认证场景下调用 id() 必须安全。
        assertThat(CurrentUser.id()).isEmpty();
    }

    @Test
    @DisplayName("未认证时调用 id() 同样安全")
    void shouldReturnEmptyIdWhenAnonymous() {
        SecurityContextHolder.clearContext();

        assertThat(CurrentUser.id()).isEmpty();
    }
}
