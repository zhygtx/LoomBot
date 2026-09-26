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
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * 登录令牌的签发、校验与吊销。
 *
 * <h2>为什么是「JWT + Redis 白名单」而不是纯无状态 JWT</h2>
 *
 * <p>纯无状态 JWT 有一个摆脱不掉的硬伤：**签出去就收不回来**。用户改了密码、用户被停用、 管理员点了「强制下线」，旧令牌在过期前依然有效 —— 而有效期通常以小时计。
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

    private static final String CLAIM_EMAIL = "email";

    private static final DefaultRedisScript<Long> ISSUE_SCRIPT =
            new DefaultRedisScript<>(
                    "redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[3]); "
                            + "redis.call('SADD', KEYS[2], ARGV[2]); "
                            + "redis.call('PEXPIRE', KEYS[2], ARGV[3]); return 1;",
                    Long.class);

    private static final DefaultRedisScript<Long> REVOKE_ALL_SCRIPT =
            new DefaultRedisScript<>(
                    "local ids=redis.call('SMEMBERS', KEYS[1]); "
                            + "for _,id in ipairs(ids) do redis.call('DEL', ARGV[1] .. id); end; "
                            + "redis.call('DEL', KEYS[1]); return #ids;",
                    Long.class);

    private static final DefaultRedisScript<Long> REVOKE_ONE_SCRIPT =
            new DefaultRedisScript<>(
                    "redis.call('DEL', KEYS[1]); redis.call('SREM', KEYS[2], ARGV[1]); return 1;",
                    Long.class);

    private final AuthProperties properties;
    private final StringRedisTemplate redis;
    private final UserService userService;
    private final SecretKey signingKey;

    public TokenService(
            AuthProperties properties, StringRedisTemplate redis, UserService userService) {
        this.properties = properties;
        this.redis = redis;
        this.userService = userService;
        this.signingKey =
                Keys.hmacShaKeyFor(properties.jwtSecret().getBytes(StandardCharsets.UTF_8));
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
                        .claim(CLAIM_EMAIL, user.getEmail())
                        .issuedAt(Date.from(now))
                        .expiration(Date.from(expiresAt))
                        .signWith(signingKey, Jwts.SIG.HS256)
                        .compact();

        String userTokensKey = KEY_USER_TOKENS + user.getId();
        redis.execute(
                ISSUE_SCRIPT,
                java.util.List.of(KEY_TOKEN + jti, userTokensKey),
                String.valueOf(user.getId()),
                jti,
                String.valueOf(properties.tokenTtl().toMillis()));

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
            Long id = Long.valueOf(userId);
            if (!String.valueOf(id).equals(claims.getSubject())) {
                return Optional.empty();
            }
            SysUser current = userService.findById(id).orElse(null);
            if (current == null || !current.enabled()) {
                redis.delete(KEY_TOKEN + jti);
                return Optional.empty();
            }
            return Optional.of(new AuthUser(id, current.getEmail()));
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
        String userId = claims.getSubject();
        if (userId == null || userId.isBlank()) {
            redis.delete(KEY_TOKEN + jti);
        } else {
            redis.execute(
                    REVOKE_ONE_SCRIPT,
                    java.util.List.of(KEY_TOKEN + jti, KEY_USER_TOKENS + userId),
                    jti);
        }
    }

    /**
     * 吊销某个用户的全部令牌。
     *
     * <p>改密码、停用用户、管理员强制下线都要走这里。只删白名单键是不够的 —— 那需要先知道有哪些 jti，所以签发时额外维护了「用户 → 全部 jti」的集合。
     *
     * <p>注意本方法不做「只保留当前这一个会话」的区分。改密码后要求所有端重新登录， 是刻意选的保守行为：用户改密码的动机往往就是「怀疑密码泄漏了」。
     */
    public void revokeAll(Long userId) {
        Long count =
                redis.execute(
                        REVOKE_ALL_SCRIPT, java.util.List.of(KEY_USER_TOKENS + userId), KEY_TOKEN);
        log.info("已吊销用户全部令牌: userId={}, 数量={}", userId, count == null ? 0 : count);
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
