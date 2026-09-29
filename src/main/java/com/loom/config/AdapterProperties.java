package com.loom.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 协议适配器的启动配置。
 *
 * <p>注意这里没有连接类型列表。类型由插件仓库中的版本清单和连接 schema 声明；本配置只保存 Python 解释器、进程托管与崩溃退避参数。
 *
 * <pre>{@code
 * loom:
 *   adapter:
 *     python-command: python
 * }</pre>
 *
 * @param pythonCommand Python 解释器命令。Windows 上通常是 {@code python}， 某些环境需要 {@code python3} 或绝对路径
 * @param taskTtl Adapter 提交给 Worker 的待消费任务有效期
 * @param controlBaseUrl Python Adapter Host internal HTTP 地址
 * @param controlToken Java 调用 Adapter 控制 API 的令牌
 * @param publicWsBaseUrl Python Adapter 反向 WS 对外地址
 * @param requestTimeout 控制 API 请求超时
 * @param autoStart Java 是否直接启动本地 Adapter Host
 * @param workingDirectory 启动 Adapter Host 的工作目录
 * @param hostModule Python 模块名
 * @param pluginRoot Adapter Host 使用的插件仓库目录
 * @param redisUrl Adapter Host 使用的 Redis 地址
 * @param restartBackoffInitialMs 崩溃重启初始退避
 * @param restartBackoffMaxMs 崩溃重启退避上限
 * @param startupTimeout 启动后等待 health 通过的超时
 */
@ConfigurationProperties(prefix = "loom.adapter")
public record AdapterProperties(
        String pythonCommand,
        Duration taskTtl,
        String controlBaseUrl,
        String controlToken,
        String publicWsBaseUrl,
        Duration requestTimeout,
        Boolean autoStart,
        String workingDirectory,
        String hostModule,
        String pluginRoot,
        String redisUrl,
        Long restartBackoffInitialMs,
        Long restartBackoffMaxMs,
        Duration startupTimeout) {

    public AdapterProperties {
        pythonCommand = pythonCommand == null || pythonCommand.isBlank() ? "python" : pythonCommand;
        taskTtl =
                taskTtl == null || taskTtl.isZero() || taskTtl.isNegative()
                        ? Duration.ofMinutes(5)
                        : taskTtl;
        controlBaseUrl =
                controlBaseUrl == null || controlBaseUrl.isBlank()
                        ? "http://127.0.0.1:9100"
                        : stripTrailingSlash(controlBaseUrl.strip());
        controlToken =
                controlToken == null || controlToken.isBlank()
                        ? "loom-dev-adapter-token"
                        : controlToken.strip();
        publicWsBaseUrl =
                publicWsBaseUrl == null || publicWsBaseUrl.isBlank()
                        ? "ws://127.0.0.1:9000"
                        : stripTrailingSlash(publicWsBaseUrl.strip());
        requestTimeout =
                requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()
                        ? Duration.ofSeconds(10)
                        : requestTimeout;
        autoStart = autoStart == null || autoStart;
        workingDirectory =
                workingDirectory == null || workingDirectory.isBlank()
                        ? "python"
                        : workingDirectory.strip();
        hostModule =
                hostModule == null || hostModule.isBlank()
                        ? "system.adapter.main"
                        : hostModule.strip();
        pluginRoot = pluginRoot == null || pluginRoot.isBlank() ? "plugins" : pluginRoot.strip();
        redisUrl =
                redisUrl == null || redisUrl.isBlank()
                        ? "redis://localhost:6379/0"
                        : redisUrl.strip();
        restartBackoffInitialMs =
                restartBackoffInitialMs == null || restartBackoffInitialMs <= 0
                        ? 1_000L
                        : restartBackoffInitialMs;
        restartBackoffMaxMs =
                restartBackoffMaxMs == null || restartBackoffMaxMs <= 0
                        ? 60_000L
                        : restartBackoffMaxMs;
        startupTimeout =
                startupTimeout == null || startupTimeout.isZero() || startupTimeout.isNegative()
                        ? Duration.ofSeconds(20)
                        : startupTimeout;
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
