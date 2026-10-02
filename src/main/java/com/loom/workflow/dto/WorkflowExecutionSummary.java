package com.loom.workflow.dto;

import java.time.LocalDateTime;

/**
 * 执行日志列表项：只带列表要用的字段，不含 detail_json。
 *
 * <p>detail_json 平均 1 到 2 KB、硬上限 16 KB，混进列表接口会让一页响应膨胀到几百 KB， 所以明细只在详情接口返回。
 *
 * <p>事件节点名和连接名都是**执行当时的快照**：插件改名、连接改名或删除之后，旧日志仍按当时的名字显示。
 */
public record WorkflowExecutionSummary(
        Long id,
        String executionId,
        Long workflowId,
        String workflowName,
        Integer definitionVersion,
        Long connectionId,
        String connectionType,
        String connectionName,
        String triggerType,
        String eventNodeKey,
        String eventNodeName,
        String eventSummary,
        String status,
        String errorCode,
        String errorMessage,
        Integer detailTruncated,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Long durationMs) {

    public static WorkflowExecutionSummary of(
            com.loom.workflow.domain.WorkflowExecution row, String workflowName) {
        return new WorkflowExecutionSummary(
                row.getId(),
                row.getExecutionId(),
                row.getWorkflowId(),
                workflowName,
                row.getDefinitionVersion(),
                row.getConnectionId(),
                row.getConnectionType(),
                row.getConnectionName(),
                row.getTriggerType(),
                row.getEventNodeKey(),
                row.getEventNodeName(),
                row.getEventSummary(),
                row.getStatus(),
                row.getErrorCode(),
                row.getErrorMessage(),
                row.getDetailTruncated(),
                row.getStartTime(),
                row.getEndTime(),
                row.getDurationMs());
    }
}
