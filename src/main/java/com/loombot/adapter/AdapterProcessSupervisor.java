package com.loombot.adapter;

import com.loombot.config.AdapterProperties;
import com.loombot.plugin.storage.PluginStorageProperties;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 本地 Adapter Host 进程的启动、健康等待、崩溃重启和关闭。 */
@Component
public class AdapterProcessSupervisor {

    private static final Logger log = LoggerFactory.getLogger(AdapterProcessSupervisor.class);

    /** 匹配适配器主机自己打的 `2026-10-02 20:49:02,639 INFO name message` 前缀。 */
    private static final Pattern HOST_LOG_LEVEL =
            Pattern.compile(
                    "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}[,.]\\d{3} (TRACE|DEBUG|INFO|WARN|WARNING|ERROR|CRITICAL) ");

    private final AdapterProperties properties;
    private final AdapterControlClient client;
    private final PluginStorageProperties pluginStorageProperties;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean restartScheduled = new AtomicBoolean();

    private volatile Process process;
    private volatile boolean shuttingDown;
    private volatile int restartAttempt;

    public AdapterProcessSupervisor(
            AdapterProperties properties,
            AdapterControlClient client,
            PluginStorageProperties pluginStorageProperties) {
        this.properties = properties;
        this.client = client;
        this.pluginStorageProperties = pluginStorageProperties;
        this.scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "adapter-process-supervisor");
                            thread.setDaemon(true);
                            return thread;
                        });
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    public void startOnApplicationReady() {
        ensureStarted();
    }

    public synchronized boolean ensureStarted() {
        if (client.health()) {
            log.info("Adapter Host 已在线，Java 不再启动本地进程");
            return true;
        }
        if (!properties.autoStart()) {
            log.info("Adapter auto-start=false，仅连接外部 Adapter Host");
            return false;
        }
        if (process != null && process.isAlive()) {
            return waitForHealthy(properties.startupTimeout());
        }
        return launchAndWait();
    }

    private boolean launchAndWait() {
        Path workingDirectory = Path.of(properties.workingDirectory()).toAbsolutePath().normalize();
        ProcessBuilder builder =
                new ProcessBuilder(properties.pythonCommand(), "-m", properties.hostModule())
                        .directory(workingDirectory.toFile())
                        .redirectErrorStream(true);
        applyEnvironment(builder.environment());
        try {
            process = builder.start();
            log.info(
                    "Adapter Host 已启动: pid={}, cwd={}, command={} -m {}",
                    process.pid(),
                    workingDirectory,
                    properties.pythonCommand(),
                    properties.hostModule());
            pumpLogs(process);
            watchExit(process);
            boolean healthy = waitForHealthy(properties.startupTimeout());
            if (!healthy) {
                log.error("Adapter Host 未在 {} 内通过 health", properties.startupTimeout());
                stopProcess();
                scheduleRestart();
            } else {
                restartAttempt = 0;
            }
            return healthy;
        } catch (IOException e) {
            log.error("Adapter Host 启动失败: {}", e.getMessage(), e);
            scheduleRestart();
            return false;
        }
    }

    private void applyEnvironment(Map<String, String> environment) {
        URI control = URI.create(properties.controlBaseUrl());
        environment.put("PYTHONUNBUFFERED", "1");
        environment.put("PYTHONIOENCODING", "utf-8");
        environment.put("PYTHONUTF8", "1");
        environment.put("ADAPTER_HOST", control.getHost());
        environment.put("ADAPTER_PORT", Integer.toString(port(control, 9100)));
        environment.put("ADAPTER_CONTROL_TOKEN", properties.controlToken());
        environment.put("ADAPTER_PLUGIN_ROOT", properties.pluginRoot());
        environment.put("ADAPTER_REDIS_URL", properties.redisUrl());
        // 适配器插件用 conn.storage 时同样只回调 Java，不直接连数据库或对象存储。
        environment.put("PLUGIN_STORAGE_BASE_URL", pluginStorageProperties.internalBaseUrl());
        environment.put("PLUGIN_STORAGE_CONTROL_TOKEN", pluginStorageProperties.controlToken());
        environment.put("ADAPTER_WS_HOST", control.getHost());
        environment.put(
                "ADAPTER_WS_PORT",
                Integer.toString(port(URI.create(properties.publicWsBaseUrl()), 9000)));
    }

    private boolean waitForHealthy(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (client.health()) {
                log.info("Adapter Host health 通过");
                return true;
            }
            Process current = process;
            if (current != null && !current.isAlive()) {
                return false;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void pumpLogs(Process current) {
        Thread thread =
                new Thread(
                        () -> {
                            try (BufferedReader reader =
                                    new BufferedReader(
                                            new InputStreamReader(
                                                    current.getInputStream(),
                                                    StandardCharsets.UTF_8))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    logHostLine(line);
                                }
                            } catch (IOException e) {
                                log.debug("[adapter-host] 日志读取结束: {}", e.getMessage());
                            }
                        },
                        "adapter-host-log");
        thread.setDaemon(true);
        thread.start();
    }

    /** 按适配器主机自己报的级别转发，避免把 WARN/ERROR 一律降级成 INFO。 */
    private void logHostLine(String line) {
        Matcher matcher = HOST_LOG_LEVEL.matcher(line);
        String level = matcher.find() ? matcher.group(1) : "INFO";
        switch (level) {
            case "TRACE", "DEBUG" -> log.debug("[adapter-host] {}", line);
            case "WARN", "WARNING" -> log.warn("[adapter-host] {}", line);
            case "ERROR", "CRITICAL" -> log.error("[adapter-host] {}", line);
            default -> log.info("[adapter-host] {}", line);
        }
    }

    private void watchExit(Process current) {
        Thread thread =
                new Thread(
                        () -> {
                            try {
                                int exitCode = current.waitFor();
                                if (!shuttingDown && process == current) {
                                    log.warn("Adapter Host 已退出: exitCode={}", exitCode);
                                    scheduleRestart();
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        },
                        "adapter-host-watch");
        thread.setDaemon(true);
        thread.start();
    }

    private void scheduleRestart() {
        if (shuttingDown || !restartScheduled.compareAndSet(false, true)) {
            return;
        }
        restartAttempt++;
        long delay =
                backoff(
                        restartAttempt,
                        properties.restartBackoffInitialMs(),
                        properties.restartBackoffMaxMs());
        log.warn("Adapter Host 将在 {}ms 后重启（第 {} 次）", delay, restartAttempt);
        scheduler.schedule(
                () -> {
                    restartScheduled.set(false);
                    if (!shuttingDown) {
                        ensureStarted();
                    }
                },
                delay,
                TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public synchronized void shutdown() {
        shuttingDown = true;
        scheduler.shutdownNow();
        stopProcess();
    }

    private synchronized void stopProcess() {
        Process current = process;
        process = null;
        if (current == null || !current.isAlive()) {
            return;
        }
        current.destroy();
        try {
            if (!current.waitFor(3, TimeUnit.SECONDS)) {
                current.destroyForcibly();
                current.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        }
    }

    private static int port(URI uri, int fallback) {
        return uri.getPort() > 0 ? uri.getPort() : fallback;
    }

    private static long backoff(int attempt, long initial, long max) {
        int exponent = Math.min(Math.max(attempt - 1, 0), 20);
        long delay = initial * (1L << exponent);
        return Math.min(delay <= 0 ? max : delay, max);
    }
}
