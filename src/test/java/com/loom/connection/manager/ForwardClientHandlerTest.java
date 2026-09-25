package com.loom.connection.manager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 正向通道帧处理器的单元测试。
 *
 * <p>这个类里藏着一个**只在竞态下才会暴露**的行为：通道句柄要等握手成功后才创建， 而对端可能在 {@code execute(...)} 返回之前就把首批消息发过来了。
 * 没有缓冲队列的话，表现是「连接刚建立就丢消息」—— 偶发、无日志、极难排查。 所以这里专门把那个窗口固定下来测。
 */
@DisplayName("正向通道帧处理器")
class ForwardClientHandlerTest {

    private static final long CONNECTION_ID = 7L;

    private ConnectionManager manager;
    private ForwardClientHandler handler;

    @BeforeEach
    void setUp() {
        manager = mock(ConnectionManager.class);
        handler = new ForwardClientHandler(manager, CONNECTION_ID);
    }

    private static WebSocketSession session() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        return session;
    }

    @Nested
    @DisplayName("句柄就位之前")
    class 句柄就位之前 {

        @Test
        @DisplayName("attach 之前的帧必须被缓冲，不能丢 —— 这是握手窗口期的竞态")
        void shouldBufferFramesBeforeAttach() {
            handler.handleTextMessage(session(), new TextMessage("first"));

            // 句柄还没就位，此时不该有任何转发
            verify(manager, never()).onFrame(any(), anyString());

            ConnectionHandle handle = new ConnectionHandle("h-1", CONNECTION_ID, session());
            handler.attach(handle);

            verify(manager).onFrame(handle, "first");
        }

        @Test
        @DisplayName("缓冲的帧必须按到达顺序回放 —— 乱序会破坏协议语义")
        void shouldReplayInOrder() {
            handler.handleTextMessage(session(), new TextMessage("a"));
            handler.handleTextMessage(session(), new TextMessage("b"));
            handler.handleTextMessage(session(), new TextMessage("c"));

            ConnectionHandle handle = new ConnectionHandle("h-1", CONNECTION_ID, session());
            handler.attach(handle);

            var inOrder = org.mockito.Mockito.inOrder(manager);
            inOrder.verify(manager).onFrame(handle, "a");
            inOrder.verify(manager).onFrame(handle, "b");
            inOrder.verify(manager).onFrame(handle, "c");
        }

        @Test
        @DisplayName("缓冲有上限：对端狂发时丢帧并告警，而不是把内存打爆")
        void shouldCapBufferedFrames() {
            for (int i = 0; i < 300; i++) {
                handler.handleTextMessage(session(), new TextMessage("m" + i));
            }

            ConnectionHandle handle = new ConnectionHandle("h-1", CONNECTION_ID, session());
            handler.attach(handle);

            // 上限是 256，所以最多回放 256 帧
            verify(manager, org.mockito.Mockito.atMost(256)).onFrame(eq(handle), anyString());
        }
    }

    @Nested
    @DisplayName("句柄就位之后")
    class 句柄就位之后 {

        private ConnectionHandle handle;

        @BeforeEach
        void attachHandle() {
            handle = new ConnectionHandle("h-1", CONNECTION_ID, session());
            handler.attach(handle);
        }

        @Test
        @DisplayName("文本帧应直接转发")
        void shouldForwardTextFrame() {
            handler.handleTextMessage(session(), new TextMessage("hello"));

            verify(manager).onFrame(handle, "hello");
        }

        @Test
        @DisplayName("二进制帧应按 UTF-8 转成文本转发 —— 协议层只认文本")
        void shouldForwardBinaryFrameAsUtf8() {
            BinaryMessage binary =
                    new BinaryMessage(
                            "{\"type\":\"x\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

            handler.handleBinaryMessage(session(), binary);

            verify(manager).onFrame(handle, "{\"type\":\"x\"}");
        }

        @Test
        @DisplayName("对端关闭应上报断线，由管理器决定是否重连")
        void shouldReportClose() {
            handler.afterConnectionClosed(session(), CloseStatus.NORMAL);

            verify(manager)
                    .onHandleClosed(eq(handle), org.mockito.ArgumentMatchers.contains("对端关闭"));
        }

        @Test
        @DisplayName("传输异常只记日志，不触发重连 —— 关闭事件才是权威信号")
        void shouldNotReconnectOnTransportError() {
            handler.handleTransportError(session(), new RuntimeException("boom"));

            verify(manager, never()).onHandleClosed(any(), anyString());
        }

        @Test
        @DisplayName("正常断开的空异常不该被当成故障传播")
        void shouldTreatNullExceptionAsNormalClose() {
            handler.handleTransportError(session(), null);

            verify(manager, never()).onHandleClosed(any(), anyString());
        }
    }

    @Nested
    @DisplayName("握手未完成就断开")
    class 握手未完成就断开 {

        @Test
        @DisplayName("句柄尚未就位时关闭不应上报 —— 没有句柄可说")
        void shouldNotReportCloseWithoutHandle() {
            handler.afterConnectionClosed(session(), CloseStatus.SERVER_ERROR);

            verify(manager, never()).onHandleClosed(any(), anyString());
        }
    }
}
