package com.loom.connection.manager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 一个已建立的 WebSocket 通道。
 *
 * <p>**两个方向共用**：正向（客户端 session）与反向（服务端 session）都包成这个类型， 这样适配器与上层逻辑不需要关心它是怎么建立的。
 *
 * <h2>「已被取代」标记</h2>
 *
 * <p>平台重连时会出现新旧会话重叠（网络抖动后平台发起新连接，旧 TCP 连接还没超时关闭）。 处理方式是接受新会话、关闭旧会话 —— 但旧会话关闭会触发「连接断开」进而触发重连调度，
 * 造成**重连风暴**。
 *
 * <p>所以被替换掉的句柄要打上 {@link #markSuperseded()}，其关闭事件不再触发重连。 不处理的话，表现是「网络抖动一次，连接开始反复重建」，且极难复现。
 */
public final class ConnectionHandle {

    private static final Logger log = LoggerFactory.getLogger(ConnectionHandle.class);

    private final String handleId;
    private final long connectionId;
    private final WebSocketSession session;
    private final AtomicBoolean superseded = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public ConnectionHandle(String handleId, long connectionId, WebSocketSession session) {
        this.handleId = handleId;
        this.connectionId = connectionId;
        this.session = session;
    }

    public String handleId() {
        return handleId;
    }

    public long connectionId() {
        return connectionId;
    }

    public WebSocketSession session() {
        return session;
    }

    public boolean isOpen() {
        return session.isOpen() && !closed.get();
    }

    public boolean isSuperseded() {
        return superseded.get();
    }

    /** 标记为已被新会话取代，其关闭事件不应触发重连。 */
    public void markSuperseded() {
        superseded.set(true);
    }

    /** 发送文本帧。 */
    public void sendText(String text) {
        send(new TextMessage(text));
    }

    /** 发送二进制帧。 */
    public void sendBinary(byte[] data) {
        send(new BinaryMessage(data));
    }

    private void send(org.springframework.web.socket.WebSocketMessage<?> message) {
        if (!isOpen()) {
            log.debug("[{}] 通道已关闭，丢弃待发消息", handleId);
            return;
        }
        try {
            // WebSocketSession 不是线程安全的，并发发送必须串行
            synchronized (session) {
                session.sendMessage(message);
            }
        } catch (IOException e) {
            log.warn("[{}] 发送失败: {}", handleId, e.getMessage());
        }
    }

    public void close(int code, String reason) {
        close(new CloseStatus(code, reason == null ? "" : reason));
    }

    public void close(CloseStatus status) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (IOException e) {
            log.debug("[{}] 关闭通道时异常: {}", handleId, e.getMessage());
        }
    }

    public void markClosed() {
        closed.set(true);
    }

    /** 用于日志的简短描述。 */
    public String describe() {
        return handleId + "(" + session.getId() + ")";
    }

    static String utf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
