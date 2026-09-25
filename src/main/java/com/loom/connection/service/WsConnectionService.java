package com.loom.connection.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.loom.common.api.ErrorCode;
import com.loom.common.api.PageResult;
import com.loom.common.exception.BusinessException;
import com.loom.connection.domain.ConnectionStatus;
import com.loom.connection.domain.ConnectionTypeDescriptor;
import com.loom.connection.domain.Direction;
import com.loom.connection.domain.WsConnection;
import com.loom.connection.dto.ConnectionCreateRequest;
import com.loom.connection.dto.ConnectionResponse;
import com.loom.connection.dto.ConnectionUpdateRequest;
import com.loom.connection.manager.ConnectionManager;
import com.loom.connection.mapper.WsConnectionMapper;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>不负责：连接状态机、重连、WS 通道。那些全在 {@code ConnectionManager} 里 —— 它是运行时状态的唯一权威，本类只是它的调用方。反过来，管理器不认识 DTO、
 * 不返回实体给上层，两边靠 connectionId 打交道。
 *
 * <h2>为什么创建时要求适配器在线</h2>
 *
 * <p>方向（正向/反向）和 {@code config} 的字段构成**都由适配器声明**，没有适配器就不知道 该不该生成接入路径，也渲染不出配置表单。所以创建时要求类型已知。
 *
 * <p>注意这和「运行时要求适配器在线」是两件事：库里已存在的连接，即使适配器没起来也能加载， 状态是 {@code WAITING_ADAPTER}，适配器 {@code hello}
 * 后自动拉起。**只有新建需要适配器在场。**
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
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    public WsConnectionService(
            WsConnectionMapper mapper, ConnectionManager manager, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.manager = manager;
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
        List<ConnectionResponse> records =
                result.getRecords().stream().map(this::toResponse).toList();
        return PageResult.of(records, result.getTotal(), pageNum, pageSize);
    }

    public ConnectionResponse get(long id, long ownerUserId) {
        return toResponse(requireOwned(id, ownerUserId));
    }

    public ConnectionStatus status(long id, long ownerUserId) {
        requireOwned(id, ownerUserId);
        return manager.status(id);
    }

    /** 所有已知连接的运行时状态。只在内存里，未启动过的连接不会出现。 */
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
                manager.connectionType(request.connectionType())
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.CONNECTION_TYPE_UNKNOWN,
                                                "适配器未就绪，无法创建类型为 "
                                                        + request.connectionType()
                                                        + " 的连接。请确认对应插件已加载。"));
        String config = ConfigMasking.requireValidConfig(request.config(), descriptor);

        WsConnection entity = new WsConnection();
        entity.setName(request.name().trim());
        entity.setConnectionType(descriptor.type());
        entity.setConfig(config);
        entity.setEndpointPath(
                descriptor.direction() == Direction.REVERSE ? allocateEndpointPath() : null);
        entity.setOwnerUserId(operatorId);
        entity.setEnabled(1);
        entity.setRemark(request.remark());
        entity.setDeleted(0);
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
        requireOwned(id, operatorId);
        WsConnection patch = new WsConnection();
        patch.setId(id);
        patch.setEnabled(enabled ? 1 : 0);
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
        // 先提交逻辑删除，再清理运行时。事务回滚时连接仍保持在线，避免数据库里还存在、
        // 运行时却已被永久摘掉的不一致状态。
        mapper.deleteById(id);
        afterCommit(() -> manager.forget(id));
        log.info("连接已删除: id={}", id);
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

    /** 类型的描述信息；适配器不在线时返回 {@code null}（掩码与就绪提示都据此退化）。 */
    private ConnectionTypeDescriptor descriptorOf(WsConnection entity) {
        return manager.connectionType(entity.getConnectionType()).orElse(null);
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
        ConnectionTypeDescriptor descriptor = descriptorOf(entity);
        return new ConnectionResponse(
                entity.getId(),
                entity.getName(),
                entity.getConnectionType(),
                descriptor == null
                        ? null
                        : ConfigMasking.mask(
                                entity.getConfig(),
                                ConfigMasking.secretFields(descriptor),
                                objectMapper),
                entity.getEndpointPath(),
                entity.getOwnerUserId(),
                Objects.equals(entity.getEnabled(), 1),
                entity.getRemark(),
                entity.getCreateTime(),
                entity.getUpdateTime(),
                descriptor != null && descriptor.ready(),
                manager.status(entity.getId()));
    }
}
