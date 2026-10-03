package com.loombot.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 装配。
 *
 * <h2>为什么分页拦截器必须显式注册</h2>
 *
 * <p>不注册 {@link PaginationInnerInterceptor} 时，{@code selectPage(...)} <b>不会报错</b> ——
 * 它会照常执行、把整张表捞回来，并把 {@code total} 留成 0。前端拿到的表现是 「第一页显示了全部数据，且总数为 0、没有下一页」。这个 bug 不会在测试里自己冒出来，
 * 只有数据量大了以后才暴露，所以拦截器是必须项而不是优化项。
 *
 * <h2>为什么还要设 maxLimit</h2>
 *
 * <p>Controller 已经挡了 {@code pageSize > 200}，但那是 HTTP 层的防线。Service 或 以后新增的调用方可能绕过它。{@code maxLimit}
 * 是最后一道闸：无论谁传了多大的 {@code size}，SQL 里的 {@code LIMIT} 都会被压到这个值。分层设防在这里是划算的 —— 一次全表捞取就足以把服务打挂。
 */
@Configuration
public class MybatisPlusConfig {

    /** 分页硬上限，兼作 Controller 校验之外的第二道防线。 */
    private static final long MAX_PAGE_SIZE = 200L;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(MAX_PAGE_SIZE);
        // 请求页码超过总页数时返回空列表，而不是回到第一页 —— 后者会让「翻到底了」
        // 表现为「无限循环在第一页」，前端很难发现
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
