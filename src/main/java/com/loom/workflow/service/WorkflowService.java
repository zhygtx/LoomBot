package com.loom.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.loom.plugin.domain.Plugin;
import com.loom.plugin.domain.PluginVersion;
import com.loom.plugin.mapper.PluginMapper;
import com.loom.plugin.mapper.PluginVersionMapper;
import com.loom.workflow.WorkflowRuntimeProperties;
import com.loom.workflow.domain.WorkflowInfo;
import com.loom.workflow.domain.WorkflowVersion;
import com.loom.workflow.mapper.WorkflowInfoMapper;
import com.loom.workflow.mapper.WorkflowVersionMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 工作流定义、版本、事件触发索引与任务投递。
 *
 * <p>MySQL 是定义真相，Redis 索引是缓存；保存时先写 MySQL 再更新索引，更新失败必须告警。
 */
@Service
public class WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    private final WorkflowInfoMapper infoMapper;
    private final WorkflowVersionMapper versionMapper;
    private final PluginVersionMapper pluginVersionMapper;
    private final PluginMapper pluginMapper;
    private final WorkflowDefinitionValidator validator;
    private final WorkflowTriggerIndexService indexService;
    private final StringRedisTemplate redis;
    private final WorkflowRuntimeProperties runtimeProperties;
    private final WorkflowTestClient testClient;
    private final ObjectMapper objectMapper;
    private final WorkflowExecutionCleanupService executionCleanup;
    private final WorkflowNodeAlertService nodeAlertService;

    public WorkflowService(
            WorkflowInfoMapper infoMapper,
            WorkflowVersionMapper versionMapper,
            PluginVersionMapper pluginVersionMapper,
            PluginMapper pluginMapper,
            WorkflowDefinitionValidator validator,
            WorkflowTriggerIndexService indexService,
            StringRedisTemplate redis,
            WorkflowRuntimeProperties runtimeProperties,
            WorkflowTestClient testClient,
            ObjectMapper objectMapper,
            WorkflowExecutionCleanupService executionCleanup,
            WorkflowNodeAlertService nodeAlertService) {
        this.infoMapper = infoMapper;
        this.versionMapper = versionMapper;
        this.pluginVersionMapper = pluginVersionMapper;
        this.pluginMapper = pluginMapper;
        this.validator = validator;
        this.indexService = indexService;
        this.redis = redis;
        this.runtimeProperties = runtimeProperties;
        this.testClient = testClient;
        this.objectMapper = objectMapper;
        this.executionCleanup = executionCleanup;
        this.nodeAlertService = nodeAlertService;
    }

    /** 运行时按版本拉取定义所需的全部信息。 */
    public record RuntimeDefinition(
            Long workflowId,
            Long versionId,
            Integer versionNo,
            JsonNode definition,
            List<Map<String, Object>> plugins) {}

    @Transactional
    public WorkflowVersion save(
            Long workflowId,
            String name,
            String description,
            JsonNode definition,
            Long ownerUserId,
            Long operatorUserId) {
        WorkflowDefinitionValidator.EventEntry eventEntry =
                validator.validate(definition, ownerUserId);
        WorkflowInfo info;
        if (workflowId == null) {
            info = new WorkflowInfo();
            info.setId(IdWorker.getId());
            info.setName(name == null || name.isBlank() ? "未命名工作流" : name.strip());
            info.setDescription(description);
            info.setEnabled(1);
            info.setOwnerUserId(ownerUserId);
            info.setCreateBy(operatorUserId);
            info.setCreateTime(LocalDateTime.now());
            infoMapper.insert(info);
        } else {
            info = requireOwned(workflowId, ownerUserId);
            if (name != null && !name.isBlank()) {
                info.setName(name.strip());
            }
            info.setDescription(description);
            info.setUpdateBy(operatorUserId);
            info.setUpdateTime(LocalDateTime.now());
        }

        Integer previousVersionNo =
                versionMapper
                        .selectList(
                                new LambdaQueryWrapper<WorkflowVersion>()
                                        .eq(WorkflowVersion::getWorkflowId, info.getId())
                                        .orderByDesc(WorkflowVersion::getVersionNo))
                        .stream()
                        .findFirst()
                        .map(WorkflowVersion::getVersionNo)
                        .orElse(0);
        WorkflowVersion version = new WorkflowVersion();
        version.setId(IdWorker.getId());
        version.setWorkflowId(info.getId());
        version.setVersionNo(previousVersionNo + 1);
        version.setDefinition(definition.toString());
        version.setCreateBy(operatorUserId);
        version.setCreateTime(LocalDateTime.now());
        versionMapper.insert(version);

        Long previousVersionId = info.getCurrentVersionId();
        info.setCurrentVersionId(version.getId());
        infoMapper.updateById(info);

        if (previousVersionId != null) {
            unindexVersion(previousVersionId);
        }
        indexVersion(version.getId(), eventEntry);
        // 保存成功后重算提醒：节点换成当前版本了就清掉，没换（只是原样保存）就留着。
        nodeAlertService.recompute(info.getId(), definition);
        log.info(
                "工作流版本已保存: workflow={} version={} event={}",
                info.getId(),
                version.getVersionNo(),
                eventEntry == null ? "" : eventEntry.nodeKey());
        return version;
    }

    @Transactional
    public void setEnabled(Long workflowId, boolean enabled, Long ownerUserId) {
        WorkflowInfo info = requireOwned(workflowId, ownerUserId);
        info.setEnabled(enabled ? 1 : 0);
        info.setUpdateTime(LocalDateTime.now());
        infoMapper.updateById(info);
        Long versionId = info.getCurrentVersionId();
        if (versionId == null) {
            return;
        }
        if (enabled) {
            // 有节点提醒说明定义和当前插件对不上，放开只会立刻跑失败
            nodeAlertService.assertRunnable(workflowId);
            indexVersion(versionId, eventEntryOf(requireVersion(versionId)));
        } else {
            unindexVersion(versionId);
        }
    }

    @Transactional
    public void delete(Long workflowId, Long ownerUserId) {
        WorkflowInfo info = requireOwned(workflowId, ownerUserId);
        List<WorkflowVersion> versions =
                versionMapper.selectList(
                        new LambdaQueryWrapper<WorkflowVersion>()
                                .eq(WorkflowVersion::getWorkflowId, workflowId));
        versions.forEach(version -> unindexVersion(version.getId()));
        versionMapper.delete(
                new LambdaQueryWrapper<WorkflowVersion>()
                        .eq(WorkflowVersion::getWorkflowId, workflowId));
        // 定义没了，执行历史也留不住：明细里的节点和版本都对应不上。
        // 执行记录、大内容行、落盘文件、日汇总一起清，别留下够不到的数据。
        int executions = executionCleanup.purgeWorkflow(workflowId);
        infoMapper.deleteById(info.getId());
        log.info("工作流已删除: id={}, 一并清理执行日志 {} 条", workflowId, executions);
    }

    /** 测试执行：直接调用工作流运行时，不经过 Redis 和触发索引。 */
    public JsonNode test(Long workflowId, Long ownerUserId) {
        WorkflowInfo info = requireOwned(workflowId, ownerUserId);
        nodeAlertService.assertRunnable(workflowId);
        Long versionId = info.getCurrentVersionId();
        if (versionId == null) {
            throw new IllegalArgumentException("工作流还没有保存过定义");
        }
        if (eventEntryOf(requireVersion(versionId)) != null) {
            throw new IllegalArgumentException("包含事件节点的工作流不能直接测试");
        }
        return testClient.test(versionId);
    }

    public RuntimeDefinition definitionForRuntime(Long versionId) {
        WorkflowVersion version = requireVersion(versionId);
        JsonNode definition = objectMapper.readTree(version.getDefinition());
        Set<Long> pluginVersionIds = new LinkedHashSet<>();
        for (JsonNode node : definition.path("nodes")) {
            Long pluginVersionId = JsonIds.parse(node, "pluginVersionId");
            if (pluginVersionId != null) {
                pluginVersionIds.add(pluginVersionId);
            }
        }
        List<Map<String, Object>> plugins = new ArrayList<>();
        if (!pluginVersionIds.isEmpty()) {
            for (PluginVersion pluginVersion :
                    pluginVersionMapper.selectBatchIds(pluginVersionIds)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("pluginVersionId", pluginVersion.getId());
                item.put("installPath", pluginVersion.getInstallPath());
                item.put("entryPoint", pluginVersion.getEntryPoint());
                item.put("pluginKey", pluginKeyOf(pluginVersion.getId()));
                plugins.add(item);
            }
        }
        return new RuntimeDefinition(
                version.getWorkflowId(),
                version.getId(),
                version.getVersionNo(),
                definition,
                plugins);
    }

    /** 定时事件和测试执行由 Java 投递，平台事件由适配器投递。 */
    public String publishTask(
            WorkflowVersion version,
            WorkflowDefinitionValidator.EventEntry eventEntry,
            Map<String, Object> event,
            Long connectionId,
            String connectionType,
            Long adapterPluginVersionId,
            String eventId) {
        long now = System.currentTimeMillis();
        long deadline = now + runtimeProperties.taskTtl().toMillis();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("messageId", UUID.randomUUID().toString());
        fields.put("executionId", UUID.randomUUID().toString());
        fields.put("eventId", eventId == null ? "" : eventId);
        fields.put("traceId", UUID.randomUUID().toString().replace("-", ""));
        fields.put("connectionId", connectionId == null ? "" : connectionId.toString());
        fields.put("connectionType", connectionType == null ? "" : connectionType);
        fields.put(
                "pluginVersionId",
                adapterPluginVersionId == null ? "" : adapterPluginVersionId.toString());
        fields.put("nodeKey", eventEntry.nodeKey());
        fields.put("workflowVersionId", version.getId().toString());
        fields.put("event", toJson(event));
        fields.put("createdAt", Long.toString(now));
        fields.put("deadline", Long.toString(deadline));
        RecordId recordId =
                redis.opsForStream()
                        .add(
                                StreamRecords.mapBacked(fields)
                                        .withStreamKey(runtimeProperties.taskStreamKey()));
        if (recordId != null) {
            redis.opsForZSet()
                    .add(
                            runtimeProperties.taskStreamKey() + ":deadlines",
                            recordId.getValue(),
                            deadline);
        }
        return fields.get("executionId");
    }

    public WorkflowInfo requireOwned(Long workflowId, Long ownerUserId) {
        WorkflowInfo info = infoMapper.selectById(workflowId);
        if (info == null) {
            throw new IllegalArgumentException("工作流不存在: " + workflowId);
        }
        if (ownerUserId != null && !ownerUserId.equals(info.getOwnerUserId())) {
            throw new IllegalArgumentException("工作流不属于当前用户: " + workflowId);
        }
        return info;
    }

    public WorkflowVersion requireVersion(Long versionId) {
        WorkflowVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new IllegalArgumentException("定义版本不存在: " + versionId);
        }
        return version;
    }

    private void indexVersion(Long versionId, WorkflowDefinitionValidator.EventEntry eventEntry) {
        if (eventEntry == null) {
            return;
        }
        if (!WorkflowDefinitionValidator.KIND_ADAPTER.equals(eventEntry.kind())) {
            return;
        }
        if (eventEntry.connectionId() == null || eventEntry.connectionType() == null) {
            log.warn("适配器事件节点缺少连接信息，跳过索引: version={}", versionId);
            return;
        }
        try {
            indexService.indexWorkflowVersion(
                    eventEntry.connectionId(),
                    eventEntry.connectionType(),
                    eventEntry.nodeKey(),
                    versionId);
        } catch (RuntimeException e) {
            log.error("更新触发索引失败，必须人工确认: version={}", versionId, e);
        }
    }

    private void unindexVersion(Long versionId) {
        try {
            WorkflowVersion version = versionMapper.selectById(versionId);
            if (version == null) {
                return;
            }
            WorkflowDefinitionValidator.EventEntry eventEntry = eventEntryOf(version);
            if (eventEntry == null
                    || !WorkflowDefinitionValidator.KIND_ADAPTER.equals(eventEntry.kind())
                    || eventEntry.connectionId() == null
                    || eventEntry.connectionType() == null) {
                return;
            }
            indexService.removeWorkflowVersion(
                    eventEntry.connectionId(),
                    eventEntry.connectionType(),
                    eventEntry.nodeKey(),
                    versionId);
        } catch (RuntimeException e) {
            log.error("移除触发索引失败，必须人工确认: version={}", versionId, e);
        }
    }

    /** 从定义里取出事件入口，不跑完整校验（历史定义可能引用已删除的插件）。 */
    public WorkflowDefinitionValidator.EventEntry eventEntryOf(WorkflowVersion version) {
        return validator.eventEntryOf(objectMapper.readTree(version.getDefinition()));
    }

    private String pluginKeyOf(Long pluginVersionId) {
        PluginVersion version = pluginVersionMapper.selectById(pluginVersionId);
        if (version == null) {
            return "";
        }
        Plugin plugin = pluginMapper.selectById(version.getPluginId());
        return plugin == null ? "" : plugin.getPluginKey();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化事件失败", e);
        }
    }
}
