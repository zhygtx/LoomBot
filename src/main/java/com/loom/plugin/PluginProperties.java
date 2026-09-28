package com.loom.plugin;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 插件仓库同步配置。
 *
 * @param repositoryKey 仓库稳定标识
 * @param repositoryUrl 公共 Git 仓库地址；为空时只扫描本地目录
 * @param branch 分支
 * @param localPath 本地仓库目录；相对路径按 Java 进程工作目录解析
 * @param syncOnStart 启动时是否同步
 * @param autoSync 是否周期性自动扫描仓库变化
 * @param syncInterval 自动扫描间隔
 * @param commandTimeoutSeconds git 命令超时
 * @param dependencyInstallTimeoutSeconds 单版本依赖安装超时
 */
@ConfigurationProperties(prefix = "loom.plugin")
public record PluginProperties(
        String repositoryKey,
        String repositoryUrl,
        String branch,
        String localPath,
        Boolean syncOnStart,
        Boolean autoSync,
        Duration syncInterval,
        Integer commandTimeoutSeconds,
        Integer dependencyInstallTimeoutSeconds) {

    public PluginProperties {
        repositoryKey =
                repositoryKey == null || repositoryKey.isBlank()
                        ? "loom-public-plugins"
                        : repositoryKey.strip();
        repositoryUrl = repositoryUrl == null ? "" : repositoryUrl.strip();
        branch = branch == null || branch.isBlank() ? "main" : branch.strip();
        localPath = localPath == null || localPath.isBlank() ? "plugins" : localPath.strip();
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
