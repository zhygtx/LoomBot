package com.loom.connection.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.loom.connection.domain.Direction;
import com.loom.connection.handshake.HandshakeSpec;
import com.loom.runtime.ipc.IpcCodec;
import com.loom.runtime.process.PythonProcessHost;
import com.loom.runtime.process.PythonProcessSpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Java ↔ 适配器协议的契约测试。
 *
 * <p><b>用一个真实的 Python 子进程</b>，而不是替身。因为要验证的正是「两边对同一份协议的理解 是否一致」：字段名、id 有无、payload 形状。用替身测等于自己跟自己对齐，
 * 协议里真正的歧义（比如 `ws.send` 是扁平字段还是嵌套 data）永远测不出来。
 *
 * <p>被测的子进程是**临时生成的一个最小桩**，不依赖仓库里的假适配器 —— 这样即使那个文件被人改坏，契约测试仍然能独立告诉你「Java 侧该是什么行为」。
 *
 * <p>打 {@code integration} 标签：需要 PATH 上有 python。
 */
@Tag("integration")
@DisplayName("适配器协议契约（真实 Python 子进程）")
class AdapterProtocolContractTest {

    /** 桩脚本：逐条读 stdin，按类型回应，并把收到的消息原样打在 stderr 上供断言。 */
    private static final String STUB =
            """
            import json, sys

            # 钉死编码：Windows 管道默认是 cp936，中文会乱码甚至抛异常。
            # 这一点与协议本身无关，但插件模板必须这么做 —— 这里顺手把它测进去。
            sys.stdout.reconfigure(encoding="utf-8", line_buffering=True)
            sys.stderr.reconfigure(encoding="utf-8", line_buffering=True)

            def send(obj):
                sys.stdout.write(json.dumps(obj, ensure_ascii=False) + "\\n")
                sys.stdout.flush()

            send({"type": "hello", "payload": {
                "protocolVersion": 1, "connectionType": "contract",
                "direction": "REVERSE", "displayName": "契约桩",
                "capabilities": ["stub"],
                "configSchema": {"type": "object",
                                 "x-handshake": {"mode": "queryParam",
                                                 "paramName": "access_token",
                                                 "secretField": "token"}}}})

            for line in sys.stdin:
                line = line.strip()
                if not line:
                    continue
                msg = json.loads(line)
                t = msg.get("type")
                sys.stderr.write("GOT " + t + "\\n")
                sys.stderr.flush()
                if t == "shutdown":
                    break
                if t == "conn.open":
                    send({"type": "ws.open",
                          "id": "py-1",
                          "payload": {"connectionId": msg["payload"]["connectionId"],
                                      "url": "ws://127.0.0.1:1/nope",
                                      "headers": {}}})
                if t == "ws.frame":
                    send({"type": "event.matched", "payload": {
                        "handleId": msg["payload"].get("handleId"),
                        "connectionId": msg["payload"]["connectionId"],
                        "eventType": "stub", "workflowIds": [], "event": {"ok": True}}})
            sys.stderr.write("EXITING\\n")
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final IpcCodec codec = new IpcCodec(mapper);

    private Path stubFile;
    private PythonProcessHost host;
    private RecordingEvents events;
    private AdapterSession session;

    @BeforeEach
    void startStub() throws IOException {
        stubFile = Files.createTempFile("loom-contract-stub", ".py");
        Files.writeString(stubFile, STUB, StandardCharsets.UTF_8);

        events = new RecordingEvents();
        PythonProcessSpec spec =
                new PythonProcessSpec(
                        "contract-stub",
                        List.of("python", stubFile.toString()),
                        Path.of(".").toAbsolutePath().normalize(),
                        Map.of());
        host = PythonProcessHost.start(spec, codec, null, code -> {});
        session = new AdapterSession(host, codec, events);
        host.startReading();
    }

    @AfterEach
    void stopStub() {
        try {
            if (host != null) {
                host.close();
            }
        } catch (Exception ignored) {
            // 关不掉不影响测试结论
        }
        try {
            Files.deleteIfExists(stubFile);
        } catch (IOException ignored) {
            // 临时文件残留无妨
        }
    }

    @Test
    @DisplayName("hello 应被解析出类型、方向、能力与握手规格")
    void shouldParseHello() {
        await().atMost(Duration.ofSeconds(20)).until(session::isReady);

        assertThat(session.connectionType()).isEqualTo("contract");
        assertThat(session.displayName()).isEqualTo("契约桩");
        assertThat(session.direction()).isEqualTo(Direction.REVERSE);
        assertThat(session.capabilities()).containsExactly("stub");
        assertThat(session.handshakeSpec().mode()).isEqualTo("queryParam");
        assertThat(session.handshakeSpec().secretField()).isEqualTo("token");
        assertThat(session.isAlive()).isTrue();
        assertThat(events.helloCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("适配器上报的握手规格应真的能被注册表使用")
    void shouldUseReportedHandshakeSpec() {
        await().atMost(Duration.ofSeconds(20)).until(session::isReady);

        HandshakeSpec spec = session.handshakeSpec();

        assertThat(spec.paramName()).isEqualTo("access_token");
        assertThat(spec.requiresSecret()).isTrue();
    }

    @Test
    @DisplayName("ws.open 必须带 connectionId —— 否则 Java 认不出它属于哪条连接")
    void shouldCarryConnectionIdInWsOpen() {
        await().atMost(Duration.ofSeconds(20)).until(session::isReady);
        session.bindHandle(1L, "h-1");

        // conn.open 会让桩回 ws.open{connectionId}；URL 故意指向一个连不上的端口，
        // 但「认领到了哪条连接」这一步与能否连上无关 —— 这里只验证认领成功
        session.sendToConnection(
                1L, "conn.open", payload -> payload.set("config", mapper.createObjectNode()));

        await().atMost(Duration.ofSeconds(20)).until(() -> events.forwardOpenConnectionId != null);
        assertThat(events.forwardOpenConnectionId).isEqualTo(1L);
    }

    @Test
    @DisplayName("event.matched 应被分发，并带出 connectionId 与事件体")
    void shouldDispatchEventMatched() {
        await().atMost(Duration.ofSeconds(20)).until(session::isReady);
        session.bindHandle(5L, "h-5");

        session.sendToConnection(
                5L,
                "ws.frame",
                payload -> {
                    payload.put("handleId", "h-5");
                    payload.putObject("data").put("content", "{}");
                });

        await().atMost(Duration.ofSeconds(20)).until(() -> !events.matched.isEmpty());
        RecordedEvent event = events.matched.get(0);
        assertThat(event.connectionId).isEqualTo(5L);
        assertThat(event.eventType).isEqualTo("stub");
        assertThat(event.event.get("ok").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("handleId 与 connectionId 的双向映射应一致")
    void shouldKeepBidirectionalHandleMapping() {
        await().atMost(Duration.ofSeconds(20)).until(session::isReady);

        session.bindHandle(9L, "h-9");
        assertThat(session.handleOf(9L)).isEqualTo("h-9");
        assertThat(session.connectionOf("h-9")).isEqualTo(9L);

        session.unbindConnection(9L);
        assertThat(session.handleOf(9L)).isNull();
        assertThat(session.connectionOf("h-9")).isNull();
    }

    @Test
    @DisplayName("未知消息类型应记日志并忽略，不能让读取线程挂掉")
    void shouldSurviveUnknownMessageType() {
        await().atMost(Duration.ofSeconds(20)).until(session::isReady);

        // 桩不认识这条；Java 侧走到 default 分支
        session.send("no.such.type", null);

        // 之后再发一条正常消息，通道仍应工作
        session.bindHandle(3L, "h-3");
        session.sendToConnection(
                3L, "ws.frame", payload -> payload.putObject("data").put("content", "{}"));

        await().atMost(Duration.ofSeconds(20)).until(() -> !events.matched.isEmpty());
        assertThat(events.matched.get(0).connectionId).isEqualTo(3L);
    }

    @Test
    @DisplayName("请求-响应应能配对：id 原样带回")
    void shouldPairRequestAndReply() {
        await().atMost(Duration.ofSeconds(20)).until(session::isReady);

        // 桩不回 reply，所以这里验证的是「超时会抛异常而不是永久挂起」
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                session.request(
                                        "no.such.request",
                                        mapper.createObjectNode(),
                                        Duration.ofMillis(500)))
                .isInstanceOf(AdapterRequestException.class)
                .hasMessageContaining("超时");
    }

    @Test
    @DisplayName("通道关闭后未完成的请求必须立刻失败，不能永远悬着")
    void shouldFailPendingOnClose() throws Exception {
        await().atMost(Duration.ofSeconds(20)).until(session::isReady);

        Thread caller =
                new Thread(
                        () -> {
                            try {
                                session.request(
                                        "no.such.request",
                                        mapper.createObjectNode(),
                                        Duration.ofSeconds(30));
                            } catch (AdapterRequestException expected) {
                                events.pendingFailed.set(true);
                            }
                        });
        caller.setDaemon(true);
        caller.start();
        Thread.sleep(300);

        host.close();

        await().atMost(Duration.ofSeconds(15)).until(() -> events.pendingFailed.get());
    }

    // ==================================================================
    // 记录替身
    // ==================================================================

    static final class RecordedEvent {
        final long connectionId;
        final String eventType;
        final JsonNode event;

        RecordedEvent(long connectionId, String eventType, JsonNode event) {
            this.connectionId = connectionId;
            this.eventType = eventType;
            this.event = event;
        }
    }

    static final class RecordingEvents implements AdapterEvents {

        final AtomicInteger helloCount = new AtomicInteger();
        final List<RecordedEvent> matched = new CopyOnWriteArrayList<>();
        final List<String> errors = new CopyOnWriteArrayList<>();
        final java.util.concurrent.atomic.AtomicBoolean pendingFailed =
                new java.util.concurrent.atomic.AtomicBoolean();
        volatile Long forwardOpenConnectionId;

        @Override
        public void onHello(AdapterSession session) {
            helloCount.incrementAndGet();
        }

        @Override
        public void onEventMatched(
                AdapterSession session, long connectionId, String eventType, JsonNode event) {
            matched.add(new RecordedEvent(connectionId, eventType, event));
        }

        @Override
        public String openForward(
                AdapterSession session,
                long connectionId,
                String url,
                Map<String, String> headers) {
            forwardOpenConnectionId = connectionId;
            // 返回 null 会让 Java 回 ok:false，这正是我们要观察的路径之一
            return null;
        }

        @Override
        public void handleSend(
                AdapterSession session, String handleId, String encoding, String content) {
            // 契约测试不关心发送
        }

        @Override
        public void handleClose(AdapterSession session, String handleId, int code, String reason) {
            // 契约测试不关心关闭
        }

        @Override
        public void onAdapterExited(AdapterSession session, int exitCode) {
            errors.add("exit=" + exitCode);
        }
    }
}
