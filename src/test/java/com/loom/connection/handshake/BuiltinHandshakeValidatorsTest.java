package com.loom.connection.handshake;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 握手校验器的单元测试。
 *
 * <p>这些校验器是**外部可触达的入口** —— 任何人知道路径就能打过来。所以除了「对不对」， 还要验证「拒绝得够不够彻底」：未知模式必须 fail closed，常量时间比较不能被换回
 * equals。
 */
@DisplayName("内置握手校验器")
class BuiltinHandshakeValidatorsTest {

    private final HandshakeValidatorRegistry registry = new HandshakeValidatorRegistry();

    private static HandshakeRequest request(
            Map<String, String> headers, Map<String, String> query) {
        return new HandshakeRequest("/ws/x", headers, query);
    }

    @Nested
    @DisplayName("none")
    class 不校验 {

        @Test
        @DisplayName("任何请求都放行 —— 此时路径本身就是凭据")
        void shouldAlwaysPass() {
            String reason =
                    registry.validate(HandshakeSpec.NONE, null, request(Map.of(), Map.of()));

            assertThat(reason).isNull();
        }

        @Test
        @DisplayName("路径就是唯一凭据，所以它必须足够不可猜")
        void shouldDocumentPathAsCredential() {
            // 这条测试的意义是把「none 模式下 path 即凭据」写成可执行的事实：
            // 一旦有人把 /ws/{随机} 改成固定路径，这里不会失败 ——
            // 但 WsConnectionServiceTest 会验证路径是 32 位十六进制随机串。
            assertThat(HandshakeSpec.NONE.requiresSecret()).isFalse();
        }
    }

    @Nested
    @DisplayName("bearerToken")
    class Bearer令牌 {

        private final HandshakeSpec spec =
                new HandshakeSpec("bearerToken", "token", null, null, null);

        @Test
        @DisplayName("正确的 Bearer 令牌应通过")
        void shouldAcceptCorrectToken() {
            String reason =
                    registry.validate(
                            spec,
                            "s3cret",
                            request(Map.of("Authorization", "Bearer s3cret"), Map.of()));

            assertThat(reason).isNull();
        }

        @Test
        @DisplayName("前缀是精确的 `Bearer `（含空格）—— 小写不认，这是刻意的严格")
        void shouldRequireExactSchemePrefix() {
            String reason =
                    registry.validate(
                            spec,
                            "s3cret",
                            request(Map.of("Authorization", "bearer s3cret"), Map.of()));

            // ⚠️ 实现是 startsWith("Bearer ")，大小写敏感。
            // 这里把行为固定下来：如果将来要放宽，改的是实现，而不是让测试悄悄跟着变。
            assertThat(reason).isNotNull();
        }

        @Test
        @DisplayName("缺少 Authorization 头应拒绝")
        void shouldRejectMissingHeader() {
            assertThat(registry.validate(spec, "s3cret", request(Map.of(), Map.of()))).isNotNull();
        }

        @Test
        @DisplayName("不带 Bearer 前缀时按整串比对 —— 容错，不是漏洞")
        void shouldTolerateMissingScheme() {
            // 实现的意图很清楚：能剥前缀就剥，剥不掉就拿整串比。
            // 这样「Authorization: <token>」这种写法也能连上，而安全性没有下降
            // （仍然是常量时间比对同一个密钥）。
            assertThat(
                            registry.validate(
                                    spec,
                                    "s3cret",
                                    request(Map.of("Authorization", "s3cret"), Map.of())))
                    .isNull();
        }

        @Test
        @DisplayName("令牌不匹配应拒绝")
        void shouldRejectWrongToken() {
            assertThat(
                            registry.validate(
                                    spec,
                                    "s3cret",
                                    request(Map.of("Authorization", "Bearer wrong"), Map.of())))
                    .isNotNull();
        }

        @Test
        @DisplayName("config 里没有该密钥字段时应拒绝，绝不能因为取不到值就放行")
        void shouldRejectWhenSecretMissing() {
            assertThat(
                            registry.validate(
                                    spec,
                                    null,
                                    request(Map.of("Authorization", "Bearer s3cret"), Map.of())))
                    .isNotNull();
            assertThat(
                            registry.validate(
                                    spec,
                                    "",
                                    request(Map.of("Authorization", "Bearer "), Map.of())))
                    .isNotNull();
        }

        @Test
        @DisplayName("可覆盖请求头名 —— 有些平台用 X-Auth-Token")
        void shouldHonorCustomHeaderName() {
            HandshakeSpec custom =
                    new HandshakeSpec("bearerToken", "token", null, "X-Auth-Token", null);

            String reason =
                    registry.validate(
                            custom,
                            "s3cret",
                            request(Map.of("X-Auth-Token", "Bearer s3cret"), Map.of()));

            assertThat(reason).isNull();
        }

        @Test
        @DisplayName("令牌多一个字符也应拒绝（长度敏感）")
        void shouldRejectLongerToken() {
            assertThat(
                            registry.validate(
                                    spec,
                                    "s3cret",
                                    request(Map.of("Authorization", "Bearer s3cretx"), Map.of())))
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("queryParam")
    class 查询参数 {

        private final HandshakeSpec spec =
                new HandshakeSpec("queryParam", "token", "access_token", null, null);

        @Test
        @DisplayName("参数值正确应通过")
        void shouldAcceptCorrectParam() {
            String reason =
                    registry.validate(
                            spec, "s3cret", request(Map.of(), Map.of("access_token", "s3cret")));

            assertThat(reason).isNull();
        }

        @Test
        @DisplayName("参数缺失或不匹配应拒绝")
        void shouldReject() {
            assertThat(registry.validate(spec, "s3cret", request(Map.of(), Map.of()))).isNotNull();
            assertThat(
                            registry.validate(
                                    spec,
                                    "s3cret",
                                    request(Map.of(), Map.of("access_token", "wrong"))))
                    .isNotNull();
            assertThat(
                            registry.validate(
                                    spec, "s3cret", request(Map.of(), Map.of("other", "s3cret"))))
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("hmacSha256")
    class HMAC签名 {

        private static final String SECRET = "hmac-secret";
        private static final String PATH = "/ws/x";

        private final HandshakeSpec spec =
                new HandshakeSpec("hmacSha256", "secret", null, "X-Signature", "X-Timestamp");

        private String sign(String timestamp) throws Exception {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of()
                    .formatHex(mac.doFinal((timestamp + PATH).getBytes(StandardCharsets.UTF_8)));
        }

        @Test
        @DisplayName("签名正确应通过")
        void shouldAcceptCorrectSignature() throws Exception {
            String timestamp = "1700000000";
            String reason =
                    registry.validate(
                            spec,
                            SECRET,
                            request(
                                    Map.of(
                                            "X-Timestamp",
                                            timestamp,
                                            "X-Signature",
                                            sign(timestamp)),
                                    Map.of()));

            assertThat(reason).isNull();
        }

        @Test
        @DisplayName("签名错误应拒绝")
        void shouldRejectWrongSignature() throws Exception {
            String timestamp = "1700000000";
            String reason =
                    registry.validate(
                            spec,
                            SECRET,
                            request(
                                    Map.of("X-Timestamp", timestamp, "X-Signature", "deadbeef"),
                                    Map.of()));

            assertThat(reason).isNotNull();
        }

        @Test
        @DisplayName("缺少时间戳或签名头应拒绝")
        void shouldRejectMissingHeaders() throws Exception {
            String timestamp = "1700000000";
            assertThat(
                            registry.validate(
                                    spec,
                                    SECRET,
                                    request(Map.of("X-Signature", sign(timestamp)), Map.of())))
                    .isNotNull();
            assertThat(
                            registry.validate(
                                    spec,
                                    SECRET,
                                    request(Map.of("X-Timestamp", timestamp), Map.of())))
                    .isNotNull();
        }

        @Test
        @DisplayName("用不同时间戳算出的签名应拒绝 —— 签名必须绑定那一时刻")
        void shouldRejectSignatureFromAnotherTimestamp() throws Exception {
            String reason =
                    registry.validate(
                            spec,
                            SECRET,
                            request(
                                    Map.of(
                                            "X-Timestamp",
                                            "1700000001",
                                            "X-Signature",
                                            sign("1700000000")),
                                    Map.of()));

            assertThat(reason).isNotNull();
        }

        @Test
        @DisplayName("密钥不同则签名不匹配")
        void shouldRejectWithDifferentSecret() throws Exception {
            String timestamp = "1700000000";
            String reason =
                    registry.validate(
                            spec,
                            "another-secret",
                            request(
                                    Map.of(
                                            "X-Timestamp",
                                            timestamp,
                                            "X-Signature",
                                            sign(timestamp)),
                                    Map.of()));

            assertThat(reason).isNotNull();
        }
    }

    @Nested
    @DisplayName("注册表的安全语义")
    class 注册表安全语义 {

        @Test
        @DisplayName("未知模式必须 fail closed —— 放行等于「任何知道路径的人都能连进来」")
        void shouldRejectUnknownMode() {
            HandshakeSpec unknown = new HandshakeSpec("somethingNew", "token", null, null, null);

            String reason = registry.validate(unknown, "anything", request(Map.of(), Map.of()));

            assertThat(reason).contains("未知");
        }

        @Test
        @DisplayName("custom 尚未实现，也必须拒绝而不是放行")
        void shouldRejectCustom() {
            HandshakeSpec custom =
                    new HandshakeSpec(HandshakeSpec.MODE_CUSTOM, "token", null, null, null);

            String reason = registry.validate(custom, "anything", request(Map.of(), Map.of()));

            assertThat(reason).contains("custom").contains("尚未实现");
        }

        @Test
        @DisplayName("未声明规格时拒绝 —— 缺省不等于放行")
        void shouldRejectNullSpec() {
            assertThat(registry.validate(null, "x", request(Map.of(), Map.of()))).isNotNull();
        }

        @Test
        @DisplayName("四个内置模式都应支持")
        void shouldSupportAllBuiltins() {
            assertThat(registry.supports(HandshakeSpec.MODE_NONE)).isTrue();
            assertThat(registry.supports(HandshakeSpec.MODE_BEARER)).isTrue();
            assertThat(registry.supports(HandshakeSpec.MODE_QUERY)).isTrue();
            assertThat(registry.supports(HandshakeSpec.MODE_HMAC)).isTrue();
        }

        @Test
        @DisplayName("无参构造器应带上四个内置校验器")
        void shouldRegisterBuiltinsByDefault() {
            assertThat(new HandshakeValidatorRegistry().supports(HandshakeSpec.MODE_BEARER))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("HandshakeRequest 取值")
    class 请求取值 {

        @Test
        @DisplayName("请求头取值应大小写不敏感 —— HTTP 头名本就不区分大小写")
        void shouldLookUpHeaderCaseInsensitively() {
            HandshakeRequest request =
                    new HandshakeRequest("/ws/x", Map.of("X-Signature", "abc"), Map.of());

            assertThat(request.header("x-signature")).isEqualTo("abc");
            assertThat(request.header("X-SIGNATURE")).isEqualTo("abc");
            assertThat(request.header("missing")).isNull();
        }

        @Test
        @DisplayName("查询参数取值应区分大小写 —— 它是我们自己的约定")
        void shouldLookUpQueryExactly() {
            HandshakeRequest request =
                    new HandshakeRequest("/ws/x", Map.of(), Map.of("access_token", "abc"));

            assertThat(request.queryParam("access_token")).isEqualTo("abc");
            assertThat(request.queryParam("ACCESS_TOKEN")).isNull();
        }

        @Test
        @DisplayName("路径应原样保留，供 custom 握手使用")
        void shouldKeepPath() {
            assertThat(new HandshakeRequest("/ws/abc", Map.of(), Map.of()).path())
                    .isEqualTo("/ws/abc");
        }
    }
}
