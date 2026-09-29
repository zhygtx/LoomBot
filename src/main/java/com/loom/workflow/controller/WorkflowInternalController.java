package com.loom.workflow.controller;

import com.loom.workflow.WorkflowWorkerProperties;
import com.loom.workflow.service.WorkflowService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工作流运行时使用的内部接口。
 *
 * <p>定义版本不可变，运行时按版本拉取并永久缓存，因此这里不需要推送变更。
 */
@RestController
@RequestMapping("/internal/workflow")
public class WorkflowInternalController {

    private final WorkflowService service;
    private final WorkflowWorkerProperties properties;

    public WorkflowInternalController(
            WorkflowService service, WorkflowWorkerProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/versions/{versionId}")
    public ResponseEntity<Map<String, Object>> version(
            @PathVariable Long versionId,
            @RequestHeader(value = "X-Workflow-Token", required = false) String token) {
        if (!properties.controlToken().equals(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        WorkflowService.RuntimeDefinition definition = service.definitionForRuntime(versionId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocolVersion", 1);
        body.put("workflowId", definition.workflowId());
        body.put("versionId", definition.versionId());
        body.put("versionNo", definition.versionNo());
        body.put("definition", definition.definition());
        body.put("plugins", definition.plugins());
        return ResponseEntity.ok(body);
    }
}
