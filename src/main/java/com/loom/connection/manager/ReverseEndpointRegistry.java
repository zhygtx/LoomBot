package com.loom.connection.manager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 反向端点注册表 —— {@code endpointPath → connectionId}。
 *
 * <p>**纯内存**，不持久化：路径唯一性已经由数据库的 {@code uk_endpoint_path} 唯一索引保证， 这里只是启动时从库里读出来重建的运行时视图。
 *
 * <h2>为什么是单一路由而不是动态注册 handler</h2>
 *
 * <p>Spring 的 {@code WebSocketHandlerRegistry} 只在启动时配置，运行时没法往里加 handler。 所以只注册**一个通配
 * handler**（{@code /ws/**}），它拿到请求后到这里查表决定归属。 这样既没跟框架较劲，也让「注册端点」变成一次简单的 map put。
 */
@Component
public class ReverseEndpointRegistry {

    private static final Logger log = LoggerFactory.getLogger(ReverseEndpointRegistry.class);

    private final Map<String, Long> connectionIdByPath = new ConcurrentHashMap<>();

    /**
     * 注册端点。
     *
     * @return {@code true} 表示注册成功；{@code false} 表示路径已被**另一个**连接占用
     */
    public boolean register(String path, long connectionId) {
        if (path == null || path.isBlank()) {
            return false;
        }
        Long existing = connectionIdByPath.putIfAbsent(path, connectionId);
        if (existing == null || existing == connectionId) {
            return true;
        }
        log.error("端点路径冲突: {} 已被连接 {} 占用，连接 {} 注册失败", path, existing, connectionId);
        return false;
    }

    public void unregister(String path, long connectionId) {
        if (path != null) {
            connectionIdByPath.remove(path, connectionId);
        }
    }

    public Long connectionIdOf(String path) {
        return path == null ? null : connectionIdByPath.get(path);
    }

    public void clear() {
        connectionIdByPath.clear();
    }

    public int size() {
        return connectionIdByPath.size();
    }
}
