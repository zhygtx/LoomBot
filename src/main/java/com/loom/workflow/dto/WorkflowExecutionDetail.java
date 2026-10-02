package com.loom.workflow.dto;

import tools.jackson.databind.JsonNode;

/**
 * 执行日志详情：列表项的信息 + 展开好的 detail_json。
 *
 * @param summary 列表项字段
 * @param trace 节点过程；解析失败时为 null，由前端按"明细不可用"处理
 */
public record WorkflowExecutionDetail(WorkflowExecutionSummary summary, JsonNode trace) {}
