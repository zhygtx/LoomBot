package com.loom.connection.handshake;

import java.util.Map;

/**
 * 握手请求的抽象视图。
 *
 * <p>刻意不直接依赖 servlet / Spring 类型 —— 让校验器是纯函数，好测也好复用 （反向 WS 的握手在 WebSocket 升级阶段，拿不到常规的 Controller
 * 上下文）。
 *
 * @param path 请求路径，HMAC 签名会用到
 * @param headers 请求头（大小写不敏感的查找由此封装）
 * @param queryParams URL 查询参数
 */
public record HandshakeRequest(
        String path, Map<String, String> headers, Map<String, String> queryParams) {

    /** 按名字取请求头，大小写不敏感。 */
    public String header(String name) {
        if (name == null || headers == null) {
            return null;
        }
        String direct = headers.get(name);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public String queryParam(String name) {
        return name == null || queryParams == null ? null : queryParams.get(name);
    }
}
