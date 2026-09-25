package com.loom.connection.ws;

import com.loom.connection.manager.ConnectionHandle;
import com.loom.connection.manager.ConnectionManager;
import java.io.EOFException;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * 反向连接（平台连入本应用）的帧处理器。
 *
 * <p>本类是**单例**，所有反向连接共用它，所以任何「每会话」的状态都必须放在 {@link WebSocketSession#getAttributes()} 里，绝不能写成字段。
 *
 * <p>与正向不同，这里不需要缓冲队列：{@code afterConnectionEstablished} 一进来 句柄就已经创建好了。
 */
@Component
public class ReverseWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ReverseWebSocketHandler.class);

    /** 会话属性 key：本会话对应的通道句柄。 */
    static final String ATTR_HANDLE = "loom.handle";

    private final ConnectionManager manager;

    public ReverseWebSocketHandler(ConnectionManager manager) {
        this.manager = manager;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long connectionId =
                (Long) session.getAttributes().get(ReverseHandshakeInterceptor.ATTR_CONNECTION_ID);
        if (connectionId == null) {
            // 走到这里说明拦截器被绕过或注册顺序被改坏了，属于编程错误
            log.error("反向会话缺少 connectionId 属性，关闭: {}", session.getId());
            closeQuietly(session);
            return;
        }
        ConnectionHandle handle = manager.onReverseSessionOpened(connectionId, session);
        if (handle == null) {
            closeQuietly(session);
            return;
        }
        session.getAttributes().put(ATTR_HANDLE, handle);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        forward(session, message.getPayload());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        forward(session, new String(message.getPayload().array(), StandardCharsets.UTF_8));
    }

    private void forward(WebSocketSession session, String payload) {
        ConnectionHandle handle = (ConnectionHandle) session.getAttributes().get(ATTR_HANDLE);
        if (handle == null) {
            return;
        }
        // 注意：这里传的是「本会话自己的」句柄。即使它已被更新的会话取代，
        // 这一帧也属于旧通道，由管理器决定怎么处理（丢弃或记录）。
        manager.onFrame(handle, payload);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ConnectionHandle handle = (ConnectionHandle) session.getAttributes().get(ATTR_HANDLE);
        if (handle != null) {
            manager.onHandleClosed(handle, "平台断开 " + status);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        // 对端正常挥手（发关闭帧后立刻关 TCP）时，Tomcat 也会以「传输错误」回调进来，
        // 异常往往是 null、EOFException 或 ClosedChannelException。那种情况不是故障 ——
        // 记成 WARN 会淹没真正的错误，还会把排查方向带偏。
        if (isNormalDisconnect(exception)) {
            log.debug("反向连接已由对端关闭: session={}", session.getId());
            return;
        }
        log.warn("反向连接传输异常: session={}, {}", session.getId(), exception.getMessage());
    }

    /** 判断是不是「正常断开被包装成异常」的常见形态。 */
    private static boolean isNormalDisconnect(Throwable exception) {
        if (exception == null
                || exception instanceof EOFException
                || exception instanceof ClosedChannelException) {
            return true;
        }
        Throwable cause = exception.getCause();
        return cause != null && cause != exception && isNormalDisconnect(cause);
    }

    private static void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.SERVER_ERROR);
        } catch (Exception e) {
            log.debug("关闭反向会话失败: {}", e.getMessage());
        }
    }
}
