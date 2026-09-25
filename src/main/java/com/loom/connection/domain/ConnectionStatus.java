package com.loom.connection.domain;

/**
 * 连接的运行时状态快照，用于管理 API 与前端展示。
 *
 * <p>刻意与持久化实体分开：{@code enabled} 是**人的意图**（存库）， {@code state} 是**此刻的事实**（内存）。两者不一致是正常的 ——
 * 比如用户启用了但适配器没上线。
 *
 * @param connectionId 连接 ID
 * @param enabled 配置状态：是否已启用
 * @param state 运行时状态
 * @param failureReason 最近一次失败原因；无失败时为 {@code null}
 * @param consecutiveFailures 连续失败次数，用于观察「一直在重连但连不上」
 */
public record ConnectionStatus(
        long connectionId,
        boolean enabled,
        ConnectionState state,
        String failureReason,
        int consecutiveFailures) {}
