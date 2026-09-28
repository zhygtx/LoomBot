package com.loom.workflow.service;

import com.loom.runtime.workflow.WorkflowTriggerIndex;
import com.loom.workflow.WorkflowRuntimeProperties;
import java.util.List;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Redis 触发倒排索引。MySQL 中的工作流定义仍是权威数据。 */
@Service
public class WorkflowTriggerIndexService implements WorkflowTriggerIndex {

    private final StringRedisTemplate redis;
    private final WorkflowRuntimeProperties properties;

    public WorkflowTriggerIndexService(
            StringRedisTemplate redis, WorkflowRuntimeProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public List<Long> findWorkflowVersionIds(
            long connectionId, String connectionType, String nodeKey) {
        Set<String> values =
                redis.opsForSet().members(indexKey(connectionId, connectionType, nodeKey));
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(WorkflowTriggerIndexService::parseLong)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    /** 供工作流保存/发布时更新索引。 */
    public void indexWorkflowVersion(
            long connectionId, String connectionType, String nodeKey, long workflowVersionId) {
        redis.opsForSet()
                .add(
                        indexKey(connectionId, connectionType, nodeKey),
                        Long.toString(workflowVersionId));
    }

    /** 供工作流删除旧版本时移除索引。 */
    public void removeWorkflowVersion(
            long connectionId, String connectionType, String nodeKey, long workflowVersionId) {
        redis.opsForSet()
                .remove(
                        indexKey(connectionId, connectionType, nodeKey),
                        Long.toString(workflowVersionId));
    }

    private String indexKey(long connectionId, String connectionType, String nodeKey) {
        return properties.indexKeyPrefix()
                + ":"
                + connectionId
                + ":"
                + connectionType
                + ":"
                + nodeKey;
    }

    private static Long parseLong(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
