package com.loom.connection.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 反向连接的 WebSocket 端点注册。
 *
 * <p>路径用通配 {@code /ws/**}，因为真实路径是每条连接建表时随机生成的 （{@code /ws/{32 位随机串}}）。通配注册后由 {@link
 * ReverseHandshakeInterceptor} 负责「这个路径属于哪条连接」的判定 —— 判定不通过就不升级，通配本身不构成风险。
 *
 * <h2>刻意不设置 allowedOrigins</h2>
 *
 * <p>Spring 默认放行**没有 Origin 头**的请求、拦截 Origin 不匹配的请求。 平台侧（NapCat / OneBot 实现）是服务端到服务端连接，不发
 * Origin，所以能正常连； 而浏览器发起的跨站连接会被默认策略挡掉。显式写 {@code "*"} 反而会打开 浏览器侧的攻击面，所以这里保持默认。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ReverseWebSocketHandler reverseHandler;
    private final ReverseHandshakeInterceptor reverseInterceptor;

    public WebSocketConfig(
            ReverseWebSocketHandler reverseHandler,
            ReverseHandshakeInterceptor reverseInterceptor) {
        this.reverseHandler = reverseHandler;
        this.reverseInterceptor = reverseInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(reverseHandler, "/ws/**").addInterceptors(reverseInterceptor);
    }
}
