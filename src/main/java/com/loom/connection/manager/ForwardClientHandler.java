package com.loom.connection.manager;

import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * 正向连接（本应用作客户端）的帧处理器 —— {@link ConnectionManager} 的内部协作者。
 *
 * <p>刻意放在 {@code manager} 包而不是 {@code connection.ws}：它只被管理器使用， 而 {@code connection.ws}
 * 里的反向处理器需要反过来依赖 {@code manager}。 放在 {@code ws} 会形成两个包互相依赖的环。
 *
 * <h2>为什么要有一个小缓冲队列</h2>
 *
 * <p>通道句柄要等握手成功、拿到 {@link WebSocketSession} 之后才能创建， 而 {@code afterConnectionEstablished} 与对端首批消息可能在
 * {@code execute(...)} 返回之前就已到达。此时 {@link #attach} 还没被调用， 直接丢帧会造成「连接刚建立就丢消息」这种极难排查的问题。
 *
 * <p>所以先缓冲、attach 后按序回放。队列有上限，防止对端狂发把内存打爆。
 */
final class ForwardClientHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ForwardClientHandler.class);

    /** 缓冲上限。握手窗口极短，正常情况远达不到。 */
    private static final int MAX_PENDING = 256;

    private final ConnectionManager manager;
    private final long connectionId;
    private final Queue<String> pending = new ConcurrentLinkedQueue<>();

    private volatile ConnectionHandle handle;

    ForwardClientHandler(ConnectionManager manager, long connectionId) {
        this.manager = manager;
        this.connectionId = connectionId;
    }

    /** 握手完成后由管理器调用，关联句柄并回放缓冲的帧。 */
    void attach(ConnectionHandle handle) {
        this.handle = handle;
        String buffered;
        while ((buffered = pending.poll()) != null) {
            manager.onFrame(handle, buffered);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        deliver(message.getPayload());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        deliver(new String(message.getPayload().array(), StandardCharsets.UTF_8));
    }

    private void deliver(String payload) {
        ConnectionHandle current = handle;
        if (current == null) {
            if (pending.size() >= MAX_PENDING) {
                log.warn("[连接 {}] 握手窗口内缓冲已满，丢弃一帧", connectionId);
                return;
            }
            pending.add(payload);
            return;
        }
        manager.onFrame(current, payload);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ConnectionHandle current = handle;
        if (current != null) {
            manager.onHandleClosed(current, "对端关闭 " + status);
        } else {
            log.debug("[连接 {}] 握手尚未完成即关闭: {}", connectionId, status);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        // 同 ReverseWebSocketHandler：正常挥手也会走这里，异常可能是 null。
        // 记成 WARN 只会淹没真正的故障。
        if (exception == null
                || exception instanceof java.io.EOFException
                || exception instanceof java.nio.channels.ClosedChannelException) {
            log.debug("[连接 {}] 通道已由对端关闭", connectionId);
            return;
        }
        log.warn("[连接 {}] 传输异常: {}", connectionId, exception.getMessage());
    }
}
