package com.loom.connection.handshake;

import tools.jackson.databind.JsonNode;

/**
 * 握手校验规格 —— 由插件在配置表单 schema 的 {@code x-handshake} 里声明。
 *
 * <p>设计要点：**Java 实现通用机制，插件声明选哪个**。 {@code mode} 覆盖「凭据出现在请求的哪里」，{@code secretField} 覆盖「密钥存在 config
 * 的哪个字段」。 所以 Java 不需要知道它叫 token 还是 verifyToken。
 *
 * <pre>{@code
 * "x-handshake": {
 *   "mode": "queryParam",
 *   "paramName": "access_token",
 *   "secretField": "token"
 * }
 * }</pre>
 *
 * @param mode 校验模式：{@code none} / {@code bearerToken} / {@code queryParam} / {@code hmacSha256} /
 *     {@code custom}
 * @param secretField 指向 config 中存放密钥的字段名；{@code none} 时忽略
 * @param paramName queryParam 模式的参数名
 * @param headerName 取凭据的请求头名；bearerToken 默认 {@code Authorization}，hmacSha256 必填
 * @param timestampHeader hmacSha256 模式的时间戳头名
 * @param maxClockSkewSeconds hmacSha256 时间戳允许的最大偏差秒数
 */
public record HandshakeSpec(
        String mode,
        String secretField,
        String paramName,
        String headerName,
        String timestampHeader,
        long maxClockSkewSeconds) {

    public static final String MODE_NONE = "none";
    public static final String MODE_BEARER = "bearerToken";
    public static final String MODE_QUERY = "queryParam";
    public static final String MODE_HMAC = "hmacSha256";
    public static final String MODE_CUSTOM = "custom";

    private static final String DEFAULT_QUERY_PARAM = "access_token";
    private static final String DEFAULT_TIMESTAMP_HEADER = "X-Timestamp";
    private static final long DEFAULT_MAX_CLOCK_SKEW_SECONDS = 300;

    public static final HandshakeSpec NONE =
            new HandshakeSpec(
                    MODE_NONE,
                    null,
                    DEFAULT_QUERY_PARAM,
                    null,
                    DEFAULT_TIMESTAMP_HEADER,
                    DEFAULT_MAX_CLOCK_SKEW_SECONDS);

    /**
     * 从配置表单 schema 的根节点解析。
     *
     * <p>反向连接必须显式声明 {@code x-handshake}；缺失或非法时适配器拒绝就绪。正向连接不解析该配置。
     */
    public static HandshakeSpec from(JsonNode schema) {
        JsonNode node = schema == null ? null : schema.get("x-handshake");
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("连接类型必须显式声明 x-handshake");
        }
        HandshakeSpec spec =
                new HandshakeSpec(
                        text(node, "mode", MODE_NONE),
                        text(node, "secretField", null),
                        text(node, "paramName", DEFAULT_QUERY_PARAM),
                        text(node, "headerName", null),
                        text(node, "timestampHeader", DEFAULT_TIMESTAMP_HEADER),
                        positiveLong(node, "maxClockSkewSeconds", DEFAULT_MAX_CLOCK_SKEW_SECONDS));
        if (!java.util.Set.of(MODE_NONE, MODE_BEARER, MODE_QUERY, MODE_HMAC, MODE_CUSTOM)
                .contains(spec.mode())) {
            throw new IllegalArgumentException("不支持的 x-handshake.mode: " + spec.mode());
        }
        if (spec.requiresSecret() && (spec.secretField() == null || spec.secretField().isBlank())) {
            throw new IllegalArgumentException("x-handshake.secretField 未配置");
        }
        return spec;
    }

    /** 是否需要取密钥值。{@code none} 不需要。 */
    public boolean requiresSecret() {
        return !MODE_NONE.equals(mode);
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || !value.isString() || value.asString().isBlank()
                ? fallback
                : value.asString();
    }

    private static long positiveLong(JsonNode node, String field, long fallback) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            return fallback;
        }
        long seconds = value.asLong();
        return seconds > 0 && seconds <= 3600 ? seconds : fallback;
    }
}
