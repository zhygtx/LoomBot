package com.loom.connection.ws;

import com.loom.connection.handshake.HandshakeRequest;
import com.loom.connection.manager.ConnectionManager;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * 反向连接握手校验 —— 在 WebSocket **升级之前**拦截。
 *
 * <p>必须在这一步拒绝，不能先建立连接再断开：答应升级就意味着资源已经分配、 对端认为连上了，之后再断会触发它的重连逻辑。
 *
 * <h2>⚠️ 一律返回 401，不区分「路径不存在 / 连接停用 / 凭据不对」</h2>
 *
 * <p>文档早期版本写的是 404 / 403 / 401 分别对应三种情况。**这里改成了统一 401**， 理由是：一旦区分，攻击者就能用「404 还是
 * 401」**枚举出哪些路径是真实存在的**， 而路径本身在 {@code x-handshake.mode = none} 时就是唯一凭据。
 *
 * <p>运维需要区分时看**服务端日志**即可 —— {@code rejectReason} 里写了具体原因。
 */
@Component
public class ReverseHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ReverseHandshakeInterceptor.class);

    /** 握手通过后写入 session attributes 的 key。 */
    static final String ATTR_CONNECTION_ID = "loom.connectionId";

    private final ConnectionManager manager;

    public ReverseHandshakeInterceptor(ConnectionManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {

        String path = request.getURI().getPath();
        HandshakeRequest handshakeRequest =
                new HandshakeRequest(
                        path, headersOf(request), parseQuery(request.getURI().getRawQuery()));

        ConnectionManager.ReverseHandshakeResult result =
                manager.validateReverseHandshake(path, handshakeRequest);
        if (!result.accepted()) {
            log.warn("反向握手被拒: path={}, 原因={}", path, result.rejectReason());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(ATTR_CONNECTION_ID, result.connectionId());
        log.info("反向握手通过: path={}, connectionId={}", path, result.connectionId());
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
        if (exception != null) {
            log.warn("握手后异常: path={}, {}", request.getURI().getPath(), exception.getMessage());
        }
    }

    private static Map<String, String> headersOf(ServerHttpRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        request.getHeaders()
                .forEach(
                        (name, values) -> {
                            if (values != null && !values.isEmpty()) {
                                headers.put(name, values.get(0));
                            }
                        });
        return headers;
    }

    /** 解析查询串。手写而不依赖 Web 上下文 —— 握手阶段拿不到常规的请求作用域。 */
    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            params.put(decode(key), decode(value));
        }
        return params;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return value;
        }
    }
}
