package com.loombot.workflow.dto;

import tools.jackson.databind.JsonNode;

/** 保存工作流：不传 id 表示新建，传 id 表示在该工作流下产生新版本。 */
public record WorkflowSaveRequest(Long id, String name, String description, JsonNode definition) {}
