package com.loom.connection.manager;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;
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
    private final Object stateLock = new Object();
    private final Queue<PendingFrame> pending = new ArrayDeque<>();

    private ConnectionHandle handle;
    private boolean closed;

    ForwardClientHandler(ConnectionManager manager, long connectionId) {
        this.manager = manager;
        this.connectionId = connectionId;
    }

    /** 握手完成后由管理器调用，关联句柄并回放缓冲的帧。 */
    boolean attach(ConnectionHandle handle) {
        synchronized (stateLock) {
            if (closed) {
                pending.clear();
                return false;
            }
            this.handle = handle;
            PendingFrame buffered;
            while ((buffered = pending.poll()) != null) {
                buffered.deliver(manager, handle);
            }
            return true;
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        deliver(PendingFrame.text(message.getPayload()));
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        ByteBuffer buffer = message.getPayload().asReadOnlyBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        deliver(PendingFrame.binary(bytes));
    }

    private void deliver(PendingFrame frame) {
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            if (handle == null) {
                if (pending.size() >= MAX_PENDING) {
                    log.warn("[连接 {}] 握手窗口内缓冲已满，丢弃一帧", connectionId);
                    return;
                }
                pending.add(frame);
                return;
            }
            frame.deliver(manager, handle);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ConnectionHandle current;
        synchronized (stateLock) {
            closed = true;
            current = handle;
        }
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

    private record PendingFrame(String text, byte[] binary) {
        static PendingFrame text(String value) {
            return new PendingFrame(value, null);
        }

        static PendingFrame binary(byte[] value) {
            return new PendingFrame(null, value);
        }

        void deliver(ConnectionManager manager, ConnectionHandle handle) {
            if (binary == null) {
                manager.onFrame(handle, text);
            } else {
                manager.onBinaryFrame(handle, binary);
            }
        }
    }
}
