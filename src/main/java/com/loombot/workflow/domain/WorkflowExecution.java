package com.loombot.workflow.domain;

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
    private Long ownerUserId;
    private Integer definitionVersion;
    private Long connectionId;
    private Long adapterPluginVersionId;
    private String connectionType;

    /** 事件节点绑定连接的名称快照。 */
    private String connectionName;

    /** 事件节点键。 */
    private String eventNodeKey;

    /** 事件节点的中文展示名快照。 */
    private String eventNodeName;

    /** EVENT / SCHEDULE / TEST。 */
    private String triggerType;

    /** 事件节点输出的摘要（截断）。 */
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
