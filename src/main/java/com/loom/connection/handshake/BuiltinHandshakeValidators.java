package com.loom.connection.handshake;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 内置握手校验器。
 *
 * <p>放在一起而不是拆成 4 个文件：每个都只有十几行，分散反而难读。
 *
 * <h2>为什么全部用常量时间比较</h2>
 *
 * <p>{@code a.equals(b)} 在第一个不同字符处就返回，攻击者能通过测量响应时间逐字节猜出密钥。 所以一律用 {@link MessageDigest#isEqual} ——
 * 它总是比较完整个数组。 这不是过度设计：握手是外部可触达的入口，时序侧信道在这里完全现实。
 */
public final class BuiltinHandshakeValidators {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String DEFAULT_AUTH_HEADER = "Authorization";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private BuiltinHandshakeValidators() {}

    /** 全部内置校验器。 */
    public static List<HandshakeValidator> all() {
        return List.of(new None(), new BearerToken(), new QueryParam(), new HmacSha256());
    }

    /** 不校验。适用于「路径本身就是凭据」的场景（path 已由 Java 随机生成）。 */
    public static final class None implements HandshakeValidator {

        @Override
        public String mode() {
            return HandshakeSpec.MODE_NONE;
        }

        @Override
        public String failureReason(HandshakeSpec spec, String secret, HandshakeRequest request) {
            return null;
        }
    }

    /** {@code Authorization: Bearer <secret>}，头名可通过 {@code headerName} 覆盖。 */
    public static final class BearerToken implements HandshakeValidator {

        @Override
        public String mode() {
            return HandshakeSpec.MODE_BEARER;
        }

        @Override
        public String failureReason(HandshakeSpec spec, String secret, HandshakeRequest request) {
            if (isBlank(secret)) {
                return "连接未配置密钥（secretField=" + spec.secretField() + "）";
            }
            String headerName =
                    isBlank(spec.headerName()) ? DEFAULT_AUTH_HEADER : spec.headerName();
            String actual = request.header(headerName);
            if (actual == null) {
                return "缺少请求头 " + headerName;
            }
            String token =
                    actual.startsWith(BEARER_PREFIX)
                            ? actual.substring(BEARER_PREFIX.length())
                            : actual;
            return constantTimeEquals(token, secret) ? null : "凭据不匹配";
        }
    }

    /** URL 查询参数等于密钥。 */
    public static final class QueryParam implements HandshakeValidator {

        @Override
        public String mode() {
            return HandshakeSpec.MODE_QUERY;
        }

        @Override
        public String failureReason(HandshakeSpec spec, String secret, HandshakeRequest request) {
            if (isBlank(secret)) {
                return "连接未配置密钥（secretField=" + spec.secretField() + "）";
            }
            String actual = request.queryParam(spec.paramName());
            if (actual == null) {
                return "缺少查询参数 " + spec.paramName();
            }
            return constantTimeEquals(actual, secret) ? null : "凭据不匹配";
        }
    }

    /**
     * HMAC-SHA256 签名校验。
     *
     * <p><b>签名串的约定</b>：{@code HMAC-SHA256(secret, <时间戳头的值> + <请求路径>)}， 结果转小写十六进制，与 {@code
     * headerName} 指定的头比对。
     *
     * <p>⚠️ 这是本项目的约定，不是某个平台的通用标准。真实平台的签名串构成可能不同 （有的把 body 也算进去、有的用 base64 而非 hex、有的带额外前缀）。
     * 遇到不匹配时应该用 {@code x-handshake.mode = "custom"} 走适配器回调， 而不是硬改这个通用实现去迁就某一个平台。
     */
    public static final class HmacSha256 implements HandshakeValidator {

        @Override
        public String mode() {
            return HandshakeSpec.MODE_HMAC;
        }

        @Override
        public String failureReason(HandshakeSpec spec, String secret, HandshakeRequest request) {
            if (isBlank(secret)) {
                return "连接未配置密钥（secretField=" + spec.secretField() + "）";
            }
            if (isBlank(spec.headerName())) {
                return "HmacSha256 模式必须声明 headerName";
            }
            String timestamp = request.header(spec.timestampHeader());
            if (isBlank(timestamp)) {
                return "缺少时间戳头 " + spec.timestampHeader();
            }
            String provided = request.header(spec.headerName());
            if (isBlank(provided)) {
                return "缺少签名头 " + spec.headerName();
            }
            long epochSeconds;
            try {
                epochSeconds = Long.parseLong(timestamp.strip());
                if (timestamp.strip().length() >= 13) {
                    epochSeconds /= 1000;
                }
            } catch (NumberFormatException e) {
                return "时间戳格式不正确";
            }
            long now = Instant.now().getEpochSecond();
            if (epochSeconds < now - spec.maxClockSkewSeconds()
                    || epochSeconds > now + spec.maxClockSkewSeconds()) {
                return "时间戳已过期";
            }
            String expected = hmacHex(secret, timestamp + request.path());
            return constantTimeEquals(provided.strip().toLowerCase(), expected) ? null : "签名不匹配";
        }

        private static String hmacHex(String secret, String message) {
            try {
                Mac mac = Mac.getInstance(HMAC_ALGORITHM);
                mac.init(
                        new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
                return HexFormat.of()
                        .formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
            } catch (java.security.GeneralSecurityException e) {
                // 算法名写死且 JDK 必然支持，走到这里说明运行环境异常 —— 不能放行
                throw new IllegalStateException("HMAC 计算失败", e);
            }
        }
    }

    private static boolean constantTimeEquals(String actual, String expected) {
        if (actual == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
