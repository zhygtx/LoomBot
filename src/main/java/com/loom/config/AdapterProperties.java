package com.loom.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 协议适配器的启动配置。
 *
 * <p>注意这里**没有连接类型列表** —— 类型由适配器自己在 {@code hello} 里声明。 Java 只负责「把脚本跑起来」，不需要预先知道它会声明什么类型。 这与「新增平台不改
 * Java」的目标一致：加一个平台 = 往 {@code scripts} 里加一行。
 *
 * <pre>{@code
 * loom:
 *   adapter:
 *     python-command: python
 *     scripts:
 *       - plugins/fake-adapter/main.py
 * }</pre>
 *
 * @param pythonCommand Python 解释器命令。Windows 上通常是 {@code python}， 某些环境需要 {@code python3} 或绝对路径
 * @param scripts 适配器脚本路径（相对工作目录或绝对路径）
 * @param restartBackoffInitialMs 崩溃重启的初始退避间隔
 * @param restartBackoffMaxMs 退避封顶。无限重试但封顶，避免打爆 CPU
 */
@ConfigurationProperties(prefix = "loom.adapter")
public record AdapterProperties(
        String pythonCommand,
        List<String> scripts,
        Long restartBackoffInitialMs,
        Long restartBackoffMaxMs) {

    private static final long DEFAULT_INITIAL_BACKOFF_MS = 1_000L;
    private static final long DEFAULT_MAX_BACKOFF_MS = 60_000L;

    public AdapterProperties {
        pythonCommand = pythonCommand == null || pythonCommand.isBlank() ? "python" : pythonCommand;
        scripts = scripts == null ? List.of() : List.copyOf(scripts);
        restartBackoffInitialMs =
                restartBackoffInitialMs == null || restartBackoffInitialMs <= 0
                        ? DEFAULT_INITIAL_BACKOFF_MS
                        : restartBackoffInitialMs;
        restartBackoffMaxMs =
                restartBackoffMaxMs == null || restartBackoffMaxMs <= 0
                        ? DEFAULT_MAX_BACKOFF_MS
                        : restartBackoffMaxMs;
    }
}
