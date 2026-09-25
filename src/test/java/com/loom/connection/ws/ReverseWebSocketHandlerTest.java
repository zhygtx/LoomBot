package com.loom.connection.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.loom.connection.manager.ConnectionHandle;
import com.loom.connection.manager.ConnectionManager;
import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * {@link ReverseWebSocketHandler} 的单元测试。
 *
 * <p>这个类此前只有集成测试覆盖，而集成测试带 {@code @Tag("integration")} 被 CI 排除， 导致它长期停在 49%。但它的逻辑其实完全不依赖外部环境 ——
 * 协作对象只有 {@link ConnectionManager} 一个，且可以 mock，所以这里用纯 Mockito 补齐。
 *
 * <p>重点覆盖三类容易出错的分支：
 *
 * <ul>
 *   <li>会话属性缺失 / 句柄创建失败时的「关闭而不是崩溃」路径；
 *   <li>{@code isNormalDisconnect} 的递归判断 —— 对端正常挥手被 Tomcat 包装成异常时， 如果误判成故障，真正的错误会被 WARN 淹没；
 *   <li>单例复用带来的「每会话状态必须放在 attributes 里」这一约定。
 * </ul>
 */
@DisplayName("反向 WebSocket 帧处理器")
class ReverseWebSocketHandlerTest {

    private ConnectionManager manager;
    private ReverseWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        manager = mock(ConnectionManager.class);
        handler = new ReverseWebSocketHandler(manager);
    }

    /** 造一个带真实 attributes Map 的会话 —— 该 Map 是 handler 的跨调用状态载体。 */
    private WebSocketSession sessionWith(Map<String, Object> attributes) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.getId()).thenReturn("sess-1");
        return session;
    }

    private WebSocketSession sessionWithConnectionId(long connectionId) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(ReverseHandshakeInterceptor.ATTR_CONNECTION_ID, connectionId);
        return sessionWith(attrs);
    }

    @Nested
    @DisplayName("建连")
    class 建连 {

        @Test
        @DisplayName("缺少 connectionId 属性时关闭会话，且不去创建句柄")
        void shouldCloseWhenConnectionIdMissing() throws IOException {
            WebSocketSession session = sessionWith(new HashMap<>());

            handler.afterConnectionEstablished(session);

            // 这是「拦截器被绕过」的编程错误路径，必须关闭而不是放行
            verify(session).close(CloseStatus.SERVER_ERROR);
            verifyNoInteractions(manager);
        }

        @Test
        @DisplayName("句柄创建失败时关闭会话")
        void shouldCloseWhenHandleCreationFails() throws IOException {
            WebSocketSession session = sessionWithConnectionId(7L);
            when(manager.onReverseSessionOpened(eq(7L), any())).thenReturn(null);

            handler.afterConnectionEstablished(session);

            verify(session).close(CloseStatus.SERVER_ERROR);
        }

        @Test
        @DisplayName("成功后把句柄存入会话属性")
        void shouldStoreHandleInAttributes() {
            WebSocketSession session = sessionWithConnectionId(7L);
            ConnectionHandle handle = mock(ConnectionHandle.class);
            when(manager.onReverseSessionOpened(eq(7L), any())).thenReturn(handle);

            handler.afterConnectionEstablished(session);

            assertThat(session.getAttributes())
                    .containsEntry(ReverseWebSocketHandler.ATTR_HANDLE, handle);
        }

        @Test
        @DisplayName("关闭本身抛异常时不能向外传播（否则会污染建连流程）")
        void shouldSwallowCloseFailure() throws IOException {
            WebSocketSession session = sessionWith(new HashMap<>());
            org.mockito.Mockito.doThrow(new IOException("已经断了")).when(session).close(any());

            // 不抛异常即为通过：清理路径的失败不应影响主流程
            handler.afterConnectionEstablished(session);

            verify(session).close(CloseStatus.SERVER_ERROR);
        }
    }

    @Nested
    @DisplayName("收帧")
    class 收帧 {

        @Test
        @DisplayName("文本帧转交给管理器")
        void shouldForwardTextFrame() {
            Map<String, Object> attrs = new HashMap<>();
            WebSocketSession session = sessionWith(attrs);
            ConnectionHandle handle = mock(ConnectionHandle.class);
            attrs.put(ReverseWebSocketHandler.ATTR_HANDLE, handle);

            handler.handleTextMessage(session, new TextMessage("hello"));

            verify(manager).onFrame(handle, "hello");
        }

        @Test
        @DisplayName("二进制帧按 UTF-8 解码后转交")
        void shouldForwardBinaryFrameAsUtf8() {
            Map<String, Object> attrs = new HashMap<>();
            WebSocketSession session = sessionWith(attrs);
            ConnectionHandle handle = mock(ConnectionHandle.class);
            attrs.put(ReverseWebSocketHandler.ATTR_HANDLE, handle);

            handler.handleBinaryMessage(
                    session, new BinaryMessage("中文内容".getBytes(StandardCharsets.UTF_8)));

            verify(manager).onFrame(handle, "中文内容");
        }

        @Test
        @DisplayName("没有句柄时静默丢弃（说明建连没走完）")
        void shouldDropFrameWithoutHandle() {
            WebSocketSession session = sessionWith(new HashMap<>());

            handler.handleTextMessage(session, new TextMessage("hello"));

            verifyNoInteractions(manager);
        }
    }

    @Nested
    @DisplayName("断连")
    class 断连 {

        @Test
        @DisplayName("有句柄时通知管理器，并把关闭原因带上")
        void shouldNotifyManagerOnClose() {
            Map<String, Object> attrs = new HashMap<>();
            WebSocketSession session = sessionWith(attrs);
            ConnectionHandle handle = mock(ConnectionHandle.class);
            attrs.put(ReverseWebSocketHandler.ATTR_HANDLE, handle);
            CloseStatus status = CloseStatus.NORMAL;

            handler.afterConnectionClosed(session, status);

            verify(manager).onHandleClosed(eq(handle), anyString());
        }

        @Test
        @DisplayName("没有句柄时不做任何事")
        void shouldDoNothingWithoutHandle() {
            WebSocketSession session = sessionWith(new HashMap<>());

            handler.afterConnectionClosed(session, CloseStatus.NORMAL);

            verify(manager, never()).onHandleClosed(any(), anyString());
        }
    }

    @Nested
    @DisplayName("传输错误判定")
    class 传输错误判定 {

        @Test
        @DisplayName("null / EOF / ClosedChannel 都算正常断开，不升级为 WARN")
        void shouldTreatCommonNormalFormsAsNormal() {
            WebSocketSession session = sessionWith(new HashMap<>());

            // 这三种是 Tomcat 在「对端发完关闭帧立刻关 TCP」时的典型回调形态
            handler.handleTransportError(session, null);
            handler.handleTransportError(session, new EOFException("对端结束"));
            handler.handleTransportError(session, new ClosedChannelException());

            // 正常断开不应触发任何业务回调
            verifyNoInteractions(manager);
        }

        @Test
        @DisplayName("嵌套在 cause 链里的正常断开同样被识别")
        void shouldDetectNormalDisconnectInCauseChain() {
            WebSocketSession session = sessionWith(new HashMap<>());
            Throwable wrapped = new IOException("外层包装", new EOFException("根因"));

            handler.handleTransportError(session, wrapped);

            verifyNoInteractions(manager);
        }

        @Test
        @DisplayName("自引用的 cause 链不会无限递归")
        void shouldNotLoopOnSelfReferencingCause() {
            WebSocketSession session = sessionWith(new HashMap<>());
            Throwable selfRef = new IOException("自引用");

            // 关键：selfRef 的 cause 指向自己，判定必须终止
            handler.handleTransportError(session, selfRef);

            verifyNoInteractions(manager);
        }

        @Test
        @DisplayName("真实异常不被吞掉（证明前面的判定没有误伤）")
        void shouldKeepRealErrors() {
            WebSocketSession session = sessionWith(new HashMap<>());

            // 只是要求不抛异常；真正异常走 WARN 分支
            handler.handleTransportError(session, new IllegalStateException("真的炸了"));

            verifyNoInteractions(manager);
        }
    }

    @Test
    @DisplayName("会话之间互不串扰（单例复用下的关键约定）")
    void shouldKeepStatePerSession() {
        Map<String, Object> attrsA = new HashMap<>();
        Map<String, Object> attrsB = new HashMap<>();
        WebSocketSession a = sessionWith(attrsA);
        WebSocketSession b = sessionWith(attrsB);
        ConnectionHandle handleA = mock(ConnectionHandle.class);

        attrsA.put(ReverseWebSocketHandler.ATTR_HANDLE, handleA);

        handler.handleTextMessage(a, new TextMessage("from-a"));
        handler.handleTextMessage(b, new TextMessage("from-b"));

        // 只有 A 的帧应该被转交；B 没有句柄
        verify(manager).onFrame(handleA, "from-a");
        verify(manager, never()).onFrame(any(), eq("from-b"));
    }

    @Test
    @DisplayName("老会话的帧仍按自己的句柄上报（由管理器决定丢弃）")
    void shouldReportWithOwnHandleEvenIfSuperseded() {
        Map<String, Object> attrs = new HashMap<>();
        WebSocketSession session = sessionWith(attrs);
        ConnectionHandle oldHandle = mock(ConnectionHandle.class);
        attrs.put(ReverseWebSocketHandler.ATTR_HANDLE, oldHandle);

        handler.handleTextMessage(session, new TextMessage("stale"));

        // 这里刻意不用 anyLong / any() 之外的模糊匹配：句柄身份必须原样传递
        verify(manager).onFrame(eq(oldHandle), eq("stale"));
    }
}
