package com.loom.connection;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 把 {@link WithAuthorities} 声明的权限串变成一次**真实的**认证。
 *
 * <h2>为什么必须走请求级后处理器</h2>
 *
 * <p>试过两条更「干净」的路，都失败了：
 *
 * <ol>
 *   <li>{@code @WithMockUser} + 手工挂 {@code WithSecurityContextTestExecutionListener} ——
 *       注解完全不生效（Boot 4 不再加载 {@code spring.factories}）
 *   <li>直接往 {@code SecurityContextHolder} 放认证 —— 被 {@code SecurityContextHolderFilter}
 *       在请求开始时清掉，等于没放
 * </ol>
 *
 * <p>只有 {@code SecurityMockMvcRequestPostProcessors.user(...)} 这条路是通的：
 * 它在请求构造阶段就把认证挂上去，绕开了「仓库里没有」的问题。 这一点由诊断用例实测确认（显式后处理器 → 200，其余方式 → 401）。
 *
 * <p>这里用 {@link InvocationInterceptor} 在方法调用前把权限串放进 ThreadLocal， 测试再通过 {@link #auth()} 取用。没有偷偷改
 * MockMvc，是因为那样调用点会看不出 「认证到底从哪来」—— 权限测试最忌讳的就是看不清自己到底测了什么。
 */
final class AuthenticationExtension implements InvocationInterceptor {

    /** 当前用例应持有的权限串，由拦截器在方法调用前写入、调用后清除。 */
    private static final ThreadLocal<String[]> CURRENT = new ThreadLocal<>();

    @Override
    public void interceptTestMethod(
            Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> invocationContext,
            ExtensionContext extensionContext)
            throws Throwable {
        Method method = invocationContext.getExecutable();
        WithAuthorities annotation = method.getAnnotation(WithAuthorities.class);
        CURRENT.set(annotation == null ? null : annotation.value());
        try {
            invocation.proceed();
        } finally {
            CURRENT.remove();
        }
    }

    /**
     * 当前用例的认证后处理器。
     *
     * <p>没有声明 {@link WithAuthorities} 时返回匿名后处理器，使 「未认证应被拒」的用例可以显式写 {@code .with(auth())} ——
     * 把「我确实没带认证」变成代码里看得见的事实，而不是靠省略。
     */
    static RequestPostProcessor auth() {
        String[] authorities = CURRENT.get();
        if (authorities == null || authorities.length == 0) {
            return request -> {
                request.setUserPrincipal(null);
                return request;
            };
        }
        return user("tester")
                .authorities(Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
    }
}
