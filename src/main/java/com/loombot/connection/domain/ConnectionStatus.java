package com.loombot.connection.domain;

import java.time.LocalDateTime;

/**
 * 连接的运行时状态快照，用于管理 API 与前端展示。
 *
 * <p>{@code enabled} 来自连接定义，{@code state} 等字段来自 Adapter 状态的数据库投影。 两者在同一个读模型里合并，但写入者是分开的：用户改
 * enabled，定时任务同步 Adapter 状态。
 *
 * @param connectionId 连接 ID
 * @param enabled 配置状态：是否已启用
 * @param state 运行时状态
 * @param failureReason 最近一次失败原因；无失败时为 {@code null}
 * @param revisionPending 期望版本是否尚未被 Adapter 观察到
 * @param desiredRevision Java 期望版本
 * @param runtimeReachable Adapter 控制 API 是否可达
 * @param observedRevision Adapter 实际应用到的版本
 * @param lastFrameAt 最近收到平台帧的时间戳；0 表示尚未收到
 * @param instanceId 最近一次状态同步对应的 Adapter 实例
 * @param lastSyncedAt Java 最近一次把 Adapter 状态写入数据库的时间
 */
public record ConnectionStatus(
        long connectionId,
        boolean enabled,
        ConnectionState state,
        String failureReason,
        int revisionPending,
        long desiredRevision,
        boolean runtimeReachable,
        Long observedRevision,
        long lastFrameAt,
        String instanceId,
        LocalDateTime lastSyncedAt) {}
