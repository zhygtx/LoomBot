package com.loombot.connection.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.loombot.common.api.ErrorCode;
import com.loombot.common.api.PageResult;
import com.loombot.common.exception.BusinessException;
import com.loombot.connection.domain.ConnectionStatus;
import com.loombot.connection.domain.ConnectionTypeDescriptor;
import com.loombot.connection.domain.Direction;
import com.loombot.connection.domain.WsConnection;
import com.loombot.connection.dto.ConnectionCreateRequest;
import com.loombot.connection.dto.ConnectionResponse;
import com.loombot.connection.dto.ConnectionUpdateRequest;
import com.loombot.connection.manager.ConnectionManager;
import com.loombot.connection.mapper.WsConnectionMapper;
import com.loombot.connection.usage.ConnectionUsageGuard;
import com.loombot.plugin.event.PluginVersionsRemovedEvent;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * WS 连接的持久化与运行时联动。
 *
 * <h2>这一层负责什么、不负责什么</h2>
 *
 * <p>负责：数据库 CRUD、接入路径生成、{@code config} 的密钥掩码、以及**在正确的时机** 通知 {@link ConnectionManager}。
 *
 * <p>不负责：Adapter 连接状态机、重连和 WS 通道。控制命令由 {@link ConnectionManager} 发给 Adapter；实际状态由定时同步器投影到
 * MySQL，本类只读取数据库快照。
 *
 * <h2>为什么创建时要求插件版本已登记</h2>
 *
 * <p>方向（正向/反向）和 {@code config} 的字段构成都由插件版本声明。没有登记版本就不知道 该不该生成接入路径，也渲染不出配置表单。所以创建时要求 {@code
 * pluginVersionId + connectionType} 已知。
 *
 * <p>注意这和“运行时进程已经在线”是两件事。新建只要求元数据已登记；进程按需启动， 未就绪期间状态是 {@code PENDING}。
 */
@Service
public class WsConnectionService {

    private static final Logger log = LoggerFactory.getLogger(WsConnectionService.class);

    /** 接入路径随机部分：16 字节 = 128 位，穷举不可行。 */
    private static final int PATH_RANDOM_BYTES = 16;

    private static final String PATH_PREFIX = "/ws/";

    /** 生成路径时的查重重试次数。128 位随机下冲突概率可忽略，这里是给「有人手动改过库」兜底。 */
    private static final int PATH_MAX_ATTEMPTS = 5;

    private final WsConnectionMapper mapper;
    private final ConnectionManager manager;
    private final ConnectionUsageGuard usageGuard;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    public WsConnectionService(
            WsConnectionMapper mapper,
            ConnectionManager manager,
            ConnectionUsageGuard usageGuard,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.manager = manager;
        this.usageGuard = usageGuard;
        this.objectMapper = objectMapper;
    }

    // ==================================================================
    // 查询
    // ==================================================================

    public PageResult<ConnectionResponse> page(
            long pageNum,
            long pageSize,
            String keyword,
            String connectionType,
            Integer enabled,
            long ownerUserId) {
        Page<WsConnection> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<WsConnection> wrapper =
                new LambdaQueryWrapper<WsConnection>()
                        .like(StringUtils.hasText(keyword), WsConnection::getName, keyword)
                        .eq(
                                StringUtils.hasText(connectionType),
                                WsConnection::getConnectionType,
                                connectionType)
                        .eq(enabled != null, WsConnection::getEnabled, enabled)
                        .eq(WsConnection::getOwnerUserId, ownerUserId)
                        .orderByDesc(WsConnection::getId);
        IPage<WsConnection> result = mapper.selectPage(page, wrapper);
        Map<Long, ConnectionStatus> statuses = manager.statusMap(result.getRecords());
        List<ConnectionResponse> records =
                result.getRecords().stream()
                        .map(
                                entity ->
                                        toResponse(
                                                entity,
                                                statuses.getOrDefault(
                                                        entity.getId(),
                                                        manager.status(entity.getId()))))
                        .toList();
        return PageResult.of(records, result.getTotal(), pageNum, pageSize);
    }

    public ConnectionResponse get(long id, long ownerUserId) {
        return toResponse(requireOwned(id, ownerUserId));
    }

    public ConnectionStatus status(long id, long ownerUserId) {
        requireOwned(id, ownerUserId);
        return manager.status(id);
    }

    /** 所有已知连接最近一次同步到 MySQL 的运行时状态。 */
    public List<ConnectionStatus> statuses(long ownerUserId) {
        return mapper
                .selectList(
                        new LambdaQueryWrapper<WsConnection>()
                                .select(WsConnection::getId)
                                .eq(WsConnection::getOwnerUserId, ownerUserId))
                .stream()
                .map(connection -> manager.status(connection.getId()))
                .toList();
    }

    // ==================================================================
    // 写入
    // ==================================================================

    @Transactional
    public ConnectionResponse create(ConnectionCreateRequest request, Long operatorId) {
        if (operatorId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        ConnectionTypeDescriptor descriptor =
                manager.connectionType(request.pluginVersionId(), request.connectionType())
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.CONNECTION_TYPE_UNKNOWN,
                                                "找不到已登记的适配器版本与类型: "
                                                        + request.pluginVersionId()
                                                        + " / "
                                                        + request.connectionType()
                                                        + "。请等待自动扫描完成或检查插件目录。"));
        String config = ConfigMasking.requireValidConfig(request.config(), descriptor);

        WsConnection entity = new WsConnection();
        entity.setName(request.name().trim());
        entity.setPluginVersionId(descriptor.pluginVersionId());
        entity.setConnectionType(descriptor.type());
        entity.setConfig(config);
        entity.setEndpointPath(
                descriptor.direction() == Direction.REVERSE ? allocateEndpointPath() : null);
        entity.setOwnerUserId(operatorId);
        entity.setEnabled(1);
        entity.setDesiredRevision(1L);
        entity.setRemark(request.remark());
        entity.setCreateBy(operatorId);
        entity.setUpdateBy(operatorId);
        entity.setCreateTime(LocalDateTime.now());
        entity.setUpdateTime(LocalDateTime.now());

        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(
                    ErrorCode.CONNECTION_NAME_EXISTS, "连接名已存在: " + entity.getName());
        }
        log.info(
                "连接已创建: id={}, name={}, type={}",
                entity.getId(),
                entity.getName(),
                entity.getConnectionType());

        // 创建即启用：新连接默认 enabled=1，所以要立刻把它拉起来
        afterCommit(() -> manager.start(entity.getId()));
        return toResponse(require(entity.getId()));
    }

    @Transactional
    public ConnectionResponse update(long id, ConnectionUpdateRequest request, Long operatorId) {
        WsConnection existing = requireOwned(id, operatorId);
        Set<String> secretFields = ConfigMasking.secretFields(descriptorOf(existing));

        // restore 内部会做「必须是对象」的校验，并把哨兵还原成库中原值
        String config =
                ConfigMasking.restore(
                        request.config(), existing.getConfig(), secretFields, objectMapper);
        ConfigMasking.requireValidConfig(objectMapper.readTree(config), descriptorOf(existing));

        WsConnection patch = new WsConnection();
        patch.setId(id);
        patch.setName(request.name().trim());
        patch.setConfig(config);
        // 显式给空串而不是 null：更新用的是 NOT_NULL 策略，传 null 等于「不改」，
        // 用户就永远清不掉备注
        patch.setRemark(request.remark() == null ? "" : request.remark());
        patch.setDesiredRevision(existing.getDesiredRevision() + 1);
        patch.setUpdateBy(operatorId);
        patch.setUpdateTime(LocalDateTime.now());
        try {
            mapper.updateById(patch);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(
                    ErrorCode.CONNECTION_NAME_EXISTS, "连接名已存在: " + patch.getName());
        }

        // 只有启用中的连接才需要刷新运行时；停用的连接等下次启用时自然读到新配置
        if (Objects.equals(existing.getEnabled(), 1)) {
            afterCommit(() -> manager.reload(id));
        }
        return toResponse(require(id));
    }

    @Transactional
    public ConnectionResponse setEnabled(long id, boolean enabled, Long operatorId) {
        WsConnection existing = requireOwned(id, operatorId);
        WsConnection patch = new WsConnection();
        patch.setId(id);
        patch.setEnabled(enabled ? 1 : 0);
        patch.setDesiredRevision(existing.getDesiredRevision() + 1);
        patch.setUpdateBy(operatorId);
        patch.setUpdateTime(LocalDateTime.now());
        mapper.updateById(patch);

        afterCommit(
                () -> {
                    if (enabled) {
                        manager.start(id);
                    } else {
                        manager.stop(id);
                    }
                });
        return toResponse(require(id));
    }

    @Transactional
    public void delete(long id, Long operatorId) {
        requireOwned(id, operatorId);
        List<String> usedBy = usageGuard.workflowsUsing(id, operatorId);
        if (!usedBy.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.CONNECTION_IN_USE,
                    "还有 "
                            + usedBy.size()
                            + " 个工作流在使用这个连接："
                            + String.join("、", usedBy)
                            + "。请先修改或删除这些工作流。");
        }
        if (!manager.stop(id)) {
            throw new BusinessException(
                    ErrorCode.CONNECTION_UNAVAILABLE, "Adapter 控制 API 不可达，连接未删除。恢复后再重试。");
        }
        // 先真删数据库行，事务提交后再清状态投影。事务回滚时连接定义和投影都还存在。
        //
        // 注意这里确实是物理删除（没有 deleted 列了）。所以下面的 forget 不是可选项：
        // 少调一次就会留下孤儿状态投影。同步周期的清理是兜底，不是正常删除路径。
        mapper.deleteById(id);
        afterCommit(
                () -> {
                    manager.forget(id);
                    // 触发索引里还留着 (connectionId, type, nodeKey) -> 版本 的映射，
                    // 连接没了就永远不会再被查询，不清理就是永久孤儿键
                    usageGuard.onConnectionDeleted(id);
                });
        log.info("连接已删除: id={}", id);
    }

    /**
     * 插件版本被移除时的系统级清理：把这些版本上的连接一并删掉。
     *
     * <p>和用户主动删除的区别是不做归属校验、也不因"有工作流在用"而拒绝——版本已经从目录里 消失，这个连接不可能再启动成功，留着只是一个永远不可用的幽灵。工作流那边由插件节点失效
     * 记录负责解释发生了什么，所以这里可以直接删干净。
     *
     * @return 实际删除的连接数
     */
    @Transactional
    public int deleteByPluginVersions(List<Long> pluginVersionIds) {
        if (pluginVersionIds == null || pluginVersionIds.isEmpty()) {
            return 0;
        }
        List<WsConnection> rows =
                mapper.selectList(
                        new LambdaQueryWrapper<WsConnection>()
                                .in(WsConnection::getPluginVersionId, pluginVersionIds));
        for (WsConnection row : rows) {
            Long id = row.getId();
            try {
                manager.stop(id);
            } catch (RuntimeException e) {
                // 适配器不可达时也要把定义删掉：它指向的版本已经不存在了
                log.warn("停止连接失败，仍然删除定义: id={} error={}", id, e.getMessage());
            }
            mapper.deleteById(id);
            afterCommit(
                    () -> {
                        manager.forget(id);
                        usageGuard.onConnectionDeleted(id);
                    });
            log.warn("连接因插件版本被移除而删除: id={} version={}", id, row.getPluginVersionId());
        }
        return rows.size();
    }

    /** 插件目录里版本被移除之后，清理绑定在这些版本上的连接。 */
    @EventListener
    @Transactional
    public void onPluginVersionsRemoved(PluginVersionsRemovedEvent event) {
        int deleted = deleteByPluginVersions(event.pluginVersionIds());
        if (deleted > 0) {
            log.warn(
                    "插件版本移除清理完成: versions={} deletedConnections={}",
                    event.pluginVersionIds(),
                    deleted);
        }
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private WsConnection require(long id) {
        WsConnection entity = mapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.CONNECTION_NOT_FOUND, "连接不存在: " + id);
        }
        return entity;
    }

    private WsConnection requireOwned(long id, Long ownerUserId) {
        if (ownerUserId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        WsConnection entity =
                mapper.selectOne(
                        new LambdaQueryWrapper<WsConnection>()
                                .eq(WsConnection::getId, id)
                                .eq(WsConnection::getOwnerUserId, ownerUserId));
        if (entity == null) {
            throw new BusinessException(ErrorCode.CONNECTION_NOT_FOUND, "连接不存在: " + id);
        }
        return entity;
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        action.run();
                    }
                });
    }

    /** 插件注册表里的类型描述；元数据缺失时返回 {@code null}。 */
    private ConnectionTypeDescriptor descriptorOf(WsConnection entity) {
        return manager.connectionType(entity.getPluginVersionId(), entity.getConnectionType())
                .orElse(null);
    }

    /**
     * 生成一条未被占用的接入路径。
     *
     * <p>先查库再插入，严格说存在竞态（两个请求同时生成同一个 128 位随机串）， 但概率是 2^-128 量级 —— 真正的兜底是数据库的 {@code
     * uk_endpoint_path} 唯一索引。 这里查重的实际价值是：给「有人手工往库里塞过脏数据」这种情况一个明确的报错， 而不是让用户看到一句
     * DuplicateKeyException。
     */
    private String allocateEndpointPath() {
        for (int attempt = 0; attempt < PATH_MAX_ATTEMPTS; attempt++) {
            byte[] bytes = new byte[PATH_RANDOM_BYTES];
            random.nextBytes(bytes);
            String path = PATH_PREFIX + HexFormat.of().formatHex(bytes);
            boolean taken =
                    mapper.exists(
                            new LambdaQueryWrapper<WsConnection>()
                                    .eq(WsConnection::getEndpointPath, path));
            if (!taken) {
                return path;
            }
            log.warn("接入路径冲突（第 {} 次），重新生成", attempt + 1);
        }
        throw new BusinessException(ErrorCode.CONNECTION_UNAVAILABLE, "生成接入路径连续失败，请重试");
    }

    private ConnectionResponse toResponse(WsConnection entity) {
        return toResponse(entity, manager.status(entity.getId()));
    }

    private ConnectionResponse toResponse(WsConnection entity, ConnectionStatus status) {
        ConnectionTypeDescriptor descriptor = descriptorOf(entity);
        return new ConnectionResponse(
                entity.getId(),
                entity.getName(),
                entity.getPluginVersionId(),
                entity.getConnectionType(),
                descriptor == null
                        ? null
                        : ConfigMasking.mask(
                                entity.getConfig(),
                                ConfigMasking.secretFields(descriptor),
                                objectMapper),
                entity.getEndpointPath(),
                manager.publicEndpoint(entity.getEndpointPath()),
                entity.getOwnerUserId(),
                Objects.equals(entity.getEnabled(), 1),
                entity.getRemark(),
                entity.getCreateTime(),
                entity.getUpdateTime(),
                status);
    }
}
