package com.loombot.connection.sync;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.loombot.adapter.AdapterConnectionStatus;
import com.loombot.adapter.AdapterControlClient;
import com.loombot.connection.domain.ConnectionState;
import com.loombot.connection.domain.WsConnection;
import com.loombot.connection.domain.WsConnectionRuntime;
import com.loombot.connection.mapper.WsConnectionMapper;
import com.loombot.connection.mapper.WsConnectionRuntimeMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 批量同步 Adapter 实际连接状态到 MySQL。
 *
 * <p>用户请求线程不再访问 Adapter 查询状态。这里周期读取全部连接期望状态，一次请求获取整批 Adapter 状态，再批量 upsert 到 {@code
 * connection_observation}。前端只读这份数据库投影。
 */
@Component
public class ConnectionRuntimeSyncService {

    private static final Logger log = LoggerFactory.getLogger(ConnectionRuntimeSyncService.class);

    private static final String RUNTIME_UNREACHABLE = "适配器监管器控制 API 不可达";

    private static final int MAX_FAILURE_LENGTH = 1024;

    private final WsConnectionMapper connectionMapper;
    private final WsConnectionRuntimeMapper runtimeMapper;
    private final AdapterControlClient adapterClient;

    private Boolean lastReachable;

    public ConnectionRuntimeSyncService(
            WsConnectionMapper connectionMapper,
            WsConnectionRuntimeMapper runtimeMapper,
            AdapterControlClient adapterClient) {
        this.connectionMapper = connectionMapper;
        this.runtimeMapper = runtimeMapper;
        this.adapterClient = adapterClient;
    }

    @Scheduled(
            fixedDelayString = "${loombot.connection.runtime-sync-interval:2s}",
            initialDelayString = "${loombot.connection.runtime-sync-initial-delay:3s}")
    public void scheduledSync() {
        syncNow();
    }

    /** 控制命令成功后立即刷新一次，让紧接着的前端读取看到新状态。 */
    public synchronized void syncNow() {
        List<WsConnection> connections = allConnections();
        if (connections.isEmpty()) {
            cleanupOrphans();
            return;
        }

        boolean reachable = true;
        Map<Long, AdapterConnectionStatus> actual;
        try {
            actual = adapterClient.statuses(connections.stream().map(WsConnection::getId).toList());
        } catch (RuntimeException e) {
            reachable = false;
            actual = Map.of();
        }
        logReachability(reachable);
        writeSnapshots(connections, reachable, actual);
    }

    /** Adapter 控制面已确认不可达时直接落库，不重复等待一次 HTTP 超时。 */
    public synchronized void markUnreachable() {
        List<WsConnection> connections = allConnections();
        if (connections.isEmpty()) {
            cleanupOrphans();
            return;
        }
        logReachability(false);
        writeSnapshots(connections, false, Map.of());
    }

    private List<WsConnection> allConnections() {
        return connectionMapper.selectList(
                new LambdaQueryWrapper<WsConnection>()
                        .select(
                                WsConnection::getId,
                                WsConnection::getEnabled,
                                WsConnection::getDesiredRevision)
                        .orderByAsc(WsConnection::getId));
    }

    private void writeSnapshots(
            List<WsConnection> connections,
            boolean reachable,
            Map<Long, AdapterConnectionStatus> actualByConnection) {
        LocalDateTime syncedAt = LocalDateTime.now();
        List<WsConnectionRuntime> snapshots =
                connections.stream()
                        .map(
                                connection ->
                                        snapshot(
                                                connection,
                                                reachable,
                                                actualByConnection.get(connection.getId()),
                                                syncedAt))
                        .toList();
        try {
            runtimeMapper.upsertBatch(snapshots);
        } catch (RuntimeException e) {
            log.error("批量写入连接运行状态失败: count={}", snapshots.size(), e);
        }
        cleanupOrphans();
    }

    private WsConnectionRuntime snapshot(
            WsConnection connection,
            boolean reachable,
            AdapterConnectionStatus actual,
            LocalDateTime syncedAt) {
        WsConnectionRuntime snapshot = new WsConnectionRuntime();
        snapshot.setConnectionId(connection.getId());
        snapshot.setRuntimeReachable(reachable ? 1 : 0);
        snapshot.setLastSyncedAt(syncedAt);

        if (!reachable) {
            snapshot.setState(
                    connection.getEnabled() == 1
                            ? ConnectionState.PENDING.name()
                            : ConnectionState.DISABLED.name());
            snapshot.setFailureReason(RUNTIME_UNREACHABLE);
            snapshot.setLastFrameAt(0L);
            return snapshot;
        }
        if (actual == null) {
            snapshot.setState(
                    connection.getEnabled() == 1
                            ? ConnectionState.PENDING.name()
                            : ConnectionState.DISABLED.name());
            snapshot.setLastFrameAt(0L);
            return snapshot;
        }

        snapshot.setInstanceId(actual.instanceId());
        snapshot.setState(ConnectionState.parse(actual.state()).name());
        snapshot.setFailureReason(trim(actual.lastError()));
        snapshot.setObservedRevision(actual.observedRevision());
        snapshot.setLastFrameAt(actual.lastFrameAt());
        return snapshot;
    }

    private void logReachability(boolean reachable) {
        if (lastReachable == null || lastReachable != reachable) {
            if (reachable) {
                log.info("Adapter 连接状态同步已恢复");
            } else {
                log.warn("适配器连接状态同步不可达，数据库保留 PENDING 或 DISABLED 状态");
            }
            lastReachable = reachable;
        }
    }

    private void cleanupOrphans() {
        try {
            runtimeMapper.deleteOrphans();
        } catch (RuntimeException e) {
            log.warn("清理连接状态孤儿行失败", e);
        }
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_FAILURE_LENGTH
                ? value
                : value.substring(0, MAX_FAILURE_LENGTH);
    }
}
