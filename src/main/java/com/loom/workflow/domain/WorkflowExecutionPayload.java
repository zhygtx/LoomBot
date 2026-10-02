package com.loom.workflow.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * 执行日志里的大内容。
 *
 * <p>节点输入输出超过内联上限时不截断，完整正文存这里；日志详情只带 `{ref, size, sha256, preview}` 标记， 前端点开才来取。
 */
@TableName("workflow_execution_payload")
@Getter
@Setter
public class WorkflowExecutionPayload {

    @TableId private Long id;

    private String executionId;
    private Long ownerUserId;
    private String payloadRef;
    private String contentType;
    private Long size;
    private String sha256;
    private String content;
    private LocalDate createdDate;
}
