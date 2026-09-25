package com.loom.runtime.ipc;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 双向 IPC 通道 —— 一条 NDJSON 消息流，绑定到某个 Python 子进程的 stdin/stdout。
 *
 * <h2>设计要点</h2>
 *
 * <ul>
 *   <li><b>读取在独立线程</b>：{@link IpcListener} 的回调运行在该线程上，实现必须快速返回
 *   <li><b>写入加锁</b>：多个业务线程可能同时发送，逐行写入必须串行，否则会撕裂 JSON
 *   <li><b>协议违规大声报错</b>：记录原始行 + 计数器，绝不静默丢弃 —— 否则「插件 print 了一句」这种问题会变成「消息莫名其妙丢失」
 *   <li><b>区分主动关闭与对端断开</b>：主动 close 不触发 {@code onClosed}
 * </ul>
 */
public final class IpcChannel implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(IpcChannel.class);

    private static final IpcListener NOOP =
            new IpcListener() {
                @Override
                public void onMessage(IpcMessage message) {
                    // 未设置监听者时丢弃
                }

                @Override
                public void onClosed(Throwable cause) {
                    // 未设置监听者时忽略
                }
            };

    private final String name;
    private final IpcCodec codec;
    private final BufferedReader reader;
    private final Writer writer;

    private final Object writeLock = new Object();
    private final AtomicBoolean closing = new AtomicBoolean(false);

    /** 协议违规累计次数。只在 {@code handleLine} 里自增，用于日志里的「第 N 次」。 */
    private final AtomicLong protocolViolations = new AtomicLong();

    private volatile IpcListener listener = NOOP;
    private volatile Thread readerThread;

    public IpcChannel(String name, IpcCodec codec, InputStream in, OutputStream out) {
        this.name = name;
        this.codec = codec;
        this.reader =
                new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 64 * 1024);
        this.writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
    }

    /** 启动读取线程。必须在设置好监听者之后调用。 */
    public void start() {
        Thread thread = new Thread(this::readLoop, "ipc-reader-" + name);
        thread.setDaemon(true);
        this.readerThread = thread;
        thread.start();
    }

    public void setListener(IpcListener listener) {
        this.listener = listener == null ? NOOP : listener;
    }

    /** 发送一条消息。通道已关闭时静默丢弃（并发关闭是正常情况，不作为错误）。 */
    public void send(IpcMessage message) {
        if (closing.get()) {
            return;
        }
        String line = codec.encode(message);
        synchronized (writeLock) {
            if (closing.get()) {
                return;
            }
            try {
                writer.write(line);
                writer.write('\n');
                writer.flush();
            } catch (IOException e) {
                log.warn("[{}] IPC 写入失败，通道可能已断开: {}", name, e.getMessage());
            }
        }
    }

    private void readLoop() {
        Throwable cause = null;
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                handleLine(line);
            }
        } catch (IOException e) {
            cause = e;
        } catch (RuntimeException e) {
            cause = e;
        } finally {
            boolean intentional = closing.getAndSet(true);
            if (intentional) {
                log.debug("[{}] IPC 通道已正常关闭", name);
            } else if (cause instanceof RuntimeException) {
                // 能逃出 handleLine 的 RuntimeException 说明是 handleLine 保护范围之外的 bug，
                // 只打 message 会丢掉栈，事后无法定位。这种情况必须带 throwable。
                log.error("[{}] IPC 读取循环异常终止", name, cause);
            } else {
                log.warn("[{}] IPC 通道断开: {}", name, cause == null ? "对端结束输出" : cause.getMessage());
            }
            listener.onClosed(intentional ? null : cause);
        }
    }

    private void handleLine(String line) {
        if (line.isBlank()) {
            return;
        }
        try {
            IpcMessage message = codec.decode(line);
            listener.onMessage(message);
        } catch (IpcProtocolException e) {
            long count = protocolViolations.incrementAndGet();
            // 大声报错：这通常意味着插件里有一句 print() 撕碎了协议
            log.error("[{}] IPC 协议违规（第 {} 次），已丢弃该行。原始内容: {}", name, count, e.getMessage());
        } catch (RuntimeException e) {
            log.error("[{}] IPC 消息处理异常，已丢弃", name, e);
        }
    }

    @Override
    public void close() {
        if (closing.getAndSet(true)) {
            return;
        }
        synchronized (writeLock) {
            try {
                writer.flush();
            } catch (IOException ignored) {
                // 关闭流程中忽略
            }
        }
        Thread thread = readerThread;
        if (thread != null) {
            thread.interrupt();
        }
    }
}
