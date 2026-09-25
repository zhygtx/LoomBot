package com.loom.runtime.ipc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link IpcChannel} 的单元测试。
 *
 * <p>IPC 是插件通信的命脉，但此前只有集成测试覆盖（被 CI 排除），覆盖率停在 63%。 这里用内存流直接构造通道，不依赖 Python 进程，因此可以进 CI。
 *
 * <p>测试重点是那些「只会在异常时序下出现」的行为：
 *
 * <ul>
 *   <li>读循环正常结束 vs 异常断开的区分 —— 决定了日志级别和是否上报 cause；
 *   <li>单行协议违规只丢一行、不拆通道 —— 插件里一句 {@code print()} 就会触发；
 *   <li>{@link IpcChannel#close()} 的幂等性，以及关闭时不把正常挥手记成故障。
 * </ul>
 *
 * <p>注意：{@code readLoop} 跑在守护线程里，所以断言必须等待而非直接读。
 */
@DisplayName("IPC 通道")
class IpcChannelTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    private final IpcCodec codec = new IpcCodec(new ObjectMapper());

    /**
     * 轮询等待条件成立。
     *
     * <p>刻意不引入 Awaitility：为了 4 处等待新增一个测试依赖并不划算， 而这里的条件都很简单（列表长度）。超时后让断言去失败，报错信息更直观。
     */
    private static void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
    }

    /** 记录所有回调，便于断言。 */
    private static final class RecordingListener implements IpcListener {

        final List<IpcMessage> messages = new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> closedCause = new AtomicReference<>();
        final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public void onMessage(IpcMessage message) {
            messages.add(message);
        }

        @Override
        public void onClosed(Throwable cause) {
            closedCause.set(cause);
            closed.countDown();
        }
    }

    private IpcChannel channelOver(String input, ByteArrayOutputStream sink, IpcListener listener) {
        InputStream in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        IpcChannel channel = new IpcChannel("test", codec, in, sink);
        channel.setListener(listener);
        return channel;
    }

    private static String line(String type, String payloadJson) {
        return payloadJson == null
                ? "{\"type\":\"" + type + "\"}"
                : "{\"type\":\"" + type + "\",\"payload\":" + payloadJson + "}";
    }

    @Nested
    @DisplayName("读循环")
    class 读循环 {

        @Test
        @DisplayName("逐行解码并回调，计数正确")
        void shouldDecodeEachLine() throws Exception {
            RecordingListener listener = new RecordingListener();
            IpcChannel channel =
                    channelOver(
                            line("evt.a", null) + "\n" + line("evt.b", "{\"k\":1}") + "\n",
                            new ByteArrayOutputStream(),
                            listener);

            channel.start();
            assertThat(listener.closed.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

            assertThat(listener.messages).hasSize(2);
            assertThat(listener.messages.get(0).type()).isEqualTo("evt.a");
            assertThat(listener.messages.get(1).type()).isEqualTo("evt.b");
            assertThat(channel.receivedCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("空行被跳过，不计入协议违规")
        void shouldSkipBlankLines() throws Exception {
            RecordingListener listener = new RecordingListener();
            IpcChannel channel =
                    channelOver(
                            "\n   \n" + line("evt.a", null) + "\n\n",
                            new ByteArrayOutputStream(),
                            listener);

            channel.start();
            assertThat(listener.closed.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

            assertThat(listener.messages).hasSize(1);
            // 空行不是插件的错，不能算违规
            assertThat(channel.protocolViolations()).isZero();
        }

        @Test
        @DisplayName("非法 JSON 只丢该行并记违规，通道继续工作")
        void shouldDropBadLineButKeepChannelAlive() throws Exception {
            RecordingListener listener = new RecordingListener();
            IpcChannel channel =
                    channelOver(
                            "这不是 JSON\n" + line("evt.after", null) + "\n",
                            new ByteArrayOutputStream(),
                            listener);

            channel.start();
            assertThat(listener.closed.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

            // 关键：一句 print() 不能把整条通道拆掉
            assertThat(channel.protocolViolations()).isEqualTo(1);
            assertThat(listener.messages).hasSize(1);
            assertThat(listener.messages.get(0).type()).isEqualTo("evt.after");
        }

        @Test
        @DisplayName("监听器抛异常只丢该条消息，不影响后续")
        void shouldSurviveListenerFailure() throws Exception {
            List<String> seen = new ArrayList<>();
            IpcListener listener =
                    new IpcListener() {
                        @Override
                        public void onMessage(IpcMessage message) {
                            seen.add(message.type());
                            if ("evt.boom".equals(message.type())) {
                                throw new IllegalStateException("业务处理炸了");
                            }
                        }

                        @Override
                        public void onClosed(Throwable cause) {
                            // 这个用例只关心消息处理是否被中断
                        }
                    };
            IpcChannel channel =
                    channelOver(
                            line("evt.boom", null) + "\n" + line("evt.ok", null) + "\n",
                            new ByteArrayOutputStream(),
                            listener);

            channel.start();
            awaitUntil(() -> seen.size() == 2);

            assertThat(seen).containsExactly("evt.boom", "evt.ok");
            // 业务异常不算协议违规
            assertThat(channel.protocolViolations()).isZero();
        }

        @Test
        @DisplayName("对端直接结束输出：onClosed(null)，不算故障")
        void shouldReportCleanEofAsNullCause() throws Exception {
            RecordingListener listener = new RecordingListener();
            IpcChannel channel = channelOver("", new ByteArrayOutputStream(), listener);

            channel.start();
            assertThat(listener.closed.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

            // cause 为 null 是「正常结束」的约定信号
            assertThat(listener.closedCause.get()).isNull();
        }

        @Test
        @DisplayName("读取中途抛 IOException：cause 被上报，不会误判成正常关闭")
        void shouldReportIoFailureAsCause() throws Exception {
            RecordingListener listener = new RecordingListener();
            InputStream failing =
                    new InputStream() {
                        private boolean first = true;

                        @Override
                        public int read() throws IOException {
                            if (first) {
                                first = false;
                                return 'x';
                            }
                            throw new IOException("管道破裂");
                        }

                        @Override
                        public int read(byte[] b, int off, int len) throws IOException {
                            throw new IOException("管道破裂");
                        }
                    };
            IpcChannel channel =
                    new IpcChannel("test", codec, failing, new ByteArrayOutputStream());
            channel.setListener(listener);

            channel.start();
            assertThat(listener.closed.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

            assertThat(listener.closedCause.get()).isInstanceOf(IOException.class);
        }
    }

    @Nested
    @DisplayName("写入")
    class 写入 {

        @Test
        @DisplayName("编码为单行 NDJSON 并成功计数")
        void shouldWriteSingleNdjsonLine() throws Exception {
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            IpcChannel channel = channelOver("", sink, new RecordingListener());

            ObjectNode payload = codec.newPayload();
            channel.send(new IpcMessage("cmd.x", null, payload));

            String written = sink.toString(StandardCharsets.UTF_8);
            assertThat(written).endsWith("\n");
            assertThat(written.trim()).doesNotContain("\n");
            assertThat(channel.sentCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("通道已关闭后写入被拒绝，不抛异常")
        void shouldRejectWriteAfterClose() throws Exception {
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            IpcChannel channel = channelOver("", sink, new RecordingListener());
            channel.close();

            // 关闭后写入是竞态下的正常现象，不能把异常抛给调用方
            channel.send(new IpcMessage("cmd.x", null, codec.newPayload()));

            assertThat(channel.sentCount()).isZero();
            assertThat(channel.isClosing()).isTrue();
        }

        @Test
        @DisplayName("底层写入失败时计入日志但不抛出")
        void shouldSwallowWriteFailure() {
            OutputStream broken =
                    new OutputStream() {
                        @Override
                        public void write(int b) throws IOException {
                            throw new IOException("对端已消失");
                        }
                    };
            IpcChannel channel =
                    new IpcChannel("test", codec, new ByteArrayInputStream(new byte[0]), broken);
            channel.setListener(new RecordingListener());

            channel.send(new IpcMessage("cmd.x", null, codec.newPayload()));

            assertThat(channel.sentCount()).isZero();
        }
    }

    @Nested
    @DisplayName("关闭")
    class 关闭 {

        @Test
        @DisplayName("close 幂等，重复调用安全")
        void shouldBeIdempotent() {
            IpcChannel channel =
                    channelOver("", new ByteArrayOutputStream(), new RecordingListener());

            channel.close();
            channel.close();
            channel.close();

            assertThat(channel.isClosing()).isTrue();
        }

        @Test
        @DisplayName("主动 close 后读循环结束不记为故障")
        void shouldNotReportFailureAfterIntentionalClose() throws Exception {
            RecordingListener listener = new RecordingListener();
            // 输入不结束，靠 close() 打断
            InputStream blocking =
                    new InputStream() {
                        @Override
                        public int read() throws IOException {
                            return -1;
                        }

                        @Override
                        public int read(byte[] b, int off, int len) throws IOException {
                            return -1;
                        }
                    };
            IpcChannel channel =
                    new IpcChannel("test", codec, blocking, new ByteArrayOutputStream());
            channel.setListener(listener);

            channel.start();
            assertThat(listener.closed.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            channel.close();

            // 主动关闭不应产生「断开」故障上报
            assertThat(listener.closedCause.get()).isNull();
        }

        @Test
        @DisplayName("flush 失败也不影响关闭流程")
        void shouldCloseEvenIfFlushFails() {
            OutputStream failingFlush =
                    new OutputStream() {
                        @Override
                        public void write(int b) {
                            // 丢弃
                        }

                        @Override
                        public void flush() throws IOException {
                            throw new IOException("flush 失败");
                        }
                    };
            IpcChannel channel =
                    new IpcChannel(
                            "test", codec, new ByteArrayInputStream(new byte[0]), failingFlush);
            channel.setListener(new RecordingListener());

            // 关闭路径上的 flush 失败必须被吞掉，否则清理流程会被中断
            channel.close();

            assertThat(channel.isClosing()).isTrue();
        }
    }

    @Test
    @DisplayName("默认监听器是空实现，未设置监听器时不 NPE")
    void shouldUseNoopListenerByDefault() {
        IpcChannel channel =
                new IpcChannel(
                        "test",
                        codec,
                        new ByteArrayInputStream(new byte[0]),
                        new ByteArrayOutputStream());

        // 不调用 setListener，start 不应抛异常
        channel.start();

        assertThat(channel.protocolViolations()).isZero();
    }
}
