package com.loom.plugin.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@TableName("plugin_node")
@Getter
@Setter
public class PluginNode {

    @TableId private Long id;

    private Long pluginVersionId;
    private String nodeKey;
    private String nodeType;
    private String name;
    private String description;
    private String inputSchema;
    private String outputSchema;
    private String sourceRef;
    private Integer sort;
    private LocalDateTime createTime;
}
