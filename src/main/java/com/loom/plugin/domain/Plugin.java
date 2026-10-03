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

    /** index.json 里声明的命名空间（可选）；声明了就会拼进 pluginKey。 */
    private String namespace;

    private String pluginKey;
    private String name;
    private String description;
    private String homepage;
    private String author;
    private Integer sort;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
