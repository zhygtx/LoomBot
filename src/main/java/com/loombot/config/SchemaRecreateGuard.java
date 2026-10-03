package com.loombot.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 开发期的「启动时清库重建」闸门与执行者。
 *
 * <h2>为什么要显式 clean，而不是让 Flyway 自己来</h2>
 *
 * <p>第一版写成 {@code EnvironmentPostProcessor}、靠改写 {@code spring.flyway.clean-on-validation-error} 让
 * Flyway 自己去 clean。那条路走不通，实测报的是：
 *
 * <pre>
 * Validate failed: Migrations have failed validation
 * Migration checksum mismatch for migration version 1
 * </pre>
 *
 * <p>原因是 {@code validate-on-migrate} 默认开启，而校验发生在 clean 之前 —— 校验一失败， migrate 流程（连同它的 clean
 * 分支）根本不会开始。而且 {@code clean-on-validation-error} 在 Boot 4 的 Flyway 自动配置里也不是一个能生效的属性。
 *
 * <p>所以改成显式调用 Flyway 的 clean：这里拿到 {@link DataSource}，用**同一份迁移配置**建一个 {@link Flyway} 实例、先 {@code
 * clean()} 把库清空。之后 Boot 自己的自动配置照常跑 validate + migrate， 面对一个空库不会再有校验和不匹配的问题。
 *
 * <h2>为什么是 BeanFactoryPostProcessor</h2>
 *
 * <p>它比普通 Bean 早、又比 {@code EnvironmentPostProcessor} 晚 —— 这个位置刚好能拿到已经绑好的 {@link
 * DataSourceProperties}（所以知道连的是哪台主机），又赶在 {@code flywayInitializer} 建出来之前把库 清掉。写成普通 Bean
 * 就太晚了：那时迁移已经跑完。
 *
 * <h2>闸门</h2>
 *
 * <p>只有连到**本机数据库**时才允许重建。指向别的主机时：
 *
 * <ul>
 *   <li>重建开关是关的 → 什么都不做，正常启动（生产就是这么跑的）；
 *   <li>重建开关是开的 → <b>直接抛异常拒绝启动</b>。不选择「悄悄跳过重建继续跑」，因为那会让你 以为表是新的、实际跑在旧结构上，那种错更难查。
 * </ul>
 */
@Component
public class SchemaRecreateGuard implements BeanFactoryPostProcessor, EnvironmentAware, Ordered {

    private static final Logger log = LoggerFactory.getLogger(SchemaRecreateGuard.class);

    private static final String PROP_ENABLED = "loombot.datasource.recreate.enabled";
    private static final String PROP_ALLOWED_HOSTS = "loombot.datasource.recreate.allowed-hosts";
    private static final String DEFAULT_ALLOWED_HOSTS = "localhost,127.0.0.1,::1,[::1]";

    private Environment environment;

    /**
     * 无参构造是必须的：{@code BeanFactoryPostProcessor} 实例在容器刷新的极早期就被创建， 那时普通 Bean 的后置处理还没装配好，构造器注入拿不到
     * {@link Environment}（实测报 {@code No default constructor found}）。所以由 Spring 通过 {@link
     * org.springframework.context.EnvironmentAware} 回调注入。
     */
    public SchemaRecreateGuard() {}

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
            throws BeansException {
        if (environment == null || !environment.getProperty(PROP_ENABLED, Boolean.class, false)) {
            return;
        }

        String url = environment.getProperty("spring.datasource.url", "");
        String host = hostOf(url);
        if (host == null) {
            host = environment.getProperty("DB_HOST", "localhost");
        }

        String allowed = environment.getProperty(PROP_ALLOWED_HOSTS, DEFAULT_ALLOWED_HOSTS);
        if (!isAllowed(host, allowed)) {
            throw new IllegalStateException(
                    "拒绝启动："
                            + PROP_ENABLED
                            + "=true 会在启动时清空数据库，但当前数据库主机是「"
                            + host
                            + "」，不在允许重建的白名单 ["
                            + allowed
                            + "] 里。如果这确实是要重建的本机库，把主机加进 "
                            + PROP_ALLOWED_HOSTS
                            + "；否则关掉 "
                            + PROP_ENABLED
                            + "。");
        }

        log.warn(
                "loombot.datasource.recreate.enabled=true：正在清空数据库 {} 并重建"
                        + "（表结构以 V1__bootstrap_schema.sql 为准）。这是开发期开关，部署时必须关掉。",
                host);

        try {
            // locations 与 application.yml 的 spring.flyway.locations 保持一致；
            // 这里只借 Flyway 的 clean，迁移本身仍由 Boot 的自动配置执行（clean 之后库是空的，
            // 它会重新跑一遍 V1）。
            String locations =
                    environment.getProperty("spring.flyway.locations", "classpath:db/migration");
            try (HikariDataSource dataSource = dataSource()) {
                Flyway.configure()
                        .dataSource(dataSource)
                        .locations(locations.split(","))
                        // Flyway 10 起 cleanDisabled 默认为 true，所以这个**专用于清库的临时实例**
                        // 必须显式放开。注意它和容器里那个负责正常迁移的 Flyway
                        // （application.yml 里 clean-disabled: true）是两个独立对象，
                        // 放开这个不会让其他任何路径获得清库能力。
                        .cleanDisabled(false)
                        .load()
                        .clean();
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "清空数据库失败（" + PROP_ENABLED + "=true）。如果不想清库，把该开关设为 false 再启动。", e);
        }
    }

    /**
     * 自己拼一个只用于 clean 的数据源。
     *
     * <p>不从容器里取 {@code DataSource}：这个时机（BeanFactoryPostProcessor）它还没被创建， 而且一旦取它就等于提前触发整个数据源初始化，会绕开
     * Boot 的自动配置流程。这里直接用 环境里的连接参数建一个短命的连接池，用完就关 —— 它的唯一用途是执行一次 {@code DROP ALL}。
     */
    private HikariDataSource dataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(environment.getProperty("spring.datasource.url"));
        dataSource.setUsername(environment.getProperty("spring.datasource.username"));
        dataSource.setPassword(environment.getProperty("spring.datasource.password"));
        dataSource.setDriverClassName(
                environment.getProperty(
                        "spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver"));
        // 清库只需要一条连接，池子开到最小
        dataSource.setMaximumPoolSize(1);
        dataSource.setPoolName("schema-recreate-clean");
        return dataSource;
    }

    /** 从 JDBC URL 里抠出主机名。形如 {@code jdbc:mysql://host:port/db?params}。 */
    static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        int start = url.indexOf("//");
        if (start < 0) {
            return null;
        }
        String rest = url.substring(start + 2);
        int end = rest.length();
        for (char stop : new char[] {'/', '?', ':'}) {
            int index = rest.indexOf(stop);
            if (index >= 0 && index < end) {
                end = index;
            }
        }
        String host = rest.substring(0, end).strip();
        return host.isEmpty() ? null : host;
    }

    static boolean isAllowed(String host, String allowedHosts) {
        if (host == null) {
            return false;
        }
        String normalized = host.strip().toLowerCase(java.util.Locale.ROOT);
        for (String candidate : allowedHosts.split(",")) {
            if (candidate.strip().toLowerCase(java.util.Locale.ROOT).equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getOrder() {
        // 尽量靠前：必须赶在 flywayInitializer 建出来之前把库清空
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
