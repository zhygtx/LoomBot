package com.loom.plugin.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@TableName("plugin_repository")
@Getter
@Setter
public class PluginRepository {

    @TableId private Long id;

    private String repoKey;
    private String repoUrl;
    private String branch;
    private String localPath;
    private String lastCommitHash;
    private LocalDateTime lastPullTime;
    private LocalDateTime lastScanTime;
    private String lastError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
