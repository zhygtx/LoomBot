package com.loom.runtime.process;

import com.loom.runtime.ipc.IpcChannel;
import com.loom.runtime.ipc.IpcCodec;
import com.loom.runtime.ipc.IpcListener;
import com.loom.runtime.ipc.IpcMessage;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Python 子进程宿主。
 *
 * <h2>职责</h2>
 *
 * <ul>
 *   <li>启动子进程，把 stdout 接到 {@link IpcChannel}，stderr 接到日志
 *   <li>进程退出时回调（供上层做退避重启）
 *   <li>优雅关闭：先发 {@code shutdown} 消息，超时才强杀
 * </ul>
 *
 * <h2>两个容易出错的细节</h2>
 *
 * <p><b>① stderr 必须持续排空。</b>否则管道缓冲区写满后子进程会阻塞在写 stderr 上， 表现为「插件莫名卡死」。
 *
 * <p><b>② 必须设 {@code PYTHONUNBUFFERED=1}。</b>否则 Python 的 stdout 是块缓冲的， 消息会攒一批才发出来甚至永远不发 —— 表现为「Java
 * 收不到插件的消息」， 而且极难联想到是缓冲问题。这是 stdio IPC 最经典的坑。
 *
 * <h2>孤儿进程</h2>
 *
 * <p>JVM 被强杀时本类的 close 不会执行。真正的防护是子进程侧： <b>读 stdin 返回 EOF 就自杀</b>。JVM 死亡时管道自动关闭，子进程立刻能感知。
 */
public final class PythonProcessHost implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(PythonProcessHost.class);

    /** 优雅退出等待时长。 */
    private static final long GRACEFUL_TIMEOUT_MS = 3000L;

    /** 强杀后等待时长。 */
    private static final long FORCE_TIMEOUT_MS = 2000L;

    private final String name;
    private final Process process;
    private final IpcChannel channel;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private PythonProcessHost(String name, Process process, IpcChannel channel) {
        this.name = name;
        this.process = process;
        this.channel = channel;
    }

    public static PythonProcessHost start(
            PythonProcessSpec spec, IpcCodec codec, IpcListener listener, IntConsumer onExit) {

        ProcessBuilder builder = new ProcessBuilder(spec.command());
        if (spec.workingDir() != null) {
            builder.directory(spec.workingDir().toFile());
        }
        // 关闭缓冲，否则消息会攒批甚至不发（stdio IPC 最经典的坑）
        builder.environment().put("PYTHONUNBUFFERED", "1");
        builder.environment().putAll(spec.env());
        builder.redirectErrorStream(false);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new PythonProcessStartException("启动 Python 进程失败: " + spec.command(), e);
        }

        IpcChannel channel =
                new IpcChannel(
                        spec.name(), codec, process.getInputStream(), process.getOutputStream());
        channel.setListener(listener);

        PythonProcessHost host = new PythonProcessHost(spec.name(), process, channel);
        host.drainStderr(spec.name());
        host.watchExit(spec.name(), onExit);
        log.info("[{}] Python 进程已启动，pid={}，命令={}", spec.name(), process.pid(), spec.command());
        return host;
    }

    /**
     * 开始读取 stdout。
     *
     * <p><b>刻意不由 {@link #start} 自动调用</b>：适配器启动后会**立即**上报 {@code hello}， 如果读线程先跑起来而监听者还没设置，这条 hello
     * 会被丢弃，适配器就永远不可用了。 所以调用方必须先 {@code channel.setListener(...)}，再调本方法。
     */
    public void startReading() {
        channel.start();
    }

    public String name() {
        return name;
    }

    public IpcChannel channel() {
        return channel;
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public long pid() {
        return process.pid();
    }

    /**
     * 优雅关闭：发 {@code shutdown} → 等待 → destroy → 等待 → 强杀。
     *
     * <p>幂等，可重复调用。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (process.isAlive()) {
            channel.send(IpcMessage.of("shutdown", null));
            if (!awaitExit(GRACEFUL_TIMEOUT_MS)) {
                log.warn("[{}] 未在 {}ms 内优雅退出，强制终止", name, GRACEFUL_TIMEOUT_MS);
                process.destroy();
                if (!awaitExit(FORCE_TIMEOUT_MS)) {
                    log.warn("[{}] 仍未退出，强杀", name);
                    process.destroyForcibly();
                    awaitExit(FORCE_TIMEOUT_MS);
                }
            }
        }
        channel.close();
        log.info("[{}] Python 进程已停止，exitCode={}", name, process.exitValue());
    }

    private boolean awaitExit(long millis) {
        try {
            return process.waitFor(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 持续排空 stderr 并转成日志 —— 不排空会让子进程在写 stderr 时阻塞。 */
    private void drainStderr(String name) {
        Thread thread =
                new Thread(
                        () -> {
                            try (BufferedReader reader =
                                    new BufferedReader(
                                            new InputStreamReader(
                                                    process.getErrorStream(),
                                                    StandardCharsets.UTF_8))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    log.info("[{}][stderr] {}", name, line);
                                }
                            } catch (IOException e) {
                                log.debug("[{}] stderr 读取结束: {}", name, e.getMessage());
                            }
                        },
                        "py-stderr-" + name);
        thread.setDaemon(true);
        thread.start();
    }

    /** 监听进程退出，供上层做退避重启。 */
    private void watchExit(String name, IntConsumer onExit) {
        Thread thread =
                new Thread(
                        () -> {
                            int exitCode;
                            try {
                                exitCode = process.waitFor();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                            // 主动关闭时不回调，避免触发「崩溃重启」
                            if (closed.get()) {
                                return;
                            }
                            log.warn("[{}] Python 进程意外退出，exitCode={}", name, exitCode);
                            if (onExit != null) {
                                onExit.accept(exitCode);
                            }
                        },
                        "py-watch-" + name);
        thread.setDaemon(true);
        thread.start();
    }
}
