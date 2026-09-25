package com.loom.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.loom.connection.domain.ConnectionState;
import com.loom.connection.domain.ConnectionStatus;
import com.loom.connection.dto.ConnectionCreateRequest;
import com.loom.connection.dto.ConnectionResponse;
import com.loom.connection.manager.ConnectionManager;
import com.loom.connection.mapper.WsConnectionMapper;
import com.loom.connection.service.WsConnectionService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

/**
 * 连接端到端测试：<b>反向建连 → 收帧 → 断线 → 重连</b>。
 *
 * <p>链路是完整的、真实的一圈：
 *
 * <pre>
 *   测试客户端 ──WS──▶ Java（/ws/***）──NDJSON──▶ 假适配器（python 子进程）
 *        ▲                                              │
 *        └────────────── ws.send ◀──NDJSON──────────────┘
 * </pre>
 *
 * <p>因此它验证的不只是「Java 自己能跑」：只要 Python 进程没起来、IPC 协议字段对不上、 handleId 没正确下发、或者 Java 的帧转发有偏差，这条回路就断了。
 *
 * <p>打 {@code integration} 标签，因为需要真实 MySQL、Python 解释器和一个可用的端口。 {@code @AutoConfigureMockMvc} 在这里用不到
 * MockMvc，加它是为了与 {@code ConnectionHttpIntegrationTest} 共用同一个 Spring 上下文 —— 每个上下文都会 额外拉起一个 Python
 * 子进程，能省则省。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@DisplayName("连接端到端：建连 / 收帧 / 断线 / 重连")
class ConnectionE2EIntegrationTest {

    private static final Duration WS_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration STATE_TIMEOUT = Duration.ofSeconds(15);

    @LocalServerPort private int port;

    @Autowired private WsConnectionService service;

    @Autowired private ConnectionManager manager;

    @Autowired private WsConnectionMapper mapper;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private JdbcTemplate jdbc;

    private String token;
    private String connectionName;
    private Long connectionId;
    private ConnectionResponse connection;

    @BeforeEach
    void setUp() {
        token = "e2e-" + UUID.randomUUID();
        connectionName = "e2e-" + UUID.randomUUID();
        awaitFakeAdapter();
        connection =
                service.create(
                        new ConnectionCreateRequest(
                                connectionName,
                                "fake",
                                objectMapper.readTree(
                                        "{\"token\":\"" + token + "\",\"echoFrames\":true}"),
                                "端到端测试"),
                        null);
        connectionId = connection.id();
    }

    @AfterEach
    void tearDown() {
        // 物理删除 + 按前缀：见 TestCleanup —— 逻辑删除下 deleteById 只置标志，
        // 而行会永久留在表里，且后续查询再也看不到它
        TestCleanup.purge(jdbc, manager, "e2e-");
        connectionId = null;
    }

    // ==================================================================
    // 主链路
    // ==================================================================

    @Test
    @DisplayName("全链路：平台连入后能收到回帧；断开后自动回到等待；再连入仍可用")
    void shouldCompleteFullRoundTrip() throws Exception {
        // ---- 1. 建连：反向连接不主动外连，注册端点后应停在「等待平台连入」
        assertThat(connection.endpointPath()).startsWith("/ws/");
        awaitState(ConnectionState.LISTENING);

        // ---- 2. 平台连入
        Collector first = new Collector();
        WebSocketSession session = connect(connection.endpointPath(), token, first);
        awaitState(ConnectionState.ONLINE);

        // ---- 3. 收帧：发一帧 → 适配器判定命中 → event.matched → ws.send 原样回灌
        session.sendMessage(new TextMessage("{\"type\":\"wanted\",\"echo\":\"e2e-1\"}"));
        String echoed = first.awaitMessage(WS_TIMEOUT);
        assertThat(echoed)
                .as("适配器应当把命中帧原样回灌（echoFrames=true），否则说明 IPC 或 handleId 有偏差")
                .isNotNull()
                .contains("e2e-1");

        // ---- 4. 断线：平台断开后 Java 应重新回到「等待平台连入」，而不是停在线或崩掉
        session.close();
        assertThat(first.closed.await(WS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .as("客户端侧应当观察到连接关闭")
                .isTrue();
        awaitState(ConnectionState.LISTENING);

        // ---- 5. 重连：同一个路径再连一次，应当无缝接管（handleId 会换新，映射要能跟上）
        Collector second = new Collector();
        WebSocketSession resumed = connect(connection.endpointPath(), token, second);
        awaitState(ConnectionState.ONLINE);

        resumed.sendMessage(new TextMessage("{\"type\":\"wanted\",\"echo\":\"e2e-2\"}"));
        String echoedAgain = second.awaitMessage(WS_TIMEOUT);
        assertThat(echoedAgain).isNotNull().contains("e2e-2");

        // 旧句柄必须已经作废：在旧会话上发帧不该再被处理（旧会话已关，这里只验证新会话干净可用）
        resumed.close();
    }

    @Test
    @DisplayName("停用连接后端点应立刻失效 —— 停用不能只是改个数据库标志")
    void shouldRejectWhenDisabled() throws Exception {
        awaitState(ConnectionState.LISTENING);

        service.setEnabled(connectionId, false, null);
        awaitState(ConnectionState.OFFLINE);

        Collector collector = new Collector();
        try {
            connect(connection.endpointPath(), token, collector);
            fail("连接已停用，握手不应成功");
        } catch (Exception expected) {
            assertThat(expected).hasMessageContaining("401");
        }

        service.setEnabled(connectionId, true, null);
        awaitState(ConnectionState.LISTENING);

        Collector afterEnable = new Collector();
        WebSocketSession session = connect(connection.endpointPath(), token, afterEnable);
        awaitState(ConnectionState.ONLINE);
        session.close();
    }

    // ==================================================================
    // 握手校验
    // ==================================================================

    @Test
    @DisplayName("token 不对时必须在升级阶段就被拒（401），不能先建立连接再断开")
    void shouldRejectWrongToken() throws Exception {
        awaitState(ConnectionState.LISTENING);

        Collector collector = new Collector();
        try {
            connect(connection.endpointPath(), "wrong-token", collector);
            fail("token 不匹配，握手不应成功");
        } catch (Exception expected) {
            assertThat(expected).hasMessageContaining("401");
        }
        // 被拒绝的握手不该影响正常连接
        awaitState(ConnectionState.LISTENING);
    }

    @Test
    @DisplayName("未注册的路径必须同样返回 401，不能因为路径不存在就回 404 —— 否则可枚举出有效路径")
    void shouldNotLeakPathExistence() throws Exception {
        Collector collector = new Collector();
        try {
            connect("/ws/" + "0".repeat(32), token, collector);
            fail("路径未注册，握手不应成功");
        } catch (Exception expected) {
            assertThat(expected).hasMessageContaining("401");
        }
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private void awaitFakeAdapter() {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            if (manager.connectionType("fake").map(t -> t.ready()).orElse(false)) {
                return;
            }
            sleep(200);
        }
        throw new IllegalStateException(
                "假适配器 30 秒内没有就绪。请确认 `python` 在 PATH 上，" + "且 demo/plugins/fake-adapter/main.py 存在");
    }

    private void awaitState(ConnectionState expected) {
        ConnectionState last = null;
        Instant deadline = Instant.now().plus(STATE_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            ConnectionStatus status = manager.status(connectionId);
            last = status.state();
            if (last == expected) {
                return;
            }
            sleep(100);
        }
        throw new AssertionError(
                "等待状态 "
                        + expected
                        + " 超时，实际是 "
                        + last
                        + "（原因: "
                        + manager.status(connectionId).failureReason()
                        + "）");
    }

    private WebSocketSession connect(String path, String accessToken, Collector collector)
            throws Exception {
        String url =
                "ws://127.0.0.1:"
                        + port
                        + path
                        + "?access_token="
                        + URLEncoder.encode(accessToken, StandardCharsets.UTF_8);
        return new StandardWebSocketClient()
                .execute(collector, url)
                .get(WS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 把收到的帧丢进队列，供测试按超时取用；同时记录关闭事件。 */
    static final class Collector extends AbstractWebSocketHandler {

        private final BlockingQueue<String> received = new LinkedBlockingQueue<>();
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            received.add(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed.countDown();
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            closed.countDown();
        }

        /** 等一帧；超时返回 {@code null}，由调用方给出有意义的断言信息。 */
        String awaitMessage(Duration timeout) throws InterruptedException {
            return received.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }
}
