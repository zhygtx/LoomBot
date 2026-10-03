package com.loombot.plugin.dto;

import java.time.LocalDateTime;

/**
 * 一个插件库在管理页面上的样子。
 *
 * <p>{@code valid=false} 表示文件夹在、但 `repo.json` 读不出来（缺 key、JSON 坏了等），
 * 这种库会被同步器跳过，所以必须让它可见，不能让站长以为"配好了"。
 */
public record PluginRepoResponse(
        String key,
        String folder,
        String url,
        String branch,
        String scanner,
        boolean valid,
        String error,
        String localPath,
        String lastCommitHash,
        LocalDateTime lastPullTime,
        LocalDateTime lastScanTime,
        String lastError) {}
