package com.loom.plugin.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@TableName("plugin")
@Getter
@Setter
public class Plugin {

    @TableId private Long id;

    private Long repositoryId;
    private String pluginKey;
    private String name;
    private String description;
    private String homepage;
    private String author;
    private Integer sort;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
