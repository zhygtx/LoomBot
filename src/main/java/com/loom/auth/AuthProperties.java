package com.loom.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 认证模块的可调参数。
 *
 * <h2>为什么放在 {@code com.loom.auth} 而不是 {@code com.loom.config}</h2>
 *
 * <p>{@code auth} 的边界规则是「只允许依赖 {@code common}」。属性类如果放 {@code config}， 这个包就得反过来依赖 {@code
 * config}，规则立刻破掉。所以模块自己的参数跟着模块走 —— {@code AdapterProperties} 放在 {@code config} 是因为适配器配置本来就属于全局技术装配，
 * 与这里的判据不同。{@code @ConfigurationPropertiesScan} 扫的是整个 {@code com.loom}，放哪都会被注册。
 *
 * <h2>为什么用 record</h2>
 *
 * <p>它是构造器绑定（不可变、无 setter），不需要 Lombok；也避免了「字段 + getter + setter」 这种在别的项目里很常见、在本工程里只允许出现在
 * MyBatis-Plus 实体上的写法（见 D35）。
 *
 * <h2>密钥为什么在构造器里就校验</h2>
 *
 * <p>配置错了要在**启动时**炸，而不是等第一个用户登录时才报 500。这里做两件事： 拒绝空值、拒绝短于 32 字节的值（HS256 要求密钥不短于 256 位，否则 jjwt
 * 直接抛异常）。
 *
 * @param jwtSecret JWT 签名密钥（HMAC-SHA256），至少 32 字节
 * @param tokenTtl 令牌有效期，同时是 Redis 白名单条目的 TTL
 * @param emailCodeTtl 邮箱验证码有效期
 * @param emailCodeCooldown 同一邮箱同一场景的重发冷却时间
 * @param emailCodeMaxAttempts 同一验证码允许的连续错误次数，超过后必须重新获取
 * @param permissionCacheTtl 授权缓存（角色 / 权限串）的有效期
 * @param authenticationCacheTtl 每次请求校验所需用户状态快照的有效期
 * @param issuer 令牌签发方，写进 {@code iss} 声明
 */
@ConfigurationProperties(prefix = "loom.auth")
public record AuthProperties(
        String jwtSecret,
        @DefaultValue("24h") Duration tokenTtl,
        @DefaultValue("5m") Duration emailCodeTtl,
        @DefaultValue("60s") Duration emailCodeCooldown,
        @DefaultValue("5") int emailCodeMaxAttempts,
        @DefaultValue("30m") Duration permissionCacheTtl,
        @DefaultValue("5m") Duration authenticationCacheTtl,
        @DefaultValue("loom") String issuer) {

    /** HS256 = HMAC-SHA256，密钥短于 256 位时 jjwt 会拒绝用它签名。 */
    private static final int MIN_SECRET_BYTES = 32;

    public AuthProperties {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "loom.auth.jwt-secret 未配置：请设置环境变量 JWT_SECRET 后重新启动。"
                            + "令牌签名密钥不能没有默认值 —— 一个随机的默认值会让「配置漏了」"
                            + "表现为「每次重启所有人掉线」，那比启动失败更难发现。");
        }
        int length = jwtSecret.getBytes(StandardCharsets.UTF_8).length;
        if (length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "loom.auth.jwt-secret 太短：当前 "
                            + length
                            + " 字节，至少需要 "
                            + MIN_SECRET_BYTES
                            + " 字节。");
        }
        requirePositive(tokenTtl, "loom.auth.token-ttl");
        requirePositive(emailCodeTtl, "loom.auth.email-code-ttl");
        requirePositive(emailCodeCooldown, "loom.auth.email-code-cooldown");
        requirePositive(permissionCacheTtl, "loom.auth.permission-cache-ttl");
        requirePositive(authenticationCacheTtl, "loom.auth.authentication-cache-ttl");
        if (emailCodeMaxAttempts < 1) {
            throw new IllegalStateException(
                    "loom.auth.email-code-max-attempts 必须大于 0：0 会让验证码永远无法通过。");
        }
    }

    private static void requirePositive(Duration value, String key) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(key + " 必须是正的时间长度。");
        }
    }
}
