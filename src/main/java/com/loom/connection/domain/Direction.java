package com.loom.connection.domain;

/**
 * 连接方向。
 *
 * <p><b>由连接类型决定，不是用户填的</b> —— 它来自插件版本连接 schema 的 {@code x-direction} 声明。数据库不单独保存这一列，Java 下发 Adapter
 * 命令时显式携带。
 */
public enum Direction {

    /** 平台主动连入，本应用作 WebSocket 服务端。 */
    REVERSE("反向"),

    /** 本应用主动连出，作 WebSocket 客户端。 */
    FORWARD("正向");

    private final String label;

    Direction(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 宽容解析：未知值返回 {@code null}，由调用方决定如何降级。 */
    public static Direction parse(String value) {
        if (value == null) {
            return null;
        }
        for (Direction direction : values()) {
            if (direction.name().equalsIgnoreCase(value.strip())) {
                return direction;
            }
        }
        return null;
    }
}
