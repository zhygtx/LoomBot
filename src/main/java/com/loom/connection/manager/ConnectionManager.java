package com.loom.connection.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.loom.config.AdapterProperties;
import com.loom.connection.adapter.AdapterEvents;
import com.loom.connection.adapter.AdapterSession;
import com.loom.connection.domain.ConnectionState;
import com.loom.connection.domain.ConnectionStatus;
import com.loom.connection.domain.ConnectionTypeDescriptor;
import com.loom.connection.domain.Direction;
import com.loom.connection.domain.WsConnection;
import com.loom.connection.handshake.HandshakeRequest;
import com.loom.connection.handshake.HandshakeSpec;
import com.loom.connection.handshake.HandshakeValidatorRegistry;
import com.loom.connection.mapper.WsConnectionMapper;
import com.loom.runtime.ipc.IpcCodec;
import com.loom.runtime.process.PythonProcessHost;
import com.loom.runtime.process.PythonProcessSpec;
import com.loom.runtime.process.PythonProcessStartException;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 连接管理器 —— 运行时状态机的唯一权威。
 *
 * <h2>托管的东西</h2>
 *
 * <ul>
 *   <li>适配器进程的启动、崩溃退避重启
 *   <li>每条连接的运行时状态与状态迁移
 *   <li>WebSocket 通道（正向客户端 / 反向服务端）的生命周期
 *   <li>断线重连调度
 * </ul>
 *
 * <h2>三条容易写错、这里刻意处理了的规则</h2>
 *
 * <ol>
 *   <li><b>适配器未就绪不拒绝连接</b>：状态置 {@code WAITING_ADAPTER}， 适配器 {@code hello} 后自动拉起。否则启动顺序会把连接卡死。
 *   <li><b>被取代的会话不触发重连</b>：平台重连时新旧会话会重叠， 旧会话关闭必须被识别为「被取代」而不是「断线」，否则重连风暴。
 *   <li><b>重连无限但退避封顶</b>：bot 应当始终在线，所以要一直重试； 但间隔要封顶，否则一个连不上的配置会打爆 CPU。
 * </ol>
 */
@Component
public class ConnectionManager implements AdapterEvents {

    private static final Logger log = LoggerFactory.getLogger(ConnectionManager.class);

    /** 正向连接握手超时。 */
    private static final Duration FORWARD_HANDSHAKE_TIMEOUT = Duration.ofSeconds(15);

    private final WsConnectionMapper mapper;
    private final IpcCodec codec;
    private final ObjectMapper objectMapper;
    private final AdapterProperties adapterProperties;
    private final ReverseEndpointRegistry endpoints;
    private final HandshakeValidatorRegistry handshakeValidators;
    private final WebSocketClient webSocketClient = new StandardWebSocketClient();

    /** connectionType → 适配器会话。 */
    private final Map<String, AdapterSession> adaptersByType = new ConcurrentHashMap<>();

    /** 进程名 → 适配器会话。 */
    private final Map<String, AdapterSession> adaptersByName = new ConcurrentHashMap<>();

    /** 进程名 → 脚本路径，重启时用。 */
    private final Map<String, String> adapterScripts = new ConcurrentHashMap<>();

    /** 进程名 → 已重启次数，用于退避。 */
    private final Map<String, Integer> adapterRestarts = new ConcurrentHashMap<>();

    private final Map<Long, ConnectionRuntime> runtimes = new ConcurrentHashMap<>();
    private final Map<String, ConnectionHandle> handles = new ConcurrentHashMap<>();
    private final AtomicLong handleSeq = new AtomicLong();
    private final ScheduledExecutorService scheduler;

    private volatile boolean shuttingDown;

    public ConnectionManager(
            WsConnectionMapper mapper,
            IpcCodec codec,
            ObjectMapper objectMapper,
            AdapterProperties adapterProperties,
            ReverseEndpointRegistry endpoints,
            HandshakeValidatorRegistry handshakeValidators) {
        this.mapper = mapper;
        this.codec = codec;
        this.objectMapper = objectMapper;
        this.adapterProperties = adapterProperties;
        this.endpoints = endpoints;
        this.handshakeValidators = handshakeValidators;
        ThreadFactory factory =
                runnable -> {
                    Thread thread = new Thread(runnable, "connection-manager");
                    thread.setDaemon(true);
                    return thread;
                };
        this.scheduler = Executors.newScheduledThreadPool(2, factory);
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        List<String> scripts = adapterProperties.scripts();
        if (scripts.isEmpty()) {
            log.warn("未配置任何适配器脚本（loom.adapter.scripts），连接将一直处于「等待适配器」状态");
            return;
        }
        for (String script : scripts) {
            launchAdapter(script);
        }
    }

    @PreDestroy
    public void shutdown() {
        shuttingDown = true;
        log.info("正在关闭连接管理器…");
        scheduler.shutdownNow();
        runtimes.values().forEach(this::teardown);
        adaptersByName.values().forEach(AdapterSession::close);
        handles.values().forEach(handle -> handle.close(CloseStatus.GOING_AWAY));
        log.info("连接管理器已关闭");
    }

    // ==================================================================
    // 适配器进程
    // ==================================================================

    private void launchAdapter(String scriptPath) {
        String name = adapterName(scriptPath);
        PythonProcessSpec spec =
                new PythonProcessSpec(
                        name,
                        List.of(adapterProperties.pythonCommand(), scriptPath),
                        Path.of(".").toAbsolutePath().normalize(),
                        Map.of());
        PythonProcessHost host;
        try {
            host =
                    PythonProcessHost.start(
                            spec, codec, null, exitCode -> onAdapterProcessExit(name, exitCode));
        } catch (PythonProcessStartException e) {
            log.error("[{}] 适配器启动失败: {}", name, e.getMessage());
            scheduleAdapterRestart(name, scriptPath);
            return;
        }
        // 先建会话（顺带把监听者装上），再开始读 —— 否则适配器立即上报的 hello 会丢
        AdapterSession session = new AdapterSession(host, codec, this);
        adaptersByName.put(name, session);
        adapterScripts.put(name, scriptPath);
        host.startReading();
    }

    private void onAdapterProcessExit(String name, int exitCode) {
        AdapterSession session = adaptersByName.remove(name);
        if (session != null) {
            adaptersByType.remove(session.connectionType(), session);
            degradeConnectionsOf(session, "适配器进程退出（exitCode=" + exitCode + "）");
        }
        if (shuttingDown) {
            return;
        }
        String scriptPath = adapterScripts.get(name);
        if (scriptPath != null) {
            scheduleAdapterRestart(name, scriptPath);
        }
    }

    private void scheduleAdapterRestart(String name, String scriptPath) {
        int attempt = adapterRestarts.merge(name, 1, Integer::sum);
        long delay =
                backoff(
                        attempt,
                        adapterProperties.restartBackoffInitialMs(),
                        adapterProperties.restartBackoffMaxMs());
        log.warn("[{}] 将在 {}ms 后重启适配器（第 {} 次）", name, delay, attempt);
        scheduler.schedule(
                () -> {
                    if (!shuttingDown) {
                        launchAdapter(scriptPath);
                    }
                },
                delay,
                TimeUnit.MILLISECONDS);
    }

    private static String adapterName(String scriptPath) {
        String normalized = scriptPath.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String file = slash < 0 ? normalized : normalized.substring(slash + 1);
        String base = file.endsWith(".py") ? file.substring(0, file.length() - 3) : file;
        return "adapter-" + base;
    }

    private static long backoff(int attempt, long initial, long max) {
        // 指数退避，指数封顶防止 long 溢出
        int exponent = Math.min(Math.max(attempt - 1, 0), 20);
        long delay = initial * (1L << exponent);
        return Math.min(delay <= 0 ? max : delay, max);
    }

    // ==================================================================
    // AdapterEvents
    // ==================================================================

    @Override
    public void onHello(AdapterSession session) {
        adapterRestarts.remove(session.name());
        adaptersByType.put(session.connectionType(), session);
        log.info("[{}] 适配器注册成功: connectionType={}", session.name(), session.connectionType());
        startEnabledConnectionsOfType(session);
    }

    @Override
    public void onEventMatched(
            AdapterSession session, long connectionId, String eventType, JsonNode event) {
        // 工作流派发是后续实现；这里先如实记录，证明「只有命中的事件才会上抛」
        log.info(
                "[{}] 事件命中: connectionId={}, eventType={}, event={}",
                session.name(),
                connectionId,
                eventType,
                event);
    }

    @Override
    public String openForward(
            AdapterSession session, long connectionId, String url, Map<String, String> headers) {
        ConnectionRuntime runtime = runtimes.get(connectionId);
        if (runtime == null) {
            log.warn("[{}] ws.open 指向未知连接 {}", session.name(), connectionId);
            return null;
        }
        if (url == null || url.isBlank()) {
            runtime.reconnecting("适配器未提供连接地址");
            scheduleReconnect(runtime);
            return null;
        }
        try {
            ForwardClientHandler handler = new ForwardClientHandler(this, connectionId);
            WebSocketHttpHeaders wsHeaders = new WebSocketHttpHeaders();
            headers.forEach(wsHeaders::add);
            WebSocketSession wsSession =
                    webSocketClient
                            .execute(handler, wsHeaders, URI.create(url))
                            .get(FORWARD_HANDSHAKE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            String handleId = "h-" + connectionId + "-" + handleSeq.incrementAndGet();
            ConnectionHandle handle = new ConnectionHandle(handleId, connectionId, wsSession);
            handler.attach(handle);
            // 注意 try 的作用域边界：从这里到 return 之间**不允许**出现会抛异常的逻辑。
            // 两个 catch 都不清理 handles —— 那样是安全的，因为此刻唯一的抛点在
            // .get(timeout)，而那发生在 handles.put 之前；且残留句柄会被下一次
            // replaceHandle（或 closeCurrentHandle）回收。若将来要在 online() 之后插入
            // 可能抛异常的逻辑，必须把 try 收窄到只包住上面的网络握手。
            handles.put(handleId, handle);
            replaceHandle(runtime, handle);
            runtime.online();
            log.info("[{}] 正向连接已建立: {}", runtime.name, url);
            return handleId;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            runtime.reconnecting("建立正向连接被中断");
            scheduleReconnect(runtime);
            return null;
        } catch (Exception e) {
            String reason = e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
            log.warn("[{}] 正向连接失败: {}", runtime.name, reason);
            runtime.reconnecting("建立正向连接失败: " + reason);
            scheduleReconnect(runtime);
            return null;
        }
    }

    @Override
    public void handleSend(
            AdapterSession session, String handleId, String encoding, String content) {
        ConnectionHandle handle = handles.get(handleId);
        if (handle == null) {
            log.warn("[{}] ws.send 指向未知通道 {}", session.name(), handleId);
            return;
        }
        if ("base64".equalsIgnoreCase(encoding)) {
            handle.sendBinary(java.util.Base64.getDecoder().decode(content));
        } else {
            handle.sendText(content);
        }
    }

    @Override
    public void handleClose(AdapterSession session, String handleId, int code, String reason) {
        ConnectionHandle handle = handles.get(handleId);
        if (handle != null) {
            handle.close(code, reason);
        }
    }

    @Override
    public void onAdapterExited(AdapterSession session, int exitCode) {
        // 进程退出的处理在 onAdapterProcessExit（那里能拿到进程名），这里只做记录
        log.warn("[{}] 适配器进程已退出，exitCode={}", session.name(), exitCode);
    }

    // ==================================================================
    // 连接运行时操作
    // ==================================================================

    /** 启动（或重启）一条连接。会重新从库里读配置。 */
    public void start(long connectionId) {
        WsConnection entity = mapper.selectById(connectionId);
        if (entity == null) {
            log.warn("连接 {} 不存在，跳过启动", connectionId);
            return;
        }
        ConnectionRuntime runtime = runtimeOf(entity);
        runtime.enabled = true;
        attemptConnect(runtime);
    }

    /** 停止一条连接：取消重连、关通道、注销端点。幂等。 */
    public void stop(long connectionId) {
        ConnectionRuntime runtime = runtimes.get(connectionId);
        if (runtime == null) {
            return;
        }
        runtime.enabled = false;
        teardown(runtime);
        log.info("[{}] 连接已停用", runtime.name);
    }

    /** 配置变更：刷新内存中的 config，并通知适配器（由它决定是否需要重连）。 */
    public void reload(long connectionId) {
        ConnectionRuntime runtime = runtimes.get(connectionId);
        if (runtime == null) {
            return;
        }
        WsConnection entity = mapper.selectById(connectionId);
        if (entity == null) {
            return;
        }
        runtime.config = entity.getConfig();
        AdapterSession adapter = adaptersByType.get(runtime.connectionType);
        if (adapter == null) {
            return;
        }
        if (runtime.enabled) {
            adapter.sendToConnection(
                    connectionId,
                    "conn.reload",
                    payload -> {
                        putJson(payload, "config", runtime.config);
                        payload.put("endpointPath", runtime.endpointPath);
                    });
        }
        if (adapter.direction() == Direction.FORWARD) {
            // 正向连接的地址可能由 config 派生 → 必须重连
            log.info("[{}] 配置变更，正向连接需要重连", runtime.name);
            closeCurrentHandle(runtime, "配置变更");
            runtime.reconnecting("配置变更");
            // 必须重置 adapterNotified，否则重连时会跳过 conn.open ——
            // attemptConnect 只在 !adapterNotified 时才发它，而这个标志此前只在
            // 「适配器进程退出」时被清掉。结果是：断开发生了，但没人告诉适配器
            // 「这条连接需要重建」，适配器也就不会重新发 ws.open，
            // 连接安静地停在 RECONNECTING 上，永远回不来。
            runtime.adapterNotified = false;
            scheduleReconnect(runtime);
        }
    }

    /** 连接被删除：清理运行时痕迹。 */
    public void forget(long connectionId) {
        ConnectionRuntime runtime = runtimes.remove(connectionId);
        if (runtime != null) {
            runtime.enabled = false;
            teardown(runtime);
        }
    }

    public ConnectionStatus status(long connectionId) {
        ConnectionRuntime runtime = runtimes.get(connectionId);
        if (runtime == null) {
            return new ConnectionStatus(connectionId, false, ConnectionState.OFFLINE, null, 0);
        }
        return new ConnectionStatus(
                connectionId,
                runtime.enabled,
                runtime.state,
                runtime.failureReason,
                runtime.consecutiveFailures);
    }

    public List<ConnectionStatus> statuses() {
        return runtimes.values().stream().map(runtime -> status(runtime.connectionId)).toList();
    }

    // ==================================================================
    // 连接类型（适配器声明）
    // ==================================================================

    /**
     * 当前**所有已知**的连接类型。
     *
     * <p>包含尚未就绪的适配器吗？不包含 —— {@code adaptersByType} 只在收到 {@code hello} 后写入，
     * 所以这里的每一项都是「已握手成功」的适配器声明的。{@link ConnectionTypeDescriptor#ready()} 区分的是「进程还活着」与「进程已退出」这两种情况。
     */
    public List<ConnectionTypeDescriptor> connectionTypes() {
        return adaptersByType.values().stream()
                .map(ConnectionManager::describe)
                .sorted(java.util.Comparator.comparing(ConnectionTypeDescriptor::type))
                .toList();
    }

    /** 查某个类型，用于创建连接时判定方向。 */
    public java.util.Optional<ConnectionTypeDescriptor> connectionType(String type) {
        AdapterSession session = type == null ? null : adaptersByType.get(type);
        return session == null
                ? java.util.Optional.empty()
                : java.util.Optional.of(describe(session));
    }

    private static ConnectionTypeDescriptor describe(AdapterSession session) {
        return new ConnectionTypeDescriptor(
                session.connectionType(),
                session.displayName(),
                session.direction(),
                session.configSchema(),
                session.handshakeSpec(),
                session.capabilities(),
                session.name(),
                session.isReady() && session.isAlive());
    }

    // ==================================================================
    // 反向端点
    // ==================================================================

    /**
     * 校验反向连接的握手。
     *
     * <p>由 {@code ReverseHandshakeInterceptor} 在 WebSocket 升级阶段调用 —— 校验不通过必须拒绝升级，不能先建立连接再断开。
     */
    public ReverseHandshakeResult validateReverseHandshake(String path, HandshakeRequest request) {
        Long connectionId = endpoints.connectionIdOf(path);
        if (connectionId == null) {
            return ReverseHandshakeResult.reject("路径未注册: " + path);
        }
        ConnectionRuntime runtime = runtimes.get(connectionId);
        if (runtime == null || !runtime.enabled) {
            return ReverseHandshakeResult.reject("连接不存在或已停用");
        }
        AdapterSession adapter = adaptersByType.get(runtime.connectionType);
        if (adapter == null) {
            return ReverseHandshakeResult.reject("适配器未就绪");
        }
        HandshakeSpec spec = adapter.handshakeSpec();
        String reason = handshakeValidators.validate(spec, secretOf(runtime.config, spec), request);
        if (reason != null) {
            return ReverseHandshakeResult.reject(reason);
        }
        return ReverseHandshakeResult.accept(connectionId);
    }

    /**
     * 平台连入后由 WS handler 调用，把新会话接管为当前通道。
     *
     * <p>返回新建的句柄，调用方必须把它**存进该 session 的 attributes**。 不能事后再用 {@link #currentHandleOf(long)} 反查 ——
     * 会话随时可能被更新的会话取代， 那时反查拿到的是新句柄，旧会话上收到的帧就会被错误地记到新句柄名下。
     */
    public ConnectionHandle onReverseSessionOpened(long connectionId, WebSocketSession session) {
        ConnectionRuntime runtime = runtimes.get(connectionId);
        if (runtime == null) {
            log.warn("反向会话到达未知连接 {}，直接关闭", connectionId);
            closeQuietly(session);
            return null;
        }
        String handleId = "h-" + connectionId + "-" + handleSeq.incrementAndGet();
        ConnectionHandle handle = new ConnectionHandle(handleId, connectionId, session);
        handles.put(handleId, handle);
        replaceHandle(runtime, handle);
        runtime.online();

        // 反向连接的 handleId 由 Java 单方面生成，适配器无从得知 —— 而它的每一次
        // ws.send / ws.frame 都以 handleId 为键。所以必须主动下发，否则反向连接
        // 永远只能收不能发。（正向连接不需要这一步：适配器自己发 ws.open，Java 在 reply 里带回 handleId。）
        AdapterSession adapter = adaptersByType.get(runtime.connectionType);
        if (adapter != null) {
            adapter.bindHandle(connectionId, handleId);
            adapter.sendToConnection(
                    connectionId, "ws.opened", payload -> payload.put("handleId", handleId));
        } else {
            log.warn("[{}] 平台已连入，但适配器未就绪，适配器将拿不到 handleId", runtime.name);
        }
        log.info("[{}] 平台已连入，handleId={}", runtime.name, handleId);
        return handle;
    }

    /** 通道上收到一帧 —— 转给该连接对应的适配器。 */
    public void onFrame(ConnectionHandle handle, String payload) {
        ConnectionRuntime runtime = runtimes.get(handle.connectionId());
        if (runtime == null) {
            return;
        }
        // 静默检测的数据源（D55）。这里**无法区分心跳帧与业务帧** ——
        // 将来若要按「有没有真实业务事件」告警，必须在这个入口做区分，而不是调个阈值了事。
        runtime.lastFrameAt = System.currentTimeMillis();
        AdapterSession adapter = adaptersByType.get(runtime.connectionType);
        if (adapter == null) {
            log.warn("[{}] 收到数据但适配器未就绪，丢弃", runtime.name);
            return;
        }
        adapter.sendToConnection(
                handle.connectionId(),
                "ws.frame",
                holder -> {
                    holder.put("handleId", handle.handleId());
                    ObjectNode data = holder.putObject("data");
                    data.put("encoding", "text");
                    data.put("content", payload);
                });
    }

    /** 取某连接当前活跃的通道句柄；没有则返回 {@code null}。 */
    public ConnectionHandle currentHandleOf(long connectionId) {
        ConnectionRuntime runtime = runtimes.get(connectionId);
        return runtime == null ? null : runtime.handle;
    }

    /** 通道关闭 —— 决定是「被取代」还是「断线」。 */
    public void onHandleClosed(ConnectionHandle handle, String reason) {
        handles.remove(handle.handleId());
        ConnectionRuntime runtime = runtimes.get(handle.connectionId());
        if (runtime == null) {
            return;
        }
        if (handle.isSuperseded()) {
            // 关键分支：被新会话取代的旧会话关闭，绝不能触发重连，否则形成重连风暴
            log.debug("[{}] 被取代的旧会话关闭，不触发重连", runtime.name);
            return;
        }
        if (runtime.handle != handle) {
            log.debug("[{}] 非当前会话关闭，忽略", runtime.name);
            return;
        }
        runtime.handle = null;
        if (!runtime.enabled || shuttingDown) {
            runtime.offline();
            return;
        }
        runtime.reconnecting("连接断开: " + reason);
        scheduleReconnect(runtime);
    }

    // ==================================================================
    // 内部：状态迁移
    // ==================================================================

    private ConnectionRuntime runtimeOf(WsConnection entity) {
        ConnectionRuntime runtime =
                runtimes.computeIfAbsent(
                        entity.getId(),
                        id ->
                                new ConnectionRuntime(
                                        id, entity.getName(), entity.getConnectionType()));
        runtime.config = entity.getConfig();
        runtime.endpointPath = entity.getEndpointPath();
        return runtime;
    }

    private void attemptConnect(ConnectionRuntime runtime) {
        if (!runtime.enabled || shuttingDown) {
            return;
        }
        AdapterSession adapter = adaptersByType.get(runtime.connectionType);
        if (adapter == null || !adapter.isReady()) {
            runtime.waitingAdapter();
            return;
        }
        cancelRetry(runtime);

        if (adapter.direction() == Direction.REVERSE) {
            if (runtime.endpointPath == null || runtime.endpointPath.isBlank()) {
                runtime.failed("反向连接缺少接入路径");
                log.error("[{}] 反向连接缺少 endpointPath，无法注册端点", runtime.name);
                return;
            }
            if (!endpoints.register(runtime.endpointPath, runtime.connectionId)) {
                runtime.failed("接入路径冲突: " + runtime.endpointPath);
                return;
            }
            // 反向连接的端点在退避期间一直是注册着的，所以平台完全可能在计时器到期
            // 之前就连进来了。那种情况下 handle 已存在、状态已是 ONLINE —— 这里若无条件
            // 写 LISTENING 就会把在线状态「降级」，表现为「平台明明连着，界面却显示等待连入」。
            if (runtime.handle == null) {
                runtime.state = ConnectionState.LISTENING;
                runtime.failureReason = null;
                log.info("[{}] 反向端点已就绪，等待平台连入: {}", runtime.name, runtime.endpointPath);
            } else {
                log.debug("[{}] 平台已在退避期间连入，保持 ONLINE 不降级", runtime.name);
            }
        } else {
            runtime.state = ConnectionState.CONNECTING;
        }

        if (!runtime.adapterNotified) {
            runtime.adapterNotified = true;
            adapter.sendToConnection(
                    runtime.connectionId,
                    "conn.open",
                    payload -> {
                        putJson(payload, "config", runtime.config);
                        payload.put("endpointPath", runtime.endpointPath);
                    });
        }
    }

    private void scheduleReconnect(ConnectionRuntime runtime) {
        if (!runtime.enabled || shuttingDown) {
            return;
        }
        cancelRetry(runtime);
        long delay =
                backoff(
                        runtime.consecutiveFailures,
                        adapterProperties.restartBackoffInitialMs(),
                        adapterProperties.restartBackoffMaxMs());
        log.info("[{}] {}ms 后重连（连续失败 {} 次）", runtime.name, delay, runtime.consecutiveFailures);
        runtime.pendingRetry =
                scheduler.schedule(
                        () -> {
                            try {
                                attemptConnect(runtime);
                            } catch (RuntimeException e) {
                                log.error("[{}] 重连时异常", runtime.name, e);
                            }
                        },
                        delay,
                        TimeUnit.MILLISECONDS);
    }

    private void cancelRetry(ConnectionRuntime runtime) {
        ScheduledFuture<?> pending = runtime.pendingRetry;
        if (pending != null) {
            pending.cancel(false);
            runtime.pendingRetry = null;
        }
    }

    private void teardown(ConnectionRuntime runtime) {
        cancelRetry(runtime);
        closeCurrentHandle(runtime, "连接已停用");
        endpoints.unregister(runtime.endpointPath, runtime.connectionId);
        AdapterSession adapter = adaptersByType.get(runtime.connectionType);
        if (adapter != null) {
            adapter.unbindConnection(runtime.connectionId);
            adapter.sendToConnection(runtime.connectionId, "conn.close", null);
        }
        runtime.offline();
    }

    private void closeCurrentHandle(ConnectionRuntime runtime, String reason) {
        ConnectionHandle handle = runtime.handle;
        if (handle == null) {
            return;
        }
        runtime.handle = null;
        handle.markSuperseded();
        handle.close(CloseStatus.NORMAL.getCode(), reason);
        handles.remove(handle.handleId());
    }

    private void replaceHandle(ConnectionRuntime runtime, ConnectionHandle next) {
        ConnectionHandle previous = runtime.handle;
        runtime.handle = next;
        if (previous != null && previous != next) {
            previous.markSuperseded();
            previous.close(CloseStatus.NORMAL.getCode(), "被新会话取代");
            handles.remove(previous.handleId());
            log.info("[{}] 旧会话被新会话取代，已关闭（此关闭不触发重连）", runtime.name);
        }
    }

    private void startEnabledConnectionsOfType(AdapterSession session) {
        List<WsConnection> connections =
                mapper.selectList(
                        new LambdaQueryWrapper<WsConnection>()
                                .eq(WsConnection::getConnectionType, session.connectionType())
                                .eq(WsConnection::getEnabled, 1));
        if (connections.isEmpty()) {
            return;
        }
        log.info("[{}] 拉起该类型的 {} 条启用连接", session.name(), connections.size());
        for (WsConnection connection : connections) {
            try {
                start(connection.getId());
            } catch (RuntimeException e) {
                log.error("拉起连接 {} 失败", connection.getId(), e);
            }
        }
    }

    private void degradeConnectionsOf(AdapterSession session, String reason) {
        runtimes.values().stream()
                .filter(runtime -> runtime.connectionType.equals(session.connectionType()))
                .forEach(
                        runtime -> {
                            closeCurrentHandle(runtime, reason);
                            endpoints.unregister(runtime.endpointPath, runtime.connectionId);
                            runtime.waitingAdapter();
                            runtime.adapterNotified = false;
                            runtime.failureReason = reason;
                        });
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 按 {@code secretField} 从 opaque config 里取出密钥值。 */
    private String secretOf(String configJson, HandshakeSpec spec) {
        if (spec == null || !spec.requiresSecret() || spec.secretField() == null) {
            return null;
        }
        if (configJson == null || configJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(configJson);
            JsonNode value = root == null ? null : root.get(spec.secretField());
            return value == null || value.isNull() ? null : value.asString();
        } catch (RuntimeException e) {
            log.warn("解析连接 config 失败: {}", e.getMessage());
            return null;
        }
    }

    private void putJson(ObjectNode payload, String field, String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            payload.putNull(field);
            return;
        }
        try {
            payload.set(field, objectMapper.readTree(rawJson));
        } catch (RuntimeException e) {
            payload.putNull(field);
            log.warn("config 不是合法 JSON，已置空: {}", e.getMessage());
        }
    }

    private static void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (Exception ignored) {
            // 尽力而为
        }
    }

    // ==================================================================
    // 握手结果
    // ==================================================================

    /**
     * 反向握手校验结果。
     *
     * @param accepted 是否通过
     * @param connectionId 通过时归属的连接 ID
     * @param rejectReason 拒绝原因（会写进日志）
     */
    public record ReverseHandshakeResult(boolean accepted, long connectionId, String rejectReason) {

        static ReverseHandshakeResult accept(long connectionId) {
            return new ReverseHandshakeResult(true, connectionId, null);
        }

        static ReverseHandshakeResult reject(String reason) {
            return new ReverseHandshakeResult(false, -1L, reason);
        }
    }
}
