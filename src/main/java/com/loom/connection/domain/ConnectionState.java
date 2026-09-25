package com.loom.connection.domain;

/**
 * 连接的**运行时状态**。
 *
 * <p>刻意<b>不持久化</b> —— 它是瞬时事实，重启后重新建立即可。 持久化反而会产生「DB 说在线但进程已死」的不一致。
 *
 * <p>持久化的只有 {@code enabled}（人的意图）。两者的区别很重要： 用户「启用」了连接，不代表它此刻在线。
 */
public enum ConnectionState {

    /** 未连接：初始状态，或已被用户停用。 */
    OFFLINE("未连接"),

    /** 已启用但适配器未就绪 —— 等待适配器上线后自动推进。 */
    WAITING_ADAPTER("等待适配器"),

    /** 正向连接：适配器正在请求建立通道。 */
    CONNECTING("连接中"),

    /** 反向连接：端点已注册，等待平台连入。 */
    LISTENING("监听中"),

    /** 在线，正常收发。 */
    ONLINE("在线"),

    /** 断线，退避等待中。 */
    RECONNECTING("重连中"),

    /** 正在主动关闭。 */
    STOPPING("停止中"),

    /**
     * 不可重试的失败。
     *
     * <p>与 {@link #RECONNECTING} 的区别很关键：鉴权失败、配置不合法这类问题 重连多少次都没用，应该停下来报错，而不是无限重试掩盖问题。
     */
    FAILED("失败");

    private final String label;

    ConnectionState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
