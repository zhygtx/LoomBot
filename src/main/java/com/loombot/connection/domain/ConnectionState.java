package com.loombot.connection.domain;

/** 适配器运行时观测状态。监管器可达性由运行时快照单独表达。 */
public enum ConnectionState {
    DISABLED("已停用"),
    PENDING("等待应用"),
    STARTING("启动中"),
    CONNECTING("连接中"),
    LISTENING("监听中"),
    ONLINE("在线"),
    RETRY_WAIT("等待重试"),
    DEGRADED("降级"),
    STOPPING("停止中"),
    FAILED("失败");

    private final String label;

    ConnectionState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static ConnectionState parse(String value) {
        if (value == null || value.isBlank()) return PENDING;
        try {
            return valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return FAILED;
        }
    }
}
