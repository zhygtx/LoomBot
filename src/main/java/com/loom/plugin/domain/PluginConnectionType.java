package com.loom.plugin.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@TableName("plugin_connection_type")
@Getter
@Setter
public class PluginConnectionType {

    @TableId private Long id;

    private Long pluginVersionId;
    private String connectionType;
    private String entryPoint;
    private String displayName;
    private String direction;
    private String protocolVersion;
    private String schemaVersion;
    private String configSchema;
    private String configSchemaSha256;
    private Integer sort;
    private LocalDateTime createTime;
}
