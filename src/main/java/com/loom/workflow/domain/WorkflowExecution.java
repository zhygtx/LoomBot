package com.loom.workflow.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 工作流执行主记录：节点明细进 detail_json，不建节点明细表。 */
@TableName("workflow_execution")
@Getter
@Setter
public class WorkflowExecution {

    @TableId private Long id;

    private String executionId;
    private Long workflowId;
    private Integer definitionVersion;
    private Long connectionId;
    private Long adapterPluginVersionId;
    private String connectionType;
    private String nodeKey;
    private String groupId;
    private String eventSummary;
    private String status;
    private String errorCode;
    private String errorMessage;
    private String detailJson;
    private Integer detailTruncated;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long durationMs;
    private LocalDate createdDate;
}
