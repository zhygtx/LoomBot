package com.loom.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.loom.workflow.domain.WorkflowExecution;
import com.loom.workflow.domain.WorkflowInfo;
import com.loom.workflow.dto.WorkflowExecutionDetail;
import com.loom.workflow.dto.WorkflowExecutionSummary;
import com.loom.workflow.mapper.WorkflowExecutionMapper;
import com.loom.workflow.mapper.WorkflowInfoMapper;
import java.util.HashMap;
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
    private final WorkflowInfoMapper infoMapper;
    private final ObjectMapper objectMapper;

    public WorkflowExecutionQueryService(
            WorkflowExecutionMapper executionMapper,
            WorkflowInfoMapper infoMapper,
            ObjectMapper objectMapper) {
        this.executionMapper = executionMapper;
        this.infoMapper = infoMapper;
        this.objectMapper = objectMapper;
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
