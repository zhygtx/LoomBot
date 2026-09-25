package com.loom.connection.adapter;

import com.loom.connection.domain.Direction;
import com.loom.connection.handshake.HandshakeSpec;
import com.loom.runtime.ipc.IpcChannel;
import com.loom.runtime.ipc.IpcCodec;
import com.loom.runtime.ipc.IpcListener;
import com.loom.runtime.ipc.IpcMessage;
import com.loom.runtime.process.PythonProcessHost;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Java 侧的**适配器视图** —— 一个正在运行的协议适配器进程。
 *
 * <h2>职责</h2>
 *
 * <ul>
 *   <li>持有进程宿主与 IPC 通道
 *   <li>分发来自适配器的消息（{@code hello} / {@code event.matched} / {@code ws.open} / …）
 *   <li>管理 Java → 适配器的请求-响应配对
 * </ul>
 *
 * <h2>为什么请求必须带超时</h2>
 *
 * <p>适配器是独立进程，可能崩溃、卡死、或实现有 bug 忘了回复。 没有超时的话调用方线程会永久阻塞，最终拖垮 JVM 的线程池。
 *
 * <h2>为什么 handle 映射放在这里</h2>
 *
 * <p>{@code handleId → connectionId} 是**适配器视角**的映射（适配器按 handleId 认通道）。 真正的 WebSocket 会话对象仍在 {@code
 * ConnectionManager} 手里 —— 本类只管协议层的对应关系。
 */
public final class AdapterSession implements IpcListener {

    private static final Logger log = LoggerFactory.getLogger(AdapterSession.class);

    /** Java → 适配器请求的默认超时。 */
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final PythonProcessHost host;
    private final IpcChannel channel;
    private final IpcCodec codec;
    private final AdapterEvents events;

    private final Map<String, CompletableFuture<IpcMessage>> pending = new ConcurrentHashMap<>();
    private final Map<Long, String> handleByConnection = new ConcurrentHashMap<>();
    private final Map<String, Long> connectionByHandle = new ConcurrentHashMap<>();
    private final AtomicLong requestSeq = new AtomicLong();

    /** 元信息，来自 {@code hello}；未上报前为 null。 */
    private volatile String connectionType;

    private volatile String displayName;
    private volatile Direction direction;
    private volatile JsonNode configSchema;
    private volatile HandshakeSpec handshakeSpec = HandshakeSpec.NONE;
    private volatile List<String> capabilities = List.of();
    private volatile boolean ready;

    public AdapterSession(PythonProcessHost host, IpcCodec codec, AdapterEvents events) {
        this.host = host;
        this.channel = host.channel();
        this.codec = codec;
        this.events = events;
        this.channel.setListener(this);
    }

    // ------------------------------------------------------------------
    // 元信息
    // ------------------------------------------------------------------

    public String connectionType() {
        return connectionType;
    }

    public String displayName() {
        return displayName == null ? connectionType : displayName;
    }

    public Direction direction() {
        return direction;
    }

    public JsonNode configSchema() {
        return configSchema;
    }

    public HandshakeSpec handshakeSpec() {
        return handshakeSpec;
    }

    public List<String> capabilities() {
        return capabilities;
    }

    /** 是否已完成 {@code hello} 上报。未就绪时不应给它派发连接。 */
    public boolean isReady() {
        return ready;
    }

    public boolean isAlive() {
        return host.isAlive();
    }

    public long pid() {
        return host.pid();
    }

    public String name() {
        return host.name();
    }

    public long protocolViolations() {
        return channel.protocolViolations();
    }

    // ------------------------------------------------------------------
    // 通道映射
    // ------------------------------------------------------------------

    public void bindHandle(long connectionId, String handleId) {
        handleByConnection.put(connectionId, handleId);
        connectionByHandle.put(handleId, connectionId);
    }

    public void unbindConnection(long connectionId) {
        String handleId = handleByConnection.remove(connectionId);
        if (handleId != null) {
            connectionByHandle.remove(handleId);
        }
    }

    public String handleOf(long connectionId) {
        return handleByConnection.get(connectionId);
    }

    public Long connectionOf(String handleId) {
        return connectionByHandle.get(handleId);
    }

    // ------------------------------------------------------------------
    // 发送
    // ------------------------------------------------------------------

    /** 单向发送，不等待回复。 */
    public void send(String type, JsonNode payload) {
        channel.send(IpcMessage.of(type, payload));
    }

    /** 单向发送给某连接，自动填充 connectionId。 */
    public void sendToConnection(long connectionId, String type, Consumer<ObjectNode> filler) {
        ObjectNode payload = codec.newPayload();
        payload.put("connectionId", String.valueOf(connectionId));
        if (filler != null) {
            filler.accept(payload);
        }
        send(type, payload);
    }

    /**
     * 请求-响应。等待适配器回 {@code reply}，超时或失败抛异常。
     *
     * @throws AdapterRequestException 超时、通道断开、或适配器回了 {@code ok:false}
     */
    public JsonNode request(String type, JsonNode payload, Duration timeout) {
        String id = "req-" + requestSeq.incrementAndGet();
        CompletableFuture<IpcMessage> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            channel.send(IpcMessage.request(id, type, payload));
            long millis = (timeout == null ? DEFAULT_REQUEST_TIMEOUT : timeout).toMillis();
            IpcMessage reply = future.get(millis, TimeUnit.MILLISECONDS);
            JsonNode body = reply.payload();
            if (body != null && body.has("ok") && !body.get("ok").asBoolean()) {
                String error = body.hasNonNull("error") ? body.get("error").asString() : "适配器返回失败";
                throw new AdapterRequestException(type + " 失败: " + error);
            }
            return body;
        } catch (TimeoutException e) {
            throw new AdapterRequestException(type + " 超时（适配器未在限定时间内回复）", e);
        } catch (ExecutionException e) {
            throw new AdapterRequestException(type + " 失败", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AdapterRequestException(type + " 被中断", e);
        } finally {
            pending.remove(id);
        }
    }

    // ------------------------------------------------------------------
    // IpcListener
    // ------------------------------------------------------------------

    @Override
    public void onMessage(IpcMessage message) {
        try {
            dispatch(message);
        } catch (RuntimeException e) {
            log.error("[{}] 处理消息 {} 时异常", name(), message.type(), e);
        }
    }

    private void dispatch(IpcMessage message) {
        switch (message.type()) {
            case IpcMessage.TYPE_REPLY -> completeReply(message);
            case "hello" -> handleHello(message);
            case "event.matched" -> handleEventMatched(message);
            case "ws.open" -> handleOpenForward(message);
            case "ws.send" ->
                    events.handleSend(
                            this,
                            message.stringField("handleId"),
                            text(message, "encoding", "text"),
                            text(message, "content", null));
            case "ws.close" ->
                    events.handleClose(
                            this,
                            message.stringField("handleId"),
                            intField(message, "code", 1000),
                            text(message, "reason", null));
            case "error" ->
                    log.error(
                            "[{}] 适配器报错: {} / {}",
                            name(),
                            text(message, "message", "?"),
                            text(message, "detail", ""));
            default -> log.warn("[{}] 收到未知消息类型: {}", name(), message.type());
        }
    }

    private void completeReply(IpcMessage message) {
        CompletableFuture<IpcMessage> future = pending.remove(message.id());
        if (future == null) {
            log.warn("[{}] 收到无主的 reply（id={}），可能已超时", name(), message.id());
            return;
        }
        future.complete(message);
    }

    private void handleHello(IpcMessage message) {
        this.connectionType = message.stringField("connectionType");
        this.displayName = message.stringField("displayName");
        this.direction = Direction.parse(message.stringField("direction"));
        this.configSchema = message.field("configSchema");
        this.handshakeSpec = HandshakeSpec.from(configSchema);

        JsonNode caps = message.field("capabilities");
        if (caps != null && caps.isArray()) {
            List<String> list = new ArrayList<>();
            caps.forEach(node -> list.add(node.asString()));
            this.capabilities = List.copyOf(list);
        }

        if (connectionType == null || connectionType.isBlank()) {
            log.error("[{}] hello 未声明 connectionType，适配器不可用", name());
            return;
        }
        if (direction == null) {
            log.error("[{}] hello 的 direction 非法（只能是 REVERSE / FORWARD），适配器不可用", name());
            return;
        }
        this.ready = true;
        log.info(
                "[{}] 适配器就绪: type={}, direction={}, capabilities={}, protocolVersion={}",
                name(),
                connectionType,
                direction,
                capabilities,
                message.field("protocolVersion"));
        events.onHello(this);
    }

    private void handleEventMatched(IpcMessage message) {
        String raw = message.stringField("connectionId");
        Long connectionId = parseLong(raw);
        if (connectionId == null) {
            log.warn("[{}] event.matched 缺少合法的 connectionId: {}", name(), raw);
            return;
        }
        events.onEventMatched(
                this, connectionId, message.stringField("eventType"), message.field("event"));
    }

    private void handleOpenForward(IpcMessage message) {
        String url = message.stringField("url");
        Long connectionId = resolveConnectionId(message);
        if (connectionId == null) {
            replyFailure(message.id(), "无法确定该请求属于哪个连接（ws.open 需带 handleId 或 connectionId）");
            return;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        JsonNode headersNode = message.field("headers");
        if (headersNode != null && headersNode.isObject()) {
            headersNode
                    .properties()
                    .forEach(entry -> headers.put(entry.getKey(), entry.getValue().asString()));
        }
        String handleId = events.openForward(this, connectionId, url, headers);
        if (handleId == null) {
            replyFailure(message.id(), "建立正向连接失败: " + url);
            return;
        }
        bindHandle(connectionId, handleId);
        ObjectNode payload = codec.newPayload();
        payload.put("ok", true);
        payload.put("handleId", handleId);
        channel.send(new IpcMessage(message.id(), IpcMessage.TYPE_REPLY, payload));
    }

    private Long resolveConnectionId(IpcMessage message) {
        String handleId = message.stringField("handleId");
        if (handleId != null) {
            Long fromHandle = connectionOf(handleId);
            if (fromHandle != null) {
                return fromHandle;
            }
        }
        return parseLong(message.stringField("connectionId"));
    }

    private void replyFailure(String id, String error) {
        ObjectNode payload = codec.newPayload();
        payload.put("ok", false);
        payload.put("error", error);
        channel.send(new IpcMessage(id, IpcMessage.TYPE_REPLY, payload));
    }

    @Override
    public void onClosed(Throwable cause) {
        // 通道断开 → 所有未完成的请求立即失败，不能永远挂起
        AdapterRequestException failure =
                new AdapterRequestException(
                        "适配器通道已关闭" + (cause == null ? "" : ": " + cause.getMessage()), cause);
        pending.values().forEach(future -> future.completeExceptionally(failure));
        pending.clear();
        if (cause != null) {
            log.warn("[{}] 适配器连接断开", name(), cause);
        }
    }

    /** 由管理器在进程退出时调用，把退出事件转给上层做退避重启。 */
    public void notifyExited(int exitCode) {
        events.onAdapterExited(this, exitCode);
    }

    public void close() {
        host.close();
    }

    // ------------------------------------------------------------------

    private String text(IpcMessage message, String field, String fallback) {
        String value = message.stringField(field);
        return value == null ? fallback : value;
    }

    private int intField(IpcMessage message, String field, int fallback) {
        JsonNode node = message.field(field);
        return node == null || !node.isNumber() ? fallback : node.asInt();
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
