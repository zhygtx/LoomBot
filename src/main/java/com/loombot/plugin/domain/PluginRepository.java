package com.loombot.plugin.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 插件库的同步状态。
 *
 * <p>声明（仓库地址、分支、用哪个子扫描器）在插件库文件夹的 `repo.json` 里，不在这张表里—— 文件夹才是"有哪些库"的真相，表只记"上次同步到哪了"。
 */
@TableName("plugin_repository")
@Getter
@Setter
public class PluginRepository {

    @TableId private Long id;

    private String repoKey;

    /** 这次同步时工作副本在哪；纯本地库也记，出问题好定位。 */
    private String localPath;

    private String lastCommitHash;
    private LocalDateTime lastPullTime;
    private LocalDateTime lastScanTime;
    private String lastError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
