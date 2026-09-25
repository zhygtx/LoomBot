package com.loom.connection.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loom.config.AdapterProperties;
import com.loom.connection.adapter.AdapterSession;
import com.loom.connection.domain.ConnectionState;
import com.loom.connection.domain.ConnectionStatus;
import com.loom.connection.domain.Direction;
import com.loom.connection.domain.WsConnection;
import com.loom.connection.handshake.HandshakeRequest;
import com.loom.connection.handshake.HandshakeSpec;
import com.loom.connection.handshake.HandshakeValidatorRegistry;
import com.loom.connection.mapper.WsConnectionMapper;
import com.loom.runtime.ipc.IpcCodec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * 连接状态机的单元测试。
 *
 * <h2>为什么这些用例值得单独写</h2>
 *
 * <p>端到端测试能证明「正常路径能跑通」，但状态机真正难的是**异常分支**： 被取代的会话该不该重连、退避期间平台抢连会不会把状态降级、适配器挂掉时连接怎么降级。
 * 这些分支在真实环境里偶发、难以复现，却正是最容易写错的地方。
 *
 * <p>用 Mockito 把适配器换成替身，就能在毫秒级反复验证它们。
 */
@DisplayName("连接状态机")
class ConnectionManagerTest {

    private static final long CONNECTION_ID = 42L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WsConnectionMapper mapper;
    private AdapterSession adapter;
    private ConnectionManager manager;

    @BeforeEach
    void setUp() {
        mapper = mock(WsConnectionMapper.class);
        adapter = mock(AdapterSession.class);
        // 反向连接：不涉及真实 socket，状态机分支最容易驱动
        lenient().when(adapter.direction()).thenReturn(Direction.REVERSE);
        lenient().when(adapter.isReady()).thenReturn(true);
        lenient().when(adapter.isAlive()).thenReturn(true);
        lenient().when(adapter.connectionType()).thenReturn("fake");
        lenient().when(adapter.name()).thenReturn("adapter-fake");
        lenient().when(adapter.displayName()).thenReturn("假适配器");
        lenient().when(adapter.capabilities()).thenReturn(List.of());
        // 不 stub 它会返回 null，握手校验注册表会判「未声明规格」而拒绝一切握手 ——
        // 那不是被测逻辑的问题，是替身没给全信息
        lenient().when(adapter.handshakeSpec()).thenReturn(HandshakeSpec.NONE);

        manager =
                new ConnectionManager(
                        mapper,
                        new IpcCodec(objectMapper),
                        objectMapper,
                        new AdapterProperties("python", List.of(), 1000L, 60000L),
                        new ReverseEndpointRegistry(),
                        new HandshakeValidatorRegistry());
    }

    /** 让管理器认识一条反向连接。 */
    private WsConnection storedConnection(String endpointPath, int enabled) {
        WsConnection entity = new WsConnection();
        entity.setId(CONNECTION_ID);
        entity.setName("c-" + CONNECTION_ID);
        entity.setConnectionType("fake");
        entity.setConfig("{\"token\":\"t\"}");
        entity.setEndpointPath(endpointPath);
        entity.setEnabled(enabled);
        lenient().when(mapper.selectById(CONNECTION_ID)).thenReturn(entity);
        // onHello 是通过 selectList 发现「该类型的启用连接」的；不 stub 它，
        // 适配器上线就拉不起任何连接
        lenient()
                .when(mapper.selectList(any()))
                .thenReturn(enabled == 1 ? List.of(entity) : List.of());
        return entity;
    }

    @Nested
    @DisplayName("适配器未就绪")
    class 适配器未就绪 {

        @Test
        @DisplayName("启动时适配器不在线 → WAITING_ADAPTER，而不是拒绝或失败")
        void shouldWaitWhenAdapterMissing() {
            storedConnection("/ws/aaa", 1);

            manager.start(CONNECTION_ID);

            ConnectionStatus status = manager.status(CONNECTION_ID);
            assertThat(status.state()).isEqualTo(ConnectionState.WAITING_ADAPTER);
            assertThat(status.enabled()).isTrue();
            assertThat(status.failureReason()).contains("fake");
        }

        @Test
        @DisplayName("适配器 hello 后应自动把该类型的启用连接拉起来")
        void shouldStartConnectionsOnHello() {
            storedConnection("/ws/aaa", 1);
            manager.start(CONNECTION_ID);
            assertThat(manager.status(CONNECTION_ID).state())
                    .isEqualTo(ConnectionState.WAITING_ADAPTER);

            manager.onHello(adapter);

            assertThat(manager.status(CONNECTION_ID).state()).isEqualTo(ConnectionState.LISTENING);
        }

        @Test
        @DisplayName("适配器 hello 不应拉起已停用的连接")
        void shouldNotStartDisabledOnHello() {
            storedConnection("/ws/aaa", 0);

            manager.onHello(adapter);

            assertThat(manager.status(CONNECTION_ID).state()).isEqualTo(ConnectionState.OFFLINE);
        }
    }

    @Nested
    @DisplayName("反向端点")
    class 反向端点 {

        @Test
        @DisplayName("适配器就绪后反向连接进入 LISTENING，并注册端点")
        void shouldListenAfterStart() {
            storedConnection("/ws/aaa", 1);

            manager.onHello(adapter);

            assertThat(manager.status(CONNECTION_ID).state()).isEqualTo(ConnectionState.LISTENING);
            assertThat(manager.validateReverseHandshake("/ws/aaa", request()).accepted()).isTrue();
        }

        @Test
        @DisplayName("缺少 endpointPath 的反向连接应置 FAILED，而不是静默什么都不做")
        void shouldFailWithoutEndpointPath() {
            storedConnection(null, 1);
            manager.onHello(adapter);

            manager.start(CONNECTION_ID);

            ConnectionStatus status = manager.status(CONNECTION_ID);
            assertThat(status.state()).isEqualTo(ConnectionState.FAILED);
            assertThat(status.failureReason()).contains("缺少");
        }

        @Test
        @DisplayName("停用后端点应立即失效 —— 停用不能只是改个标志")
        void shouldUnregisterOnStop() {
            storedConnection("/ws/aaa", 1);
            manager.onHello(adapter);
            manager.start(CONNECTION_ID);

            manager.stop(CONNECTION_ID);

            assertThat(manager.status(CONNECTION_ID).state()).isEqualTo(ConnectionState.OFFLINE);
            assertThat(manager.validateReverseHandshake("/ws/aaa", request()).accepted()).isFalse();
        }

        @Test
        @DisplayName("未注册路径应被拒，且原因可读")
        void shouldRejectUnknownPath() {
            manager.onHello(adapter);

            ConnectionManager.ReverseHandshakeResult result =
                    manager.validateReverseHandshake("/ws/nope", request());

            assertThat(result.accepted()).isFalse();
            assertThat(result.rejectReason()).contains("未注册");
        }

        @Test
        @DisplayName("适配器不在线时握手应被拒 —— 连进来也没人解析帧")
        void shouldRejectWhenAdapterAbsent() {
            storedConnection("/ws/aaa", 1);
            // 不走 onHello，所以适配器未注册
            manager.start(CONNECTION_ID);

            ConnectionManager.ReverseHandshakeResult result =
                    manager.validateReverseHandshake("/ws/aaa", request());

            // 端点压根没注册（因为适配器缺席时不会走到注册那一步）
            assertThat(result.accepted()).isFalse();
        }
    }

    @Nested
    @DisplayName("会话接管")
    class 会话接管 {

        @Test
        @DisplayName("新会话应取代旧会话，且旧会话关闭不触发重连")
        void shouldSupersedeOldSession() {
            storedConnection("/ws/aaa", 1);
            manager.onHello(adapter);
            manager.start(CONNECTION_ID);

            ConnectionHandle first =
                    manager.onReverseSessionOpened(CONNECTION_ID, mockSession("s1"));
            assertThat(manager.status(CONNECTION_ID).state()).isEqualTo(ConnectionState.ONLINE);
            assertThat(manager.currentHandleOf(CONNECTION_ID)).isSameAs(first);

            ConnectionHandle second =
                    manager.onReverseSessionOpened(CONNECTION_ID, mockSession("s2"));

            assertThat(first.isSuperseded()).isTrue();
            assertThat(first.isOpen()).isFalse();
            assertThat(manager.currentHandleOf(CONNECTION_ID)).isSameAs(second);

            // 关键：旧会话的关闭事件必须被识别为「被取代」，绝不能触发重连
            manager.onHandleClosed(first, "被新会话取代");
            assertThat(manager.status(CONNECTION_ID).state()).isEqualTo(ConnectionState.ONLINE);
        }

        @Test
        @DisplayName("每次接管都应换新的 handleId —— 不同会话不能共用一个句柄")
        void shouldIssueFreshHandleId() {
            storedConnection("/ws/aaa", 1);
            manager.onHello(adapter);
            manager.start(CONNECTION_ID);

            ConnectionHandle first =
                    manager.onReverseSessionOpened(CONNECTION_ID, mockSession("s1"));
            ConnectionHandle second =
                    manager.onReverseSessionOpened(CONNECTION_ID, mockSession("s2"));

            assertThat(first.handleId()).isNotEqualTo(second.handleId());
        }

        @Test
        @DisplayName("平台连入时若适配器在场，应把 handleId 下发下去 —— 否则反向连接只能收不能发")
        void shouldPushHandleIdToAdapter() {
            storedConnection("/ws/aaa", 1);
            manager.onHello(adapter);
            manager.start(CONNECTION_ID);
            org.mockito.Mockito.clearInvocations(adapter);

            ConnectionHandle handle =
                    manager.onReverseSessionOpened(CONNECTION_ID, mockSession("s1"));

            verify(adapter).bindHandle(CONNECTION_ID, handle.handleId());
            verify(adapter)
                    .sendToConnection(
                            org.mockito.ArgumentMatchers.eq(CONNECTION_ID),
                            org.mockito.ArgumentMatchers.eq("ws.opened"),
                            any());
        }

        @Test
        @DisplayName("真断线应进入 RECONNECTING")
        void shouldReconnectOnRealDisconnect() {
            storedConnection("/ws/aaa", 1);
            manager.onHello(adapter);
            manager.start(CONNECTION_ID);
            ConnectionHandle handle =
                    manager.onReverseSessionOpened(CONNECTION_ID, mockSession("s1"));

            manager.onHandleClosed(handle, "对端关闭");

            ConnectionStatus status = manager.status(CONNECTION_ID);
            assertThat(status.state()).isEqualTo(ConnectionState.RECONNECTING);
            assertThat(status.consecutiveFailures()).isEqualTo(1);
        }

        @Test
        @DisplayName("停用中的连接断线不该重连 —— 那是用户主动停的")
        void shouldNotReconnectWhenDisabled() {
            storedConnection("/ws/aaa", 1);
            manager.onHello(adapter);
            manager.start(CONNECTION_ID);
            ConnectionHandle handle =
                    manager.onReverseSessionOpened(CONNECTION_ID, mockSession("s1"));

            manager.stop(CONNECTION_ID);
            manager.onHandleClosed(handle, "对端关闭");

            assertThat(manager.status(CONNECTION_ID).state()).isEqualTo(ConnectionState.OFFLINE);
        }

        @Test
        @DisplayName("非当前会话关闭应被忽略，不能影响在线状态")
        void shouldIgnoreStaleHandleClose() {
            storedConnection("/ws/aaa", 1);
            manager.onHello(adapter);
            manager.start(CONNECTION_ID);
            ConnectionHandle first =
                    manager.onReverseSessionOpened(CONNECTION_ID, mockSession("s1"));
            manager.onReverseSessionOpened(CONNECTION_ID, mockSession("s2"));

            // first 已被取代；这里再关一次不该有任何副作用
            manager.onHandleClosed(first, "重复关闭");

            assertThat(manager.status(CONNECTION_ID).state()).isEqualTo(ConnectionState.ONLINE);
        }

        @Test
        @DisplayName("未知 connectionId 的会话应被直接关掉，不能留在内存里")
        void shouldRejectUnknownConnectionSession() throws Exception {
            org.springframework.web.socket.WebSocketSession session = mockSession("s9");

            ConnectionHandle handle = manager.onReverseSessionOpened(999L, session);

            assertThat(handle).isNull();
            verify(session).close(any(org.springframework.web.socket.CloseStatus.class));
        }
    }

    @Nested
    @DisplayName("帧转发")
    class 帧转发 {

        @Test
        @DisplayName("收到的帧应带 connectionId 与 handleId 一起转给适配器")
        void shouldForwardFrameWithBothIds() {
            storedConnection("/ws/aaa", 1);
            manager.onHello(adapter);
            manager.start(CONNECTION_ID);
            ConnectionHandle handle =
                    manager.onReverseSessionOpened(CONNECTION_ID, mockSession("s1"));
            org.mockito.Mockito.clearInvocations(adapter);

            manager.onFrame(handle, "{\"type\":\"wanted\"}");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<java.util.function.Consumer<tools.jackson.databind.node.ObjectNode>>
                    captor = ArgumentCaptor.forClass(java.util.function.Consumer.class);
            verify(adapter)
                    .sendToConnection(
                            org.mockito.ArgumentMatchers.eq(CONNECTION_ID),
                            org.mockito.ArgumentMatchers.eq("ws.frame"),
                            captor.capture());

            // 用序列化后的 JSON 断言，而不是直接翻节点树：
            // JsonNodeFactory.instance 与 ObjectMapper 造出的 ObjectNode 实现类可能不同，
            // 跨类型 get() 会拿到 null。序列化是两边都保证一致的共同语言。
            //
            // ⚠️ 这里断言不到 connectionId：它由 AdapterSession.sendToConnection 负责填充，
            // 而此处 adapter 是替身（不会真的填）。越界断言只会造出一条假的失败。
            tools.jackson.databind.node.ObjectNode payload =
                    tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            captor.getValue().accept(payload);
            String json = objectMapper.writeValueAsString(payload);
            assertThat(json).contains("\"handleId\":\"" + handle.handleId() + "\"");
            assertThat(json).contains("\"encoding\":\"text\"");
            assertThat(json).contains("\"content\":\"{\\\"type\\\":\\\"wanted\\\"}\"");
        }

        @Test
        @DisplayName("适配器不在场时收到帧应丢弃，而不是抛异常把读取线程弄死")
        void shouldDropFrameWithoutAdapter() {
            storedConnection("/ws/aaa", 1);
            manager.onHello(adapter);
            manager.start(CONNECTION_ID);
            ConnectionHandle handle =
                    manager.onReverseSessionOpened(CONNECTION_ID, mockSession("s1"));
            org.mockito.Mockito.clearInvocations(adapter);

            // 未知 connectionId 的句柄：管理器查不到运行时，应静默丢弃
            manager.onFrame(new ConnectionHandle("h-ghost", 999L, mockSession("s9")), "{}");

            verify(adapter, never())
                    .sendToConnection(org.mockito.ArgumentMatchers.anyLong(), any(), any());
        }
    }

    @Nested
    @DisplayName("启停与清理")
    class 启停与清理 {

        @Test
        @DisplayName("删除连接应清掉运行时痕迹，且幂等")
        void shouldForgetConnectionIdempotently() {
            storedConnection("/ws/aaa", 1);
            manager.onHello(adapter);
            manager.start(CONNECTION_ID);

            manager.forget(CONNECTION_ID);
            manager.forget(CONNECTION_ID);

            assertThat(manager.status(CONNECTION_ID).state()).isEqualTo(ConnectionState.OFFLINE);
            assertThat(manager.status(CONNECTION_ID).enabled()).isFalse();
        }

        @Test
        @DisplayName("停用再启用应重新注册端点")
        void shouldReRegisterOnReEnable() {
            storedConnection("/ws/aaa", 1);
            manager.onHello(adapter);
            manager.stop(CONNECTION_ID);

            manager.start(CONNECTION_ID);

            assertThat(manager.validateReverseHandshake("/ws/aaa", request()).accepted()).isTrue();
        }

        @Test
        @DisplayName("重复 start 应幂等：不重复发 conn.open")
        void shouldNotifyAdapterOnlyOnce() {
            storedConnection("/ws/aaa", 1);
            manager.onHello(adapter);
            // onHello 已经拉过一次，所以此后 start 不应再发第二次
            verify(adapter, times(1))
                    .sendToConnection(
                            org.mockito.ArgumentMatchers.eq(CONNECTION_ID),
                            org.mockito.ArgumentMatchers.eq("conn.open"),
                            any());
            org.mockito.Mockito.clearInvocations(adapter);

            manager.start(CONNECTION_ID);
            manager.start(CONNECTION_ID);

            verify(adapter, never())
                    .sendToConnection(
                            org.mockito.ArgumentMatchers.eq(CONNECTION_ID),
                            org.mockito.ArgumentMatchers.eq("conn.open"),
                            any());
        }

        @Test
        @DisplayName("不存在的连接 start/stop 应安全返回，不抛异常")
        void shouldTolerateUnknownConnection() {
            when(mapper.selectById(999L)).thenReturn(null);

            manager.start(999L);
            manager.stop(999L);
            manager.reload(999L);
            manager.forget(999L);

            assertThat(manager.status(999L).state()).isEqualTo(ConnectionState.OFFLINE);
        }
    }

    @Nested
    @DisplayName("连接类型")
    class 连接类型 {

        @Test
        @DisplayName("未 hello 前类型列表为空 —— 类型的真相在适配器手里")
        void shouldBeEmptyBeforeHello() {
            assertThat(manager.connectionTypes()).isEmpty();
            assertThat(manager.connectionType("fake")).isEmpty();
        }

        @Test
        @DisplayName("hello 后应能查到类型描述")
        void shouldDescribeAfterHello() {
            manager.onHello(adapter);

            assertThat(manager.connectionType("fake")).isPresent();
            assertThat(manager.connectionTypes()).hasSize(1);
            assertThat(manager.connectionTypes().get(0).type()).isEqualTo("fake");
            assertThat(manager.connectionTypes().get(0).direction()).isEqualTo(Direction.REVERSE);
            assertThat(manager.connectionTypes().get(0).ready()).isTrue();
        }

        @Test
        @DisplayName("适配器进程退出后类型应标记为 not ready")
        void shouldMarkNotReadyAfterExit() {
            manager.onHello(adapter);
            when(adapter.isAlive()).thenReturn(false);

            // onAdapterExited 只做记录（真正的降级在 onAdapterProcessExit），
            // 类型仍然查得到，但 ready 翻成 false —— 前端据此提示「插件未就绪」
            assertThat(manager.connectionType("fake")).isPresent();
            assertThat(manager.connectionType("fake").orElseThrow().ready()).isFalse();
        }
    }

    private static HandshakeRequest request() {
        return new HandshakeRequest("/ws/aaa", Map.of(), Map.of("access_token", "t"));
    }

    private static org.springframework.web.socket.WebSocketSession mockSession(String id) {
        org.springframework.web.socket.WebSocketSession session =
                mock(org.springframework.web.socket.WebSocketSession.class);
        lenient().when(session.getId()).thenReturn(id);
        lenient().when(session.isOpen()).thenReturn(true);
        lenient().when(session.getAttributes()).thenReturn(new java.util.HashMap<>());
        return session;
    }

    /** 让编译器知道 never() 被用到（部分用例只在特定分支下调用）。 */
    @SuppressWarnings("unused")
    private void assertNeverCalled() {
        verify(adapter, never()).close();
    }
}
