package com.loom.system.service;

import com.loom.system.SystemCacheProperties;
import com.loom.system.dto.MenuResponse;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 当前用户侧边栏菜单读模型缓存。
 *
 * <p>菜单树由菜单定义、角色菜单关系和用户角色关系共同决定。关系变更时不扫描并删除 每个用户键，而是推进全局版本号；旧键随 TTL 自然消失。这样失效成本和在线用户数无关。
 */
@Component
public class NavigationCache {

    private static final Logger log = LoggerFactory.getLogger(NavigationCache.class);

    private static final String KEY_VERSION = "menu:navigation:v1:version";

    private static final String KEY_ENTRY = "menu:navigation:v1:";

    private static final TypeReference<List<MenuResponse>> MENU_LIST_TYPE =
            new TypeReference<>() {};

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final SystemCacheProperties properties;

    public NavigationCache(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            SystemCacheProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public List<MenuResponse> getOrLoad(long userId, Supplier<List<MenuResponse>> loader) {
        String key = null;
        try {
            key = entryKey(userId);
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return objectMapper.readValue(cached, MENU_LIST_TYPE);
            }
        } catch (RuntimeException e) {
            log.warn("读取侧边栏缓存失败，回退数据库: userId={}", userId, e);
        }

        List<MenuResponse> loaded = List.copyOf(loader.get());
        if (key == null) {
            return loaded;
        }
        try {
            redis.opsForValue()
                    .set(key, objectMapper.writeValueAsString(loaded), properties.navigationTtl());
        } catch (RuntimeException e) {
            log.warn("写入侧边栏缓存失败: userId={}", userId, e);
        }
        return loaded;
    }

    /** 菜单定义、角色菜单或用户角色变化后推进版本。 */
    public void invalidateAllAfterCommit() {
        afterCommit(this::invalidateAll);
    }

    private void invalidateAll() {
        try {
            redis.opsForValue().increment(KEY_VERSION);
        } catch (RuntimeException e) {
            log.warn("推进侧边栏缓存版本失败，旧缓存将等 TTL 自然过期", e);
        }
    }

    private String entryKey(long userId) {
        String version = redis.opsForValue().get(KEY_VERSION);
        return KEY_ENTRY + (version == null ? "0" : version) + ":user:" + userId;
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            action.run();
                        }
                    });
            return;
        }
        action.run();
    }
}
