package com.loom.connection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loom.common.exception.BusinessException;
import com.loom.connection.domain.ConnectionTypeDescriptor;
import com.loom.connection.domain.Direction;
import com.loom.connection.handshake.HandshakeSpec;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("config 密钥掩码")
class ConfigMaskingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String text) {
        return mapper.readTree(text);
    }

    /** 与假适配器声明一致的 schema：token 标了 x-secret，targetUrl 没有。 */
    private ConnectionTypeDescriptor descriptorWith(String secretField) {
        String schema =
                """
                {
                  "type": "object",
                  "properties": {
                    "token": {"type": "string", "x-secret": true},
                    "targetUrl": {"type": "string"}
                  }
                }
                """;
        return new ConnectionTypeDescriptor(
                "fake",
                "假适配器",
                Direction.REVERSE,
                mapper.readTree(schema),
                secretField == null
                        ? HandshakeSpec.NONE
                        : new HandshakeSpec("queryParam", secretField, "access_token", null, null),
                List.of(),
                "fake-adapter",
                true);
    }

    @Nested
    @DisplayName("密钥字段识别")
    class 密钥字段识别 {

        @Test
        @DisplayName("schema 标了 x-secret 的字段应被识别")
        void shouldFindSecretFlaggedField() {
            assertThat(ConfigMasking.secretFields(descriptorWith(null))).containsExactly("token");
        }

        @Test
        @DisplayName("握手指定的 secretField 也应被识别，即使 schema 没标")
        void shouldFindHandshakeSecretField() {
            ConnectionTypeDescriptor descriptor =
                    new ConnectionTypeDescriptor(
                            "fake",
                            "假适配器",
                            Direction.REVERSE,
                            mapper.readTree("{\"type\":\"object\"}"),
                            new HandshakeSpec("bearerToken", "accessToken", null, null, null),
                            List.of(),
                            "fake-adapter",
                            true);

            assertThat(ConfigMasking.secretFields(descriptor)).containsExactly("accessToken");
        }

        @Test
        @DisplayName("mode=none 时不该因为 secretField 有值就误判为密钥")
        void shouldIgnoreSecretFieldWhenModeNone() {
            ConnectionTypeDescriptor descriptor =
                    new ConnectionTypeDescriptor(
                            "fake",
                            "假适配器",
                            Direction.FORWARD,
                            mapper.readTree("{\"type\":\"object\"}"),
                            new HandshakeSpec("none", "token", null, null, null),
                            List.of(),
                            "fake-adapter",
                            true);

            assertThat(ConfigMasking.secretFields(descriptor)).isEmpty();
        }

        @Test
        @DisplayName("适配器不在线（描述为 null）时应安全返回空集合，而不是抛异常")
        void shouldTolerateMissingDescriptor() {
            assertThat(ConfigMasking.secretFields(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("打掩码")
    class 打掩码 {

        @Test
        @DisplayName("只替换密钥字段，其他字段原样保留")
        void shouldMaskOnlySecretFields() {
            JsonNode masked =
                    ConfigMasking.mask(
                            "{\"token\":\"super-secret\",\"targetUrl\":\"ws://x\"}",
                            Set.of("token"),
                            mapper);

            assertThat(masked.get("token").asString()).isEqualTo(ConfigMasking.MASK);
            assertThat(masked.get("targetUrl").asString()).isEqualTo("ws://x");
            assertThat(masked.toString()).doesNotContain("super-secret");
        }

        @Test
        @DisplayName("没有密钥字段时内容不变")
        void shouldReturnOriginalWhenNoSecretFields() {
            JsonNode masked = ConfigMasking.mask("{\"targetUrl\":\"ws://x\"}", Set.of(), mapper);

            assertThat(masked.get("targetUrl").asString()).isEqualTo("ws://x");
        }

        @Test
        @DisplayName("密钥字段为空串时不该替换成掩码 —— 否则会凭空造出一个密钥")
        void shouldNotMaskBlankSecret() {
            JsonNode masked = ConfigMasking.mask("{\"token\":\"\"}", Set.of("token"), mapper);
            assertThat(masked.get("token").asString()).isEmpty();
        }

        @Test
        @DisplayName("库里已有脏数据（不是 JSON 对象）时返回 null，不能把查询接口带崩")
        void shouldNotThrowOnCorruptedStoredConfig() {
            assertThat(ConfigMasking.mask("not-json", Set.of("token"), mapper)).isNull();
            assertThat(ConfigMasking.mask("[1,2]", Set.of("token"), mapper)).isNull();
        }
    }

    @Nested
    @DisplayName("还原掩码")
    class 还原掩码 {

        @Test
        @DisplayName("原样回传哨兵时应保留库里的真实密钥")
        void shouldRestoreMaskedSecret() {
            String restored =
                    ConfigMasking.restore(
                            json("{\"token\":\"" + ConfigMasking.MASK + "\"}"),
                            "{\"token\":\"real\"}",
                            Set.of("token"),
                            mapper);

            assertThat(restored).isEqualTo("{\"token\":\"real\"}");
        }

        @Test
        @DisplayName("用户填了新密钥时应采用新值，不能被旧值覆盖")
        void shouldPreferNewSecret() {
            String restored =
                    ConfigMasking.restore(
                            json("{\"token\":\"new-one\"}"),
                            "{\"token\":\"real\"}",
                            Set.of("token"),
                            mapper);

            assertThat(restored).isEqualTo("{\"token\":\"new-one\"}");
        }

        @Test
        @DisplayName("库里本来就没有该字段时收到哨兵，应删除字段而不是写入哨兵")
        void shouldRemoveFieldWhenNoStoredValue() {
            String restored =
                    ConfigMasking.restore(
                            json("{\"token\":\"" + ConfigMasking.MASK + "\"}"),
                            "{\"targetUrl\":\"ws://x\"}",
                            Set.of("token"),
                            mapper);

            assertThat(restored).doesNotContain("token");
        }

        @Test
        @DisplayName("非密钥字段即使等于哨兵字符串也不该被替换")
        void shouldNotTouchNonSecretFields() {
            String restored =
                    ConfigMasking.restore(
                            json("{\"nickname\":\"" + ConfigMasking.MASK + "\"}"),
                            "{\"nickname\":\"old\"}",
                            Set.of("token"),
                            mapper);

            assertThat(restored).isEqualTo("{\"nickname\":\"" + ConfigMasking.MASK + "\"}");
        }

        @Test
        @DisplayName("不得修改调用方传进来的对象 —— 否则重试或复用时会带着已还原的值")
        void shouldNotMutateInput() {
            JsonNode incoming = json("{\"token\":\"" + ConfigMasking.MASK + "\"}");

            ConfigMasking.restore(incoming, "{\"token\":\"real\"}", Set.of("token"), mapper);

            assertThat(incoming.get("token").asString()).isEqualTo(ConfigMasking.MASK);
        }
    }

    @Nested
    @DisplayName("JSON 校验")
    class JSON校验 {

        @Test
        @DisplayName("合法对象应通过，并规范化输出")
        void shouldAcceptObject() {
            assertThat(ConfigMasking.requireJsonObject(json("{ \"a\" : 1 }")))
                    .isEqualTo("{\"a\":1}");
        }

        @Test
        @DisplayName("数组 / 标量不是对象，应拒绝")
        void shouldRejectNonObject() {
            assertThatThrownBy(() -> ConfigMasking.requireJsonObject(json("[1,2]")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("必须是 JSON 对象");
            assertThatThrownBy(() -> ConfigMasking.requireJsonObject(json("\"x\"")))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("null 应给出可读报错，而不是 NPE")
        void shouldRejectNull() {
            assertThatThrownBy(() -> ConfigMasking.requireJsonObject(null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不能为空");
        }
    }
}
