package com.loom.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.loom.connection.handshake.BuiltinHandshakeValidators;
import com.loom.connection.handshake.HandshakeRequest;
import com.loom.connection.handshake.HandshakeSpec;
import com.loom.connection.handshake.HandshakeValidator;
import com.loom.connection.handshake.HandshakeValidatorRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link ConnectionConfig} 的装配测试。
 *
 * <p>这个类看着像「纯配置」，其实藏着一处安全逻辑：注册表对同名 mode 是「后者覆盖前者」， 所以自定义校验器一旦与内置同名，就能静默顶掉内置实现 —— 一个叫 {@code "none"}
 * 的自定义项 可以直接关掉全部握手校验。这层剔除逻辑必须有测试守着，否则将来重构时删掉它不会有任何报错。
 */
@DisplayName("连接模块装配")
class ConnectionConfigTest {

    private final ConnectionConfig config = new ConnectionConfig();

    /** 造一个「永远放行」的校验器 —— 正是最需要被挡住的那类实现。 */
    private static HandshakeValidator alwaysAllow(String mode) {
        return new HandshakeValidator() {
            @Override
            public String mode() {
                return mode;
            }

            @Override
            public String failureReason(
                    HandshakeSpec spec, String secret, HandshakeRequest request) {
                return null;
            }
        };
    }

    @Test
    @DisplayName("无自定义校验器时，内置实现一个都不能少")
    void shouldKeepAllBuiltins() {
        HandshakeValidatorRegistry registry = config.handshakeValidatorRegistry(List.of());

        // 若这里退化成「只注册自定义项」，所有握手都会被判为未知模式而拒绝
        for (HandshakeValidator builtin : BuiltinHandshakeValidators.all()) {
            assertThat(registry.supports(builtin.mode()))
                    .as("内置校验器 %s 不应丢失", builtin.mode())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("自定义校验器被追加，内置仍然可用")
    void shouldAppendCustomValidator() {
        HandshakeValidatorRegistry registry =
                config.handshakeValidatorRegistry(List.of(alwaysAllow("my-custom-mode")));

        assertThat(registry.supports("my-custom-mode")).isTrue();
        assertThat(registry.supports("none")).isTrue();
    }

    @Test
    @DisplayName("与内置同名的自定义校验器被剔除：放行一切的 bearerToken 不能顶掉内置校验")
    void shouldRejectCustomValidatorClashingWithBuiltin() {
        // 关键安全用例：内置 bearerToken 会真正校验密钥，而自定义版本放行一切。
        // 若剔除逻辑失效，下面这次「无凭据」校验就会通过（返回 null）。
        HandshakeValidator malicious = alwaysAllow("bearerToken");

        HandshakeValidatorRegistry registry = config.handshakeValidatorRegistry(List.of(malicious));

        HandshakeSpec spec = new HandshakeSpec("bearerToken", "token", null, null, null);
        HandshakeRequest noCredentials = new HandshakeRequest("/ws/abc", Map.of(), Map.of());

        String reason = registry.validate(spec, "s3cret", noCredentials);

        assertThat(reason).as("内置 bearerToken 必须生效，不能使用自定义的放行实现").isNotNull();
    }

    @Test
    @DisplayName("同名冲突不影响其他自定义项注册")
    void shouldStillRegisterNonClashingValidators() {
        HandshakeValidatorRegistry registry =
                config.handshakeValidatorRegistry(
                        List.of(alwaysAllow("none"), alwaysAllow("another-mode")));

        assertThat(registry.supports("another-mode")).isTrue();
        assertThat(registry.supports("none")).isTrue();
    }

    @Test
    @DisplayName("IpcCodec Bean 可正常构造")
    void shouldBuildIpcCodec() {
        assertThat(config.ipcCodec(new ObjectMapper())).isNotNull();
    }
}
