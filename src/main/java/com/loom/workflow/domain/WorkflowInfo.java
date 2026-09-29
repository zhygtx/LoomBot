package com.loom.workflow.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 工作流稳定身份：名称、启用状态与当前版本。 */
@TableName("workflow_info")
@Getter
@Setter
public class WorkflowInfo {

    @TableId private Long id;

    private String name;
    private String description;
    private Integer enabled;
    private Long currentVersionId;
    private Long ownerUserId;
    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
}
