package com.loom.workflow.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.loom.common.api.Result;
import com.loom.common.security.CurrentUser;
import com.loom.workflow.domain.WorkflowInfo;
import com.loom.workflow.domain.WorkflowVersion;
import com.loom.workflow.dto.WorkflowExecutionDetail;
import com.loom.workflow.dto.WorkflowExecutionSummary;
import com.loom.workflow.dto.WorkflowSaveRequest;
import com.loom.workflow.mapper.WorkflowInfoMapper;
import com.loom.workflow.mapper.WorkflowVersionMapper;
import com.loom.workflow.service.WorkflowExecutionQueryService;
import com.loom.workflow.service.WorkflowNodeCatalogService;
import com.loom.workflow.service.WorkflowService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 工作流定义与执行记录接口。 */
@RestController
@RequestMapping("/api/workflow")
public class WorkflowController {

    private final WorkflowService service;
    private final WorkflowInfoMapper infoMapper;
    private final WorkflowVersionMapper versionMapper;
    private final WorkflowExecutionQueryService executionQuery;
    private final ObjectMapper objectMapper;
    private final WorkflowNodeCatalogService nodeCatalog;

    public WorkflowController(
            WorkflowService service,
            WorkflowInfoMapper infoMapper,
            WorkflowVersionMapper versionMapper,
            WorkflowExecutionQueryService executionQuery,
            ObjectMapper objectMapper,
            WorkflowNodeCatalogService nodeCatalog) {
        this.service = service;
        this.infoMapper = infoMapper;
        this.versionMapper = versionMapper;
        this.executionQuery = executionQuery;
        this.objectMapper = objectMapper;
        this.nodeCatalog = nodeCatalog;
    }

    /** 画布节点目录：插件节点 + 系统节点。 */
    @GetMapping("/nodes")
    @PreAuthorize("@permission.has(authentication, 'workflow:def:list')")
    public Result<Map<String, Object>> nodes() {
        return Result.success(nodeCatalog.catalog());
    }

    @GetMapping("/list")
    @PreAuthorize("@permission.has(authentication, 'workflow:def:list')")
    public Result<List<WorkflowInfo>> list() {
        Long ownerUserId = CurrentUser.requireId();
        return Result.success(
                infoMapper.selectList(
                        new LambdaQueryWrapper<WorkflowInfo>()
                                .eq(WorkflowInfo::getOwnerUserId, ownerUserId)
                                .orderByDesc(WorkflowInfo::getUpdateTime)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@permission.has(authentication, 'workflow:def:list')")
    public Result<Map<String, Object>> get(@PathVariable Long id) {
        WorkflowInfo info = service.requireOwned(id, CurrentUser.requireId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workflow", info);
        if (info.getCurrentVersionId() != null) {
            WorkflowVersion version = service.requireVersion(info.getCurrentVersionId());
            body.put("versionNo", version.getVersionNo());
            body.put("definition", objectMapper.readTree(version.getDefinition()));
        }
        return Result.success(body);
    }

    @PostMapping("/save")
    @PreAuthorize("@permission.has(authentication, 'workflow:def:save')")
    public Result<Map<String, Object>> save(@RequestBody WorkflowSaveRequest request) {
        Long userId = CurrentUser.requireId();
        WorkflowVersion version =
                service.save(
                        request.id(),
                        request.name(),
                        request.description(),
                        request.definition(),
                        userId,
                        userId);
        return Result.success(
                Map.of(
                        "workflowId", version.getWorkflowId(),
                        "versionId", version.getId(),
                        "versionNo", version.getVersionNo()));
    }

    @PostMapping("/{id}/enabled")
    @PreAuthorize("@permission.has(authentication, 'workflow:def:update')")
    public Result<Boolean> setEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
        service.setEnabled(id, enabled, CurrentUser.requireId());
        return Result.success(true);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@permission.has(authentication, 'workflow:def:delete')")
    public Result<Boolean> delete(@PathVariable Long id) {
        service.delete(id, CurrentUser.requireId());
        return Result.success(true);
    }

    @PostMapping("/{id}/run")
    @PreAuthorize("@permission.has(authentication, 'workflow:def:run')")
    public Result<JsonNode> run(@PathVariable Long id) {
        return Result.success(service.test(id, CurrentUser.requireId()));
    }

    /**
     * 执行日志：默认不含测试执行，按主键游标向前翻页。
     *
     * <p>编辑器里的执行日志抽屉复用同一个接口，只是固定传 `workflowId`。
     */
    @GetMapping("/executions")
    @PreAuthorize("@permission.has(authentication, 'workflow:log:list')")
    public Result<List<WorkflowExecutionSummary>> executionLog(
            @RequestParam(required = false) Long workflowId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean includeTest,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false) Integer size) {
        return Result.success(
                executionQuery.list(
                        CurrentUser.requireId(),
                        workflowId,
                        status,
                        includeTest,
                        keyword,
                        beforeId,
                        size));
    }

    @GetMapping("/executions/{executionId}")
    @PreAuthorize("@permission.has(authentication, 'workflow:log:list')")
    public Result<WorkflowExecutionDetail> executionDetail(@PathVariable String executionId) {
        return Result.success(executionQuery.detail(CurrentUser.requireId(), executionId));
    }

    /**
     * 执行时绑定的定义快照。
     *
     * <p>历史日志模式下画布按这份定义渲染，工作流后来被编辑过也不影响日志对位。
     */
    @GetMapping("/executions/{executionId}/definition")
    @PreAuthorize("@permission.has(authentication, 'workflow:log:list')")
    public Result<Map<String, Object>> executionDefinition(@PathVariable String executionId) {
        return Result.success(executionQuery.definition(CurrentUser.requireId(), executionId));
    }

    /** 大内容正文：日志详情里只放引用，前端点开才来取，正文不截断。 */
    @GetMapping("/executions/{executionId}/payloads/{ref}")
    @PreAuthorize("@permission.has(authentication, 'workflow:log:list')")
    public Result<String> executionPayload(
            @PathVariable String executionId, @PathVariable String ref) {
        return Result.success(executionQuery.payload(CurrentUser.requireId(), executionId, ref));
    }

    /** 落盘文件（图片等二进制）：日志里只有文件名，点下载才来取。 */
    @GetMapping("/executions/{executionId}/files/{fileName}")
    @PreAuthorize("@permission.has(authentication, 'workflow:log:list')")
    public ResponseEntity<byte[]> executionFile(
            @PathVariable String executionId, @PathVariable String fileName) throws IOException {
        Path path = executionQuery.artifact(CurrentUser.requireId(), executionId, fileName);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDisposition(
                ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build());
        // 插件返回的内容不可信：别让浏览器按内容猜类型执行
        headers.add("X-Content-Type-Options", "nosniff");
        headers.setCacheControl(CacheControl.noStore());
        return new ResponseEntity<>(Files.readAllBytes(path), headers, HttpStatus.OK);
    }
}
