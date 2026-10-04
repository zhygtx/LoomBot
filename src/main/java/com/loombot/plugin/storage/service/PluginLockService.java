package com.loombot.plugin.storage.service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * 插件侧的跨进程锁。
 *
 * <p>只做一件事：给"初始化 / 迁移 / 去重"这类短临界区一个跨进程的互斥点。它刻意不承担数据存储职责， 所以 Redis 不可用时插件仍能正常读写状态，只是拿不到锁 ——
 * 影响面被限制在一个 API 上。
 *
 * <h2>为什么释放要跑 Lua</h2>
 *
 * <p>"先 GET 判断是不是自己的 token，再 DEL"在两步之间有窗口：如果锁刚好在这时自然过期， 另一个进程拿到新锁，前一个进程的 DEL 就会把别人的锁删掉。Lua 脚本在
 * Redis 里原子执行， 判断和删除之间不会被打断。
 *
 * <h2>为什么等待循环在调用方</h2>
 *
 * <p>这里的 acquire 是单次尝试（{@code SET NX PX}），不阻塞。等待重试由 Python 侧的 {@code async with state.lock(...)} 完成
 * —— 那样等待期间不会占着 Tomcat 的工作线程。
 */
@Service
public class PluginLockService {

    /** 键前缀里带 {@code plugin-storage}，与鉴权、工作流等其他 Redis 用途区分开。 */
    private static final String KEY_PREFIX = "loombot:plugin-storage:lock:";

    /** 默认锁有效期。取一个"足够覆盖初始化、又不至于让崩溃的进程永久占锁"的值。 */
    public static final long DEFAULT_TTL_MILLIS = 30_000L;

    /** 上限：防止调用方传一个"相当于永不过期"的 TTL，把锁变成需要人工清理的残留。 */
    private static final long MAX_TTL_MILLIS = 10 * 60_000L;

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('GET', KEYS[1]) == ARGV[1] "
                            + "then return redis.call('DEL', KEYS[1]) else return 0 end",
                    Long.class);

    private final StringRedisTemplate redis;

    public PluginLockService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 单次尝试获取锁。
     *
     * @return 持有凭证；没抢到返回 {@code null}
     */
    public String tryAcquire(String lockKey, Long ttlMillis) {
        long ttl = ttlMillis == null || ttlMillis <= 0 ? DEFAULT_TTL_MILLIS : ttlMillis;
        ttl = Math.min(ttl, MAX_TTL_MILLIS);
        String token = UUID.randomUUID().toString().replace("-", "");
        Boolean acquired =
                redis.opsForValue().setIfAbsent(redisKey(lockKey), token, Duration.ofMillis(ttl));
        return Boolean.TRUE.equals(acquired) ? token : null;
    }

    /** 释放锁。token 不匹配时不删 —— 那说明锁已经过期并被别人持有。 */
    public boolean release(String lockKey, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        Long removed = redis.execute(RELEASE_SCRIPT, List.of(redisKey(lockKey)), token);
        return removed != null && removed > 0;
    }

    private static String redisKey(String lockKey) {
        return KEY_PREFIX + lockKey;
    }
}
