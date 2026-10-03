package com.loombot.connection.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.loombot.adapter.AdapterConnectionCommand;
import com.loombot.adapter.AdapterControlClient;
import com.loombot.adapter.AdapterControlException;
import com.loombot.adapter.AdapterControlResult;
import com.loombot.connection.domain.ConnectionState;
import com.loombot.connection.domain.ConnectionStatus;
import com.loombot.connection.domain.ConnectionTypeDescriptor;
import com.loombot.connection.domain.Direction;
import com.loombot.connection.domain.WsConnection;
import com.loombot.connection.domain.WsConnectionRuntime;
import com.loombot.connection.mapper.WsConnectionMapper;
import com.loombot.connection.mapper.WsConnectionRuntimeMapper;
import com.loombot.connection.sync.ConnectionRuntimeSyncService;
import com.loombot.runtime.plugin.AdapterConnectionTypeRuntime;
import com.loombot.runtime.plugin.PluginCatalog;
import com.loombot.runtime.plugin.PluginVersionRuntime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 连接控制面。
 *
 * <p>Java 不持有 socket、不启动 Adapter Plugin。控制命令直接走 internal HTTP；状态读取只查 MySQL 投影，Adapter 状态由 {@link
 * ConnectionRuntimeSyncService} 定时同步。
 */
@Component
public class ConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(ConnectionManager.class);

    private final WsConnectionMapper mapper;
    private final WsConnectionRuntimeMapper runtimeMapper;
    private final PluginCatalog pluginCatalog;
    private final AdapterControlClient adapterClient;
    private final ConnectionRuntimeSyncService runtimeSync;
    private final ObjectMapper objectMapper;

    public ConnectionManager(
            WsConnectionMapper mapper,
            WsConnectionRuntimeMapper runtimeMapper,
            PluginCatalog pluginCatalog,
            AdapterControlClient adapterClient,
            ConnectionRuntimeSyncService runtimeSync,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.runtimeMapper = runtimeMapper;
        this.pluginCatalog = pluginCatalog;
        this.adapterClient = adapterClient;
        this.runtimeSync = runtimeSync;
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void reconcileOnStart() {
        try {
            List<AdapterConnectionCommand> desired =
                    mapper.selectList(new LambdaQueryWrapper<WsConnection>()).stream()
                            .map(this::command)
                            .toList();
            AdapterControlResult result = adapterClient.reconcile(desired);
            runtimeSync.syncNow();
            log.info("Adapter reconcile 完成: ok={}, message={}", result.ok(), result.message());
        } catch (AdapterControlException e) {
            log.warn("Adapter reconcile 失败，连接状态等待监管器恢复: {}", e.getMessage());
            runtimeSync.markUnreachable();
        }
    }

    public void start(long connectionId) {
        WsConnection entity = mapper.selectById(connectionId);
        if (entity == null) {
            return;
        }
        control("apply", () -> adapterClient.apply(command(entity)));
    }

    public boolean stop(long connectionId) {
        WsConnection entity = mapper.selectById(connectionId);
        if (entity == null) {
            return true;
        }
        return control("remove", () -> adapterClient.remove(connectionId));
    }

    public void reload(long connectionId) {
        WsConnection entity = mapper.selectById(connectionId);
        if (entity == null) {
            return;
        }
        control("apply", () -> adapterClient.apply(command(entity)));
    }

    public void forget(long connectionId) {
        runtimeMapper.deleteById(connectionId);
    }

    public ConnectionStatus status(long connectionId) {
        WsConnection entity = mapper.selectById(connectionId);
        if (entity == null) {
            return offline(connectionId, false);
        }
        return aggregate(entity, runtimeMapper.selectById(connectionId));
    }

    public List<ConnectionStatus> statuses(long ownerUserId) {
        List<WsConnection> entities =
                mapper.selectList(
                        new LambdaQueryWrapper<WsConnection>()
                                .select(
                                        WsConnection::getId,
                                        WsConnection::getEnabled,
                                        WsConnection::getDesiredRevision)
                                .eq(WsConnection::getOwnerUserId, ownerUserId)
                                .orderByDesc(WsConnection::getId));
        return statusMap(entities).values().stream().toList();
    }

    public Map<Long, ConnectionStatus> statusMap(List<WsConnection> entities) {
        if (entities == null || entities.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = entities.stream().map(WsConnection::getId).toList();
        Map<Long, WsConnectionRuntime> runtimes = new LinkedHashMap<>();
        runtimeMapper
                .selectBatchIds(ids)
                .forEach(runtime -> runtimes.put(runtime.getConnectionId(), runtime));
        Map<Long, ConnectionStatus> result = new LinkedHashMap<>();
        for (WsConnection entity : entities) {
            result.put(entity.getId(), aggregate(entity, runtimes.get(entity.getId())));
        }
        return result;
    }

    public List<ConnectionTypeDescriptor> connectionTypes() {
        return pluginCatalog.adapterConnectionTypes().stream()
                .map(this::describe)
                .sorted(
                        java.util.Comparator.comparing(ConnectionTypeDescriptor::pluginVersionId)
                                .thenComparing(ConnectionTypeDescriptor::type))
                .toList();
    }

    public Optional<ConnectionTypeDescriptor> connectionType(Long pluginVersionId, String type) {
        if (pluginVersionId == null || type == null) {
            return Optional.empty();
        }
        return pluginCatalog.adapterConnectionType(pluginVersionId, type).map(this::describe);
    }

    public String publicEndpoint(String endpointPath) {
        if (endpointPath == null || endpointPath.isBlank()) {
            return null;
        }
        return adapterClient.publicWsBaseUrl() + endpointPath;
    }

    private ConnectionStatus aggregate(WsConnection entity, WsConnectionRuntime runtime) {
        boolean enabled = entity.getEnabled() == 1;
        if (!enabled) {
            return new ConnectionStatus(
                    entity.getId(),
                    false,
                    ConnectionState.DISABLED,
                    null,
                    0,
                    entity.getDesiredRevision(),
                    runtime != null && Integer.valueOf(1).equals(runtime.getRuntimeReachable()),
                    null,
                    0,
                    runtime == null ? null : runtime.getInstanceId(),
                    runtime == null ? null : runtime.getLastSyncedAt());
        }
        if (runtime == null) {
            return new ConnectionStatus(
                    entity.getId(),
                    true,
                    ConnectionState.PENDING,
                    null,
                    0,
                    entity.getDesiredRevision(),
                    false,
                    null,
                    0,
                    null,
                    null);
        }
        Long observedRevision = runtime.getObservedRevision();
        return new ConnectionStatus(
                entity.getId(),
                true,
                ConnectionState.parse(runtime.getState()),
                runtime.getFailureReason(),
                observedRevision != null && observedRevision < entity.getDesiredRevision() ? 1 : 0,
                entity.getDesiredRevision(),
                Integer.valueOf(1).equals(runtime.getRuntimeReachable()),
                observedRevision,
                runtime.getLastFrameAt() == null ? 0 : runtime.getLastFrameAt(),
                runtime.getInstanceId(),
                runtime.getLastSyncedAt());
    }

    private AdapterConnectionCommand command(WsConnection entity) {
        PluginVersionRuntime pluginVersion =
                pluginCatalog.adapterVersion(entity.getPluginVersionId()).orElse(null);
        AdapterConnectionTypeRuntime type =
                pluginCatalog
                        .adapterConnectionType(
                                entity.getPluginVersionId(), entity.getConnectionType())
                        .orElse(null);
        Direction direction = type == null ? null : Direction.parse(type.direction());
        JsonNode config;
        try {
            config = objectMapper.readTree(entity.getConfig());
        } catch (RuntimeException e) {
            config = objectMapper.createObjectNode();
        }
        return new AdapterConnectionCommand(
                entity.getId(),
                entity.getPluginVersionId(),
                pluginVersion == null ? null : pluginVersion.pluginKey(),
                pluginVersion == null ? null : pluginVersion.pluginVersion(),
                pluginVersion == null ? null : pluginVersion.installPath(),
                type == null ? null : type.entryPoint(),
                pluginVersion == null ? null : pluginVersion.pythonPath(),
                pluginVersion == null ? null : pluginVersion.artifactSha256(),
                entity.getConnectionType(),
                direction,
                config,
                entity.getEndpointPath(),
                entity.getDesiredRevision(),
                entity.getEnabled() == 1,
                hash(config));
    }

    private ConnectionTypeDescriptor describe(AdapterConnectionTypeRuntime type) {
        Direction direction = Direction.parse(type.direction());
        JsonNode schema;
        try {
            schema = objectMapper.readTree(type.configSchemaJson());
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "插件连接类型 schema 非法: "
                            + type.pluginKey()
                            + "@"
                            + type.pluginVersion()
                            + "/"
                            + type.connectionType(),
                    e);
        }
        List<String> capabilities;
        try {
            JsonNode caps = objectMapper.readTree(type.capabilitiesJson());
            capabilities =
                    caps != null && caps.isArray()
                            ? java.util.stream.StreamSupport.stream(caps.spliterator(), false)
                                    .map(JsonNode::asString)
                                    .toList()
                            : List.of();
        } catch (RuntimeException e) {
            capabilities = List.of();
        }
        return new ConnectionTypeDescriptor(
                type.pluginId(),
                type.pluginVersionId(),
                type.pluginKey(),
                type.pluginName(),
                type.pluginVersion(),
                type.connectionType(),
                type.displayName(),
                direction,
                schema,
                capabilities,
                type.pluginKey());
    }

    private boolean control(
            String action, java.util.concurrent.Callable<AdapterControlResult> call) {
        try {
            AdapterControlResult result = call.call();
            if (!result.ok()) {
                log.warn("Adapter {} 失败: {}", action, result.message());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Adapter {} 失败: {}", action, e.getMessage());
            return false;
        }
    }

    private static ConnectionStatus offline(long connectionId, boolean enabled) {
        return new ConnectionStatus(
                connectionId,
                enabled,
                ConnectionState.DISABLED,
                null,
                0,
                0,
                false,
                null,
                0,
                null,
                null);
    }

    private static String hash(JsonNode config) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return "sha256:"
                    + java.util.HexFormat.of()
                            .formatHex(
                                    digest.digest(
                                            config.toString()
                                                    .getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }
}
