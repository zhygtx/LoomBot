package com.loom.workflow.domain;

import com.baomidou.mybatisplus.annotation.TableField;
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

    /**
     * 列表页角标：这条工作流有没有节点失效提醒。
     *
     * <p>不是表字段，由查询时补上。放实体上而不是另建 DTO，是因为列表接口本来就返回这个实体， 多一个字段比多一层映射划算。
     */
    @TableField(exist = false)
    private Boolean hasAlert;
}
