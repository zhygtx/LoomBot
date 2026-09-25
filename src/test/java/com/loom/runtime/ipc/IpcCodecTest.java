package com.loom.runtime.ipc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * NDJSON 编解码的单元测试。
 *
 * <p>重点在**畸形输入**：协议流是外部进程给的，一行坏数据不能把读取线程弄死， 也不能被静默吞掉 —— 必须抛出可定位的异常。
 */
@DisplayName("IPC 编解码")
class IpcCodecTest {

    private final IpcCodec codec = new IpcCodec(new ObjectMapper());

    @Nested
    @DisplayName("解码")
    class 解码 {

        @Test
        @DisplayName("完整信封应能解出 id / type / payload")
        void shouldDecodeFullEnvelope() {
            IpcMessage message =
                    codec.decode(
                            "{\"id\":\"req-1\",\"type\":\"hello\",\"payload\":{\"connectionType\":\"fake\"}}");

            assertThat(message.id()).isEqualTo("req-1");
            assertThat(message.type()).isEqualTo("hello");
            assertThat(message.stringField("connectionType")).isEqualTo("fake");
        }

        @Test
        @DisplayName("没有 id 的通知类消息应能解出（id 为 null）")
        void shouldDecodeWithoutId() {
            IpcMessage message = codec.decode("{\"type\":\"ws.opened\",\"payload\":{}}");

            assertThat(message.id()).isNull();
            assertThat(message.type()).isEqualTo("ws.opened");
        }

        @Test
        @DisplayName("payload 可缺省 —— Java 发 shutdown 时就不带 payload")
        void shouldDecodeWithoutPayload() {
            IpcMessage message = codec.decode("{\"type\":\"shutdown\"}");

            assertThat(message.type()).isEqualTo("shutdown");
            assertThat(message.payload()).isNull();
            // 对缺省 payload 取字段应返回 null，而不是 NPE
            assertThat(message.stringField("anything")).isNull();
            assertThat(message.field("anything")).isNull();
        }

        @Test
        @DisplayName("缺少 type 应报协议错 —— 没有 type 就无法分发")
        void shouldRejectMissingType() {
            assertThatThrownBy(() -> codec.decode("{\"payload\":{}}"))
                    .isInstanceOf(IpcProtocolException.class)
                    .hasMessageContaining("type");
        }

        @Test
        @DisplayName("非 JSON 应报协议错，并附上原始内容片段")
        void shouldRejectNonJson() {
            assertThatThrownBy(() -> codec.decode("this is not json"))
                    .isInstanceOf(IpcProtocolException.class)
                    .hasMessageContaining("JSON");
        }

        @Test
        @DisplayName("JSON 数组不是信封，应报协议错")
        void shouldRejectArray() {
            assertThatThrownBy(() -> codec.decode("[1,2,3]"))
                    .isInstanceOf(IpcProtocolException.class);
        }

        @Test
        @DisplayName("超长坏行应被截断，避免把整行塞进日志")
        void shouldAbbreviateLongBadLine() {
            String huge = "x".repeat(5000);

            assertThatThrownBy(() -> codec.decode(huge))
                    .isInstanceOf(IpcProtocolException.class)
                    .satisfies(
                            e ->
                                    assertThat(e.getMessage().length())
                                            .as("错误信息应被截断，否则一行坏数据能刷爆日志")
                                            .isLessThan(400));
        }

        @Test
        @DisplayName("type 不是字符串应报协议错")
        void shouldRejectNonStringType() {
            assertThatThrownBy(() -> codec.decode("{\"type\":123}"))
                    .isInstanceOf(IpcProtocolException.class);
        }
    }

    @Nested
    @DisplayName("编码")
    class 编码 {

        @Test
        @DisplayName("普通消息应编成单行 JSON —— NDJSON 的前提是绝不能有换行")
        void shouldEncodeSingleLine() {
            ObjectNode payload = JsonNodeFactory.instance.objectNode();
            payload.put("connectionId", "42");

            String line = codec.encode(IpcMessage.of("conn.open", payload));

            assertThat(line).doesNotContain("\n");
            assertThat(codec.decode(line).stringField("connectionId")).isEqualTo("42");
        }

        @Test
        @DisplayName("没有 id 时不应输出 id 字段 —— 免得对端以为要回复")
        void shouldOmitIdWhenAbsent() {
            String line = codec.encode(IpcMessage.of("ws.opened", null));

            assertThat(line).doesNotContain("\"id\"");
        }

        @Test
        @DisplayName("带 id 的请求应输出 id")
        void shouldIncludeIdForRequest() {
            String line = codec.encode(IpcMessage.request("req-9", "ws.frame", null));

            assertThat(line).contains("\"id\":\"req-9\"");
        }

        @Test
        @DisplayName("payload 为 null 时不应输出 payload 字段")
        void shouldOmitNullPayload() {
            String line = codec.encode(IpcMessage.of("shutdown", null));

            assertThat(line).doesNotContain("payload");
        }

        @Test
        @DisplayName("编解码应可往返，包括二进制内容")
        void shouldRoundTripBinaryContent() {
            String base64 = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, -1});
            ObjectNode payload = JsonNodeFactory.instance.objectNode();
            payload.put("encoding", "base64");
            payload.put("content", base64);

            IpcMessage decoded = codec.decode(codec.encode(IpcMessage.of("ws.send", payload)));

            assertThat(decoded.stringField("encoding")).isEqualTo("base64");
            assertThat(decoded.stringField("content")).isEqualTo(base64);
        }
    }

    @Nested
    @DisplayName("工具方法")
    class 工具方法 {

        @Test
        @DisplayName("newPayload 应返回空对象，供上层填充")
        void shouldCreateEmptyPayload() {
            ObjectNode payload = codec.newPayload();

            assertThat(payload.isObject()).isTrue();
            assertThat(payload.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("取字段时缺失应返回 null，而不是抛异常")
        void shouldReturnNullForMissingField() {
            IpcMessage message = codec.decode("{\"type\":\"t\",\"payload\":{\"a\":1}}");

            assertThat(message.stringField("missing")).isNull();
            assertThat(message.field("missing")).isNull();
            assertThat(message.stringField("a")).isEqualTo("1");
        }

        @Test
        @DisplayName("显式 null 的字段应与缺失同样处理 —— 免得调用方到处判 isNull")
        void shouldTreatExplicitNullAsMissing() {
            IpcMessage message = codec.decode("{\"type\":\"t\",\"payload\":{\"a\":null}}");

            assertThat(message.field("a")).isNull();
            assertThat(message.stringField("a")).isNull();
        }

        @Test
        @DisplayName("isReply 应只对 reply 类型为真")
        void shouldDetectReply() {
            assertThat(codec.decode("{\"type\":\"reply\"}").isReply()).isTrue();
            assertThat(codec.decode("{\"type\":\"hello\"}").isReply()).isFalse();
        }
    }
}
