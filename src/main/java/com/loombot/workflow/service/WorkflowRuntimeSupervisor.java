package com.loombot.workflow.service;

import com.loombot.config.AdapterProperties;
import com.loombot.plugin.storage.PluginStorageProperties;
import com.loombot.workflow.WorkflowRuntimeProperties;
import com.loombot.workflow.WorkflowWorkerProperties;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 工作流运行时进程托管。
 *
 * <p>与适配器监管器分开：工作流崩溃不影响收消息，适配器崩溃也不影响正在跑的工作流。
 */
@Component
public class WorkflowRuntimeSupervisor {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRuntimeSupervisor.class);
    private static final long RESTART_DELAY_MS = 3_000L;

    private final AdapterProperties adapterProperties;
    private final WorkflowWorkerProperties workerProperties;
    private final WorkflowRuntimeProperties runtimeProperties;
    private final PluginStorageProperties pluginStorageProperties;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private volatile Process process;

    public WorkflowRuntimeSupervisor(
            AdapterProperties adapterProperties,
            WorkflowWorkerProperties workerProperties,
            WorkflowRuntimeProperties runtimeProperties,
            PluginStorageProperties pluginStorageProperties) {
        this.adapterProperties = adapterProperties;
        this.workerProperties = workerProperties;
        this.runtimeProperties = runtimeProperties;
        this.pluginStorageProperties = pluginStorageProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!workerProperties.autoStart()) {
            log.info("工作流运行时 auto-start=false，仅连接外部运行时");
            return;
        }
        try {
            launch();
        } catch (IOException e) {
            log.error("工作流运行时启动失败: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public synchronized void shutdown() {
        shuttingDown.set(true);
        Process current = process;
        if (current != null && current.isAlive()) {
            current.destroy();
            try {
                if (!current.waitFor(5, TimeUnit.SECONDS)) {
                    current.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private synchronized void launch() throws IOException {
        ProcessBuilder builder =
                new ProcessBuilder(
                        adapterProperties.pythonCommand(), "-m", workerProperties.module());
        builder.directory(
                Path.of(workerProperties.workingDirectory()).toAbsolutePath().normalize().toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("PYTHONUNBUFFERED", "1");
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.environment().put("PYTHONUTF8", "1");
        // 大内容落盘目录：运行时按这个绝对路径写，Java 读同一目录提供下载
        builder.environment()
                .put("WORKFLOW_ARTIFACT_DIR", runtimeProperties.artifactsRoot().toString());
        // 插件持久化：运行时不直连 MySQL/Redis/对象存储，只回调 Java 的内部接口。
        // 节点进程从这里继承环境变量，所以插件侧不需要额外配置。
        builder.environment()
                .put("PLUGIN_STORAGE_BASE_URL", pluginStorageProperties.internalBaseUrl());
        builder.environment()
                .put("PLUGIN_STORAGE_CONTROL_TOKEN", pluginStorageProperties.controlToken());
        URI testEndpoint = URI.create(workerProperties.testBaseUrl());
        if (testEndpoint.getHost() != null) {
            builder.environment().put("WORKFLOW_TEST_HOST", testEndpoint.getHost());
        }
        if (testEndpoint.getPort() > 0) {
            builder.environment()
                    .put("WORKFLOW_TEST_PORT", Integer.toString(testEndpoint.getPort()));
        }
        process = builder.start();
        log.info("工作流运行时已启动: pid={} module={}", process.pid(), workerProperties.module());
        Thread reader = new Thread(this::pump, "workflow-worker-log");
        reader.setDaemon(true);
        reader.start();
        Thread watcher = new Thread(this::watch, "workflow-worker-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    private void pump() {
        Process current = process;
        if (current == null) {
            return;
        }
        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(current.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[workflow-worker] {}", line);
            }
        } catch (IOException e) {
            log.debug("工作流运行时日志读取结束: {}", e.getMessage());
        }
    }

    private void watch() {
        Process current = process;
        if (current == null) {
            return;
        }
        try {
            int code = current.waitFor();
            if (shuttingDown.get()) {
                return;
            }
            log.warn("工作流运行时已退出: exitCode={}，{}ms 后重启", code, RESTART_DELAY_MS);
            Thread.sleep(RESTART_DELAY_MS);
            if (!shuttingDown.get()) {
                launch();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.error("工作流运行时重启失败: {}", e.getMessage(), e);
        }
    }
}
