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
 */
public record HandshakeSpec(
        String mode,
        String secretField,
        String paramName,
        String headerName,
        String timestampHeader) {

    public static final String MODE_NONE = "none";
    public static final String MODE_BEARER = "bearerToken";
    public static final String MODE_QUERY = "queryParam";
    public static final String MODE_HMAC = "hmacSha256";
    public static final String MODE_CUSTOM = "custom";

    private static final String DEFAULT_QUERY_PARAM = "access_token";
    private static final String DEFAULT_TIMESTAMP_HEADER = "X-Timestamp";

    public static final HandshakeSpec NONE =
            new HandshakeSpec(MODE_NONE, null, DEFAULT_QUERY_PARAM, null, DEFAULT_TIMESTAMP_HEADER);

    /**
     * 从配置表单 schema 的根节点解析。
     *
     * <p>缺失 {@code x-handshake} 时返回 {@link #NONE}，而不是报错 —— 插件可以不声明握手校验（虽然通常不建议）。
     */
    public static HandshakeSpec from(JsonNode schema) {
        JsonNode node = schema == null ? null : schema.get("x-handshake");
        if (node == null || !node.isObject()) {
            return NONE;
        }
        return new HandshakeSpec(
                text(node, "mode", MODE_NONE),
                text(node, "secretField", null),
                text(node, "paramName", DEFAULT_QUERY_PARAM),
                text(node, "headerName", null),
                text(node, "timestampHeader", DEFAULT_TIMESTAMP_HEADER));
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
}
