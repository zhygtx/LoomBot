package com.loom.auth.service;

import com.loom.auth.AuthProperties;
import com.loom.auth.domain.SysUser;
import com.loom.common.security.AuthUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 登录令牌的签发、校验与吊销。
 *
 * <h2>为什么是「JWT + Redis 白名单」而不是纯无状态 JWT</h2>
 *
 * <p>纯无状态 JWT 有一个摆脱不掉的硬伤：**签出去就收不回来**。用户改了密码、账号被停用、 管理员点了「强制下线」，旧令牌在过期前依然有效 —— 而有效期通常以小时计。
 * 对「登录」这件事来说，能吊销比省一次 Redis 查询重要得多。
 *
 * <p>做法是给每个令牌一个 {@code jti}，签发时在 Redis 里写一条白名单记录； 校验时除了验签与过期，还要**确认白名单里还有这个 jti**。于是吊销 = 删 Redis 键。
 *
 * <p>代价是每个请求多一次 Redis 查询，以及 Redis 从「可选缓存」变成「运行时依赖」。 这个代价是明确接受了的：Redis 本来就已在技术栈里（见 docs/auth.md
 * 的决策记录）。
 *
 * <h2>两个键的分工</h2>
 *
 * <table border="1">
 *   <caption>Redis 键</caption>
 *   <tr><th>键</th><th>值</th><th>用途</th></tr>
 *   <tr><td>{@code auth:token:{jti}}</td><td>userId</td>
 *       <td>白名单。登出 / 改密码 / 强制下线时删它，令牌立刻失效</td></tr>
 *   <tr><td>{@code auth:user:tokens:{userId}}</td><td>该用户所有有效 jti 的集合</td>
 *       <td>「踢掉这个人的全部会话」需要反查，只有白名单键做不到</td></tr>
 * </table>
 *
 * <p>第二个键的 TTL 随每次登录顺延，所以它不会无限增长；里面的过期 jti 在 {@link #revokeAll} 时顺手清理，删不存在的键是无害的。
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    private static final String KEY_TOKEN = "auth:token:";
    private static final String KEY_USER_TOKENS = "auth:user:tokens:";

    private static final String CLAIM_ACCOUNT = "account";
    private static final String CLAIM_EMAIL = "email";

    /**
     * application.yml 里那个开发默认密钥的前缀。
     *
     * <p>本工程刻意**不给** {@code jwt-secret} 一个像样的默认值：一个随机的默认值会把
     * 「配置漏了」伪装成「每次重启所有人掉线」，比启动失败难查得多。开发默认值只存在于 被 .gitignore 排除的 application.yml 里，并且一旦被用上就在这里吼一声 ——
     * 生产环境忘记设 JWT_SECRET 时，这条 WARN 是唯一的提示。
     */
    private static final String DEV_SECRET_PREFIX = "loom-dev-";

    private final AuthProperties properties;
    private final StringRedisTemplate redis;
    private final SecretKey signingKey;

    public TokenService(AuthProperties properties, StringRedisTemplate redis) {
        this.properties = properties;
        this.redis = redis;
        this.signingKey =
                Keys.hmacShaKeyFor(properties.jwtSecret().getBytes(StandardCharsets.UTF_8));
        if (properties.jwtSecret().startsWith(DEV_SECRET_PREFIX)) {
            log.warn(
                    "正在使用开发默认的 JWT 密钥（loom.auth.jwt-secret 以 {} 开头）。"
                            + "生产环境必须通过环境变量 JWT_SECRET 覆盖它，否则任何人都能伪造令牌。",
                    DEV_SECRET_PREFIX);
        }
    }

    /** 签发结果。 */
    public record IssuedToken(String token, String jti, long expiresInSeconds) {}

    /** 签发令牌并写入白名单。 */
    public IssuedToken issue(SysUser user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.tokenTtl());
        // jti 用 UUID 而不是「userId + 时间戳」：后者可被预测，而白名单键一旦被猜到，
        // 攻击者就能用它来探测「某个令牌是否还有效」。
        String jti = UUID.randomUUID().toString().replace("-", "");

        String token =
                Jwts.builder()
                        .issuer(properties.issuer())
                        .subject(String.valueOf(user.getId()))
                        .id(jti)
                        .claim(CLAIM_ACCOUNT, user.getAccount())
                        .claim(CLAIM_EMAIL, user.getEmail())
                        .issuedAt(Date.from(now))
                        .expiration(Date.from(expiresAt))
                        .signWith(signingKey, Jwts.SIG.HS256)
                        .compact();

        redis.opsForValue()
                .set(KEY_TOKEN + jti, String.valueOf(user.getId()), properties.tokenTtl());
        String userTokensKey = KEY_USER_TOKENS + user.getId();
        redis.opsForSet().add(userTokensKey, jti);
        redis.expire(userTokensKey, properties.tokenTtl());

        return new IssuedToken(token, jti, properties.tokenTtl().toSeconds());
    }

    /**
     * 校验令牌并取出调用者身份。
     *
     * <p>三道关：签名与有效期（jjwt 负责）、签发方（jjwt 负责）、**白名单**（本方法负责）。 任何一道不过都返回 {@link Optional#empty()} ——
     * 对调用方来说「这个令牌不能用」 是同一种结果，区分具体原因只对攻击者有意义。
     */
    public Optional<AuthUser> authenticate(String token) {
        Claims claims = parse(token).orElse(null);
        if (claims == null) {
            return Optional.empty();
        }
        String jti = claims.getId();
        if (jti == null || jti.isBlank()) {
            return Optional.empty();
        }
        String userId = redis.opsForValue().get(KEY_TOKEN + jti);
        if (userId == null) {
            // 已登出 / 已改密 / 已被强制下线 / 白名单自己过期
            return Optional.empty();
        }
        try {
            return Optional.of(
                    new AuthUser(
                            Long.valueOf(userId),
                            claims.get(CLAIM_ACCOUNT, String.class),
                            claims.get(CLAIM_EMAIL, String.class)));
        } catch (NumberFormatException e) {
            log.warn("白名单里的 userId 不是数字，令牌作废: jti={}", jti);
            return Optional.empty();
        }
    }

    /**
     * 吊销单个令牌（登出）。
     *
     * <p>令牌非法时不报错，只记 DEBUG：登出是幂等的，「登出一个已经失效的令牌」 不是错误，不该让用户看到一句红字。
     */
    public void revoke(String token) {
        Claims claims = parse(token).orElse(null);
        if (claims == null) {
            return;
        }
        String jti = claims.getId();
        if (jti == null || jti.isBlank()) {
            return;
        }
        redis.delete(KEY_TOKEN + jti);
        String userId = claims.getSubject();
        if (userId != null) {
            redis.opsForSet().remove(KEY_USER_TOKENS + userId, jti);
        }
    }

    /**
     * 吊销某个用户的全部令牌。
     *
     * <p>改密码、停用账号、管理员强制下线都要走这里。只删白名单键是不够的 —— 那需要先知道有哪些 jti，所以签发时额外维护了「用户 → 全部 jti」的集合。
     *
     * <p>注意本方法不做「只保留当前这一个会话」的区分。改密码后要求所有端重新登录， 是刻意选的保守行为：用户改密码的动机往往就是「怀疑密码泄漏了」。
     */
    public void revokeAll(Long userId) {
        Set<String> jtis = redis.opsForSet().members(KEY_USER_TOKENS + userId);
        if (jtis != null && !jtis.isEmpty()) {
            List<String> keys = jtis.stream().map(jti -> KEY_TOKEN + jti).toList();
            redis.delete(keys);
            log.info("已吊销用户全部令牌: userId={}, 数量={}", userId, keys.size());
        }
        redis.delete(KEY_USER_TOKENS + userId);
    }

    /** 只验签与有效期，**不查白名单** —— 吊销场景需要能解析出一个已失效的令牌。 */
    private Optional<Claims> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(
                    Jwts.parser()
                            .verifyWith(signingKey)
                            .requireIssuer(properties.issuer())
                            .build()
                            .parseSignedClaims(token)
                            .getPayload());
        } catch (JwtException | IllegalArgumentException e) {
            // 不记 WARN：扫描器与过期令牌会让这里刷屏，而它对排查没有信息量
            log.debug("令牌解析失败: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
