package com.loom.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.loom.workflow.WorkflowRuntimeProperties;
import com.loom.workflow.domain.WorkflowExecution;
import com.loom.workflow.domain.WorkflowExecutionPayload;
import com.loom.workflow.domain.WorkflowInfo;
import com.loom.workflow.domain.WorkflowVersion;
import com.loom.workflow.dto.WorkflowExecutionDetail;
import com.loom.workflow.dto.WorkflowExecutionSummary;
import com.loom.workflow.mapper.WorkflowExecutionMapper;
import com.loom.workflow.mapper.WorkflowExecutionPayloadMapper;
import com.loom.workflow.mapper.WorkflowInfoMapper;
import com.loom.workflow.mapper.WorkflowVersionMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 执行日志查询。
 *
 * <p>所有入口都按 ownerUserId 过滤：日志里带节点输入输出，不能跨用户看。 分页用主键游标（雪花 id 递增），不用 offset，避免新记录插入时翻页错位。
 */
@Service
public class WorkflowExecutionQueryService {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    private final WorkflowExecutionMapper executionMapper;
    private final WorkflowExecutionPayloadMapper payloadMapper;
    private final WorkflowInfoMapper infoMapper;
    private final WorkflowVersionMapper versionMapper;
    private final ObjectMapper objectMapper;
    private final WorkflowRuntimeProperties properties;

    public WorkflowExecutionQueryService(
            WorkflowExecutionMapper executionMapper,
            WorkflowExecutionPayloadMapper payloadMapper,
            WorkflowInfoMapper infoMapper,
            WorkflowVersionMapper versionMapper,
            ObjectMapper objectMapper,
            WorkflowRuntimeProperties properties) {
        this.executionMapper = executionMapper;
        this.payloadMapper = payloadMapper;
        this.infoMapper = infoMapper;
        this.versionMapper = versionMapper;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 执行时绑定的定义快照。
     *
     * <p>历史日志模式下画布要按当时那份定义渲染：工作流后来被改过之后 nodeId 会对不上，节点详情就挂空了。
     * 版本清理只删「没有任何执行记录引用」的定义，所以这里只要执行记录还在，快照就一定取得到。
     */
    public Map<String, Object> definition(Long ownerUserId, String executionId) {
        WorkflowExecution row = requireExecution(ownerUserId, executionId);
        WorkflowInfo info = infoMapper.selectById(row.getWorkflowId());
        WorkflowVersion version = versionOf(row.getWorkflowId(), row.getDefinitionVersion());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(
                "workflowId",
                row.getWorkflowId() == null ? null : String.valueOf(row.getWorkflowId()));
        body.put("workflowName", info == null ? null : info.getName());
        body.put("versionNo", row.getDefinitionVersion());
        body.put("definitionAvailable", version != null);
        body.put("definition", version == null ? null : parseDefinition(version.getDefinition()));
        return body;
    }

    private WorkflowVersion versionOf(Long workflowId, Integer versionNo) {
        if (workflowId == null || versionNo == null) {
            return null;
        }
        return versionMapper.selectOne(
                new LambdaQueryWrapper<WorkflowVersion>()
                        .eq(WorkflowVersion::getWorkflowId, workflowId)
                        .eq(WorkflowVersion::getVersionNo, versionNo));
    }

    private JsonNode parseDefinition(String definition) {
        if (definition == null || definition.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(definition);
        } catch (RuntimeException e) {
            // 快照坏了不该让整条记录查不出来，前端会退回当前版本并提示
            return null;
        }
    }

    /**
     * 取一条大内容的完整正文（日志详情里只放引用，前端点开才调这里）。
     *
     * <p>正文不截断，原样返回；权限按 ownerUserId + executionId 双重校验。
     */
    public String payload(Long ownerUserId, String executionId, String ref) {
        requireExecution(ownerUserId, executionId);
        WorkflowExecutionPayload row =
                payloadMapper.selectOne(
                        new LambdaQueryWrapper<WorkflowExecutionPayload>()
                                .eq(WorkflowExecutionPayload::getExecutionId, executionId)
                                .eq(WorkflowExecutionPayload::getPayloadRef, ref)
                                .eq(WorkflowExecutionPayload::getOwnerUserId, ownerUserId));
        if (row == null) {
            throw new IllegalArgumentException("大内容不存在: " + executionId + "/" + ref);
        }
        return row.getContent();
    }

    /**
     * 取一条落盘文件（二进制这类内容不进库，只留文件引用）。
     *
     * <p>路径先 normalize 再校验仍在 artifact 根目录内，避免用 `../` 读到别的文件。
     */
    public Path artifact(Long ownerUserId, String executionId, String fileName) {
        requireExecution(ownerUserId, executionId);
        Path root = properties.artifactsRoot();
        Path target = root.resolve(executionId).resolve(fileName).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            throw new IllegalArgumentException("文件不存在或不可用: " + fileName);
        }
        return target;
    }

    private WorkflowExecution requireExecution(Long ownerUserId, String executionId) {
        WorkflowExecution row =
                executionMapper.selectOne(
                        new LambdaQueryWrapper<WorkflowExecution>()
                                .eq(WorkflowExecution::getExecutionId, executionId)
                                .eq(WorkflowExecution::getOwnerUserId, ownerUserId));
        if (row == null) {
            throw new IllegalArgumentException("执行记录不存在: " + executionId);
        }
        return row;
    }

    public List<WorkflowExecutionSummary> list(
            Long ownerUserId,
            Long workflowId,
            String status,
            Boolean includeTest,
            Long beforeId,
            Integer size) {
        LambdaQueryWrapper<WorkflowExecution> query =
                new LambdaQueryWrapper<WorkflowExecution>()
                        .eq(WorkflowExecution::getOwnerUserId, ownerUserId)
                        .orderByDesc(WorkflowExecution::getId)
                        .last("LIMIT " + pageSize(size));
        if (workflowId != null) {
            query.eq(WorkflowExecution::getWorkflowId, workflowId);
        }
        if (status != null && !status.isBlank()) {
            query.eq(
                    WorkflowExecution::getStatus,
                    status.strip().toUpperCase(java.util.Locale.ROOT));
        }
        if (!Boolean.TRUE.equals(includeTest)) {
            query.ne(WorkflowExecution::getTriggerType, "TEST");
        }
        if (beforeId != null) {
            query.lt(WorkflowExecution::getId, beforeId);
        }
        List<WorkflowExecution> rows = executionMapper.selectList(query);
        Map<Long, String> names = workflowNames(rows);
        return rows.stream()
                .map(row -> WorkflowExecutionSummary.of(row, names.get(row.getWorkflowId())))
                .toList();
    }

    public WorkflowExecutionDetail detail(Long ownerUserId, String executionId) {
        WorkflowExecution row =
                executionMapper.selectOne(
                        new LambdaQueryWrapper<WorkflowExecution>()
                                .eq(WorkflowExecution::getExecutionId, executionId)
                                .eq(WorkflowExecution::getOwnerUserId, ownerUserId));
        if (row == null) {
            throw new IllegalArgumentException("执行记录不存在: " + executionId);
        }
        WorkflowInfo info = infoMapper.selectById(row.getWorkflowId());
        return new WorkflowExecutionDetail(
                WorkflowExecutionSummary.of(row, info == null ? null : info.getName()),
                parseTrace(row.getDetailJson()));
    }

    private JsonNode parseTrace(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(detailJson);
        } catch (RuntimeException e) {
            // 明细坏了不该让整条记录查不出来，列表信息仍然有用
            return null;
        }
    }

    private Map<Long, String> workflowNames(List<WorkflowExecution> rows) {
        Set<Long> ids = new LinkedHashSet<>();
        for (WorkflowExecution row : rows) {
            if (row.getWorkflowId() != null) {
                ids.add(row.getWorkflowId());
            }
        }
        Map<Long, String> names = new HashMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        for (WorkflowInfo info : infoMapper.selectBatchIds(ids)) {
            names.put(info.getId(), info.getName());
        }
        return names;
    }

    private static int pageSize(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }
}
