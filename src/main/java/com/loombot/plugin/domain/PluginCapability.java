package com.loombot.plugin.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@TableName("plugin_capability")
@Getter
@Setter
public class PluginCapability {

    @TableId private Long id;

    private Long pluginVersionId;
    private String capability;
    private String kind;
    private String target;
    private String detailJson;
    private LocalDateTime createTime;
}
