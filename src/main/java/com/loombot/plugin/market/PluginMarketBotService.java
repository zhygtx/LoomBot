package com.loombot.plugin.market;

import com.loombot.config.AdapterProperties;
import com.loombot.system.service.SystemConfigService;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 插件库 PR 机器人的定时触发。
 *
 * <p>机器人本体是 Python（`system.plugin_market.bot`：要改写 AST、要调 AI，Java 干不了），这里只负责 按点叫它跑一轮 `--once`：
 *
 * <ul>
 *   <li>**非密钥配置**从 `sys_config` 读出来，序列化成一个 JSON 通过环境变量传进去——后台改完下一轮生效；
 *   <li>**密钥**（Gitee token、AI key）在服务器的 `python/secrets/plugin-market.json` 里，Java 不碰。
 * </ul>
 *
 * <p>为什么是"定时叫一次"而不是常驻进程：机器人只在有人提 PR 时才有活干，没必要一直占着一个进程；
 * 它自己的状态文件保证了幂等和"续做没做完的发布"。同理也不等它跑完——叫起来就返回，由守护线程 收日志、超时兜底、跑完再放锁。
 */
@Component
public class PluginMarketBotService {

    private static final Logger log = LoggerFactory.getLogger(PluginMarketBotService.class);

    /** 传给 Python 的环境变量名，值是一整份非密钥配置的 JSON。 */
    private static final String ENV_CONFIG = "LOOMBOT_PLUGIN_MARKET_CONFIG";

    private static final String MODULE = "system.plugin_market.bot";

    private final SystemConfigService systemConfig;
    private final AdapterProperties adapterProperties;
    private final ObjectMapper objectMapper;
    private final Duration timeout;

    /** 同一时刻只跑一轮；跑完（或超时杀掉）才放开。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Process process;

    public PluginMarketBotService(
            SystemConfigService systemConfig,
            AdapterProperties adapterProperties,
            ObjectMapper objectMapper,
            @Value("${loombot.plugin-market.timeout:10m}") Duration timeout) {
        this.systemConfig = systemConfig;
        this.adapterProperties = adapterProperties;
        this.objectMapper = objectMapper;
        this.timeout = timeout;
    }

    @Scheduled(
            fixedDelayString = "${loombot.plugin-market.interval:5m}",
            initialDelayString = "${loombot.plugin-market.initial-delay:2m}")
    public void tick() {
        if (!systemConfig.enabled(SystemConfigService.PLUGIN_MARKET_ENABLED)) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.debug("上一轮插件库机器人还在跑，跳过这一轮");
            return;
        }
        String config;
        try {
            config = buildConfig();
        } catch (RuntimeException e) {
            running.set(false);
            log.warn("插件库机器人配置读取失败，跳过: {}", e.getMessage());
            return;
        }
        try {
            launch(config);
        } catch (IOException e) {
            running.set(false);
            log.error("插件库机器人启动失败: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void shutdown() {
        Process current = process;
        if (current != null && current.isAlive()) {
            current.destroyForcibly();
        }
    }

    /** 把 `sys_config` 里那几条拼成机器人要的一份 JSON。缺的项留空，Python 侧有默认值。 */
    private String buildConfig() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("enabled", true);
        copySection(SystemConfigService.PLUGIN_MARKET_REPO, "repo", root);
        copySection(SystemConfigService.PLUGIN_MARKET_AI, "ai", root);
        copySection(SystemConfigService.PLUGIN_MARKET_GATES, "gates", root);
        return objectMapper.writeValueAsString(root);
    }

    private void copySection(String key, String field, ObjectNode root) {
        String raw = systemConfig.rawValue(key);
        if (raw == null || raw.isBlank()) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node != null && node.isObject()) {
                root.set(field, node);
            }
        } catch (RuntimeException e) {
            log.warn("系统配置 {} 不是合法 JSON，已忽略: {}", key, e.getMessage());
        }
    }

    private void launch(String config) throws IOException {
        ProcessBuilder builder =
                new ProcessBuilder(adapterProperties.pythonCommand(), "-m", MODULE, "--once");
        builder.directory(
                Path.of(adapterProperties.workingDirectory())
                        .toAbsolutePath()
                        .normalize()
                        .toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("PYTHONUNBUFFERED", "1");
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.environment().put("PYTHONUTF8", "1");
        builder.environment().put(ENV_CONFIG, config);
        Process started = builder.start();
        process = started;
        log.info("插件库机器人已启动: pid={}", started.pid());
        Thread watcher = new Thread(() -> watch(started), "plugin-market-bot");
        watcher.setDaemon(true);
        watcher.start();
        Thread killer = new Thread(() -> killAfterTimeout(started), "plugin-market-bot-timeout");
        killer.setDaemon(true);
        killer.start();
    }

    private void watch(Process started) {
        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[plugin-market] {}", line);
            }
            int code = started.waitFor();
            if (code != 0) {
                log.warn("插件库机器人退出码 {}", code);
            }
        } catch (IOException e) {
            log.debug("插件库机器人日志读取结束: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            running.set(false);
        }
    }

    private void killAfterTimeout(Process started) {
        try {
            if (!started.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
                log.warn("插件库机器人超过 {} 还没跑完，强制结束", timeout);
                started.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
