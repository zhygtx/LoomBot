package com.loombot.plugin.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@TableName("plugin_dependency")
@Getter
@Setter
public class PluginDependency {

    @TableId private Long id;

    private Long pluginVersionId;
    private String packageName;
    private String versionSpec;
    private String resolvedVersion;
    private String wheelSha256;
    private String sourceUrl;
    private String marker;
    private LocalDateTime createTime;
}
