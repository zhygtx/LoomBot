package com.loom.plugin;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 插件同步配置。
 *
 * <p>只配"插件库放在哪"和同步节奏：**每个库自己的声明（仓库地址、分支、子扫描器）在库文件夹的 `repo.json` 里**。所以加一个插件库 = 往 plugins
 * 根目录放一个文件夹，不用改配置、也不用重启。
 *
 * @param pluginsRoot 插件库根目录；下面的每个子目录是一个插件库
 * @param syncOnStart 启动时是否同步
 * @param autoSync 是否周期性自动扫描
 * @param syncInterval 自动扫描间隔
 * @param commandTimeoutSeconds git 命令超时
 * @param dependencyInstallTimeoutSeconds 单版本依赖安装超时
 */
@ConfigurationProperties(prefix = "loom.plugin")
public record PluginProperties(
        String pluginsRoot,
        Boolean syncOnStart,
        Boolean autoSync,
        Duration syncInterval,
        Integer commandTimeoutSeconds,
        Integer dependencyInstallTimeoutSeconds) {

    public PluginProperties {
        pluginsRoot =
                pluginsRoot == null || pluginsRoot.isBlank()
                        ? "python/plugins"
                        : pluginsRoot.strip();
        syncOnStart = syncOnStart == null || syncOnStart;
        autoSync = autoSync == null || autoSync;
        syncInterval =
                syncInterval == null || syncInterval.isZero() || syncInterval.isNegative()
                        ? Duration.ofSeconds(30)
                        : syncInterval;
        commandTimeoutSeconds =
                commandTimeoutSeconds == null || commandTimeoutSeconds <= 0
                        ? 60
                        : commandTimeoutSeconds;
        dependencyInstallTimeoutSeconds =
                dependencyInstallTimeoutSeconds == null || dependencyInstallTimeoutSeconds <= 0
                        ? 600
                        : dependencyInstallTimeoutSeconds;
    }
}
