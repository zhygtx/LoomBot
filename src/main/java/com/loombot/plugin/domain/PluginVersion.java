package com.loombot.plugin.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@TableName("plugin_version")
@Getter
@Setter
public class PluginVersion {

    @TableId private Long id;

    private Long pluginId;
    private String version;
    private String sourceCommitHash;
    private String manifestJson;
    private String manifestSha256;
    private String artifactSha256;
    private String installPath;
    private String entryPoint;
    private String pythonPath;
    private String runtimeKey;
    private LocalDateTime publishedTime;
    private LocalDateTime syncedTime;
    private LocalDateTime createTime;
}
