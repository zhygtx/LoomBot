package com.loom.runtime.process;

import static org.assertj.core.api.Assertions.assertThat;

import com.loom.runtime.ipc.IpcCodec;
import com.loom.runtime.ipc.IpcListener;
import com.loom.runtime.ipc.IpcMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link PythonProcessHost} 关闭语义的测试。
 *
 * <p>重点是 D38 修的那个 bug：**「等待被中断」不能与「等待超时」混为一谈**。
 *
 * <p>修复前，`awaitExit` 在 `InterruptedException` 时返回 `false`，而 `close()` 把 `false` 读作「没优雅退出」，于是升级为
 * `destroy()` → `destroyForcibly()`。 后果是：应用关闭流程里（此时中断标志常常已置位）3 秒优雅窗口一秒都没走完就强杀 Python，
 * 同时打印一条从未发生的超时告警。
 *
 * <p>这里用一个「收到 shutdown 后需要一小段时间才退出」的假进程来放大这个窗口： 修复后应当**等到子进程自己退出**，而不是立刻强杀。
 *
 * <p>标记为 integration：它要真起 Python 进程。
 */
@Tag("integration")
@DisplayName("Python 进程宿主")
class PythonProcessHostTest {

    /**
     * 收到 shutdown 后先睡一会儿再退出，用来验证「等满优雅窗口」而不是「立刻强杀」。
     *
     * <p>看门狗是**兜底自杀**：中断用例会故意留下一个存活的子进程， 靠它保证测试结束后不会产生孤儿（也就不会卡住临时目录的删除）。
     */
    private static final String SLOW_EXIT_SCRIPT =
            """
            import sys, time, threading

            def watchdog():
                time.sleep(4)
                sys.exit(9)

            threading.Thread(target=watchdog, daemon=True).start()

            for line in sys.stdin:
                line = line.strip()
                if not line:
                    continue
                if '"shutdown"' in line:
                    # 模拟「需要时间做清理」的适配器
                    time.sleep(1.0)
                    sys.exit(0)
            """;

    private static final IpcListener NOOP =
            new IpcListener() {
                @Override
                public void onMessage(IpcMessage message) {
                    // 本测试不关心消息
                }

                @Override
                public void onClosed(Throwable cause) {
                    // 本测试不关心关闭回调
                }
            };

    private PythonProcessHost startHost(Path dir) throws Exception {
        Path script = dir.resolve("slow.py");
        Files.writeString(script, SLOW_EXIT_SCRIPT);
        PythonProcessSpec spec =
                new PythonProcessSpec(
                        "slow-adapter", List.of("python", script.toString()), dir, Map.of());
        return PythonProcessHost.start(spec, new IpcCodec(new ObjectMapper()), NOOP, null);
    }

    @Test
    @DisplayName("正常关闭：发 shutdown 后等到进程自己退出，不升级为强杀")
    void shouldWaitForGracefulExit(@TempDir Path dir) throws Exception {
        PythonProcessHost host = startHost(dir);
        // 进程刚起来时可能还没进 stdin 循环，稍等一下再关
        Thread.sleep(600);

        host.close();

        assertThat(host.isAlive()).isFalse();
    }

    @Test
    @DisplayName("中断标志已置位时：不强制终止进程，且 close() 本身不抛异常（D38 回归）")
    void shouldNotForceKillWhenInterrupted(@TempDir Path dir) throws Exception {
        PythonProcessHost host = startHost(dir);
        Thread.sleep(600);

        // 关键：模拟「关闭流程跑在一个已被中断的线程上」——
        // 这正是 Spring/JVM 关闭和上游取消时的真实形态。
        Thread.currentThread().interrupt();
        try {
            // 修复前：awaitExit 返回 false 被读作「超时」，于是立刻 destroy + destroyForcibly，
            //         脚本被强杀，退出码不是 0。
            // 修复后：中断被识别为「状态未知」，不做强制终止，脚本走完清理后 exit(0)。
            host.close();

            // 中断标志必须被保留，交给上层处理
            assertThat(Thread.currentThread().isInterrupted()).as("close() 不应吞掉中断标志").isTrue();
        } finally {
            // 清掉标志，否则后续所有 waitFor 都会立刻抛 InterruptedException
            Thread.interrupted();
        }

        // 等脚本自然退出（它自己会 exit(0)）。必须「等待」而非断言立刻退出：
        // 被中断时不保证进程已结束，这正是「状态未知」的含义。
        long deadline = System.currentTimeMillis() + 10_000;
        while (host.isAlive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }

        // 判别性断言 —— 这一条才能真正区分「优雅等待」与「立刻强杀」：
        // 优雅退出码为 0；被强杀则不是 0。
        assertThat(host.exitCode()).as("进程应优雅退出（exitCode=0），而不是被强杀").isZero();
    }

    @Test
    @DisplayName("close 幂等：重复调用不抛异常")
    void shouldBeIdempotent(@TempDir Path dir) throws Exception {
        PythonProcessHost host = startHost(dir);
        Thread.sleep(600);

        host.close();
        host.close();
        host.close();

        assertThat(host.isAlive()).isFalse();
    }

    @Test
    @DisplayName("进程已退出后再 close 也不抛异常")
    void shouldTolerateAlreadyDeadProcess(@TempDir Path dir) throws Exception {
        PythonProcessHost host = startHost(dir);
        host.close();
        Thread.sleep(300);

        host.close();

        assertThat(host.isAlive()).isFalse();
    }
}
