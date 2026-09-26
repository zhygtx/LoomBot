package com.loom.system.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@TableName("sys_menu")
@Getter
@Setter
public class Menu {

    @TableId private Long id;

    private Long parentId;
    private String type;
    private String name;
    private String routeName;
    private String path;
    private String componentKey;
    private String iconKey;
    private String redirect;
    private Integer sort;
    private Integer visible;
    private Integer keepAlive;
    private Integer status;
    private String remark;

    @TableLogic private Integer deleted;

    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
}
