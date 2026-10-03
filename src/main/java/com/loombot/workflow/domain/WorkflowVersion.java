package com.loombot.workflow.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 不可变工作流定义版本。执行记录绑定这里的版本号。 */
@TableName("workflow_version")
@Getter
@Setter
public class WorkflowVersion {

    @TableId private Long id;

    private Long workflowId;
    private Integer versionNo;

    /** DAG 定义 JSON 文本。 */
    private String definition;

    private String remark;
    private Long createBy;
    private LocalDateTime createTime;
}
