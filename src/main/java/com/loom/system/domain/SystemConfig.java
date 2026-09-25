package com.loom.system.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@TableName("sys_config")
@Getter
@Setter
public class SystemConfig {

    @TableId private Long id;
    private String configKey;
    private String configValue;
    private String valueType;
    private String configGroup;
    private String name;
    private String description;
    private Integer builtin;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
