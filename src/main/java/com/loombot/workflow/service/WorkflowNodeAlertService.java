package com.loombot.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.loombot.plugin.event.PluginNodesChangedEvent;
import com.loombot.workflow.domain.WorkflowInfo;
import com.loombot.workflow.domain.WorkflowNodeAlert;
import com.loombot.workflow.domain.WorkflowVersion;
import com.loombot.workflow.event.WorkflowSchedulesChangedEvent;
import com.loombot.workflow.mapper.WorkflowInfoMapper;
import com.loombot.workflow.mapper.WorkflowNodeAlertMapper;
import com.loombot.workflow.mapper.WorkflowVersionMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 工作流节点失效提醒。
 *
 * <p>插件节点契约变了（入参/出参 schema 变化）或者节点被删掉之后，引用了它的工作流：
 *
 * <ol>
 *   <li>在 {@code workflow_node_alert} 里留一条提醒——列表页和画布据此标出来；
 *   <li>**从触发链路里摘掉**：事件触发从 Redis 倒排索引里删、状态改成停用。
 * </ol>
 *
 * <p>为什么必须摘掉而不是只提醒：事件触发的工作流可能很久没人打开，光标记等于不提示， 它会一直跑失败，而用户只能从失败日志里倒查原因。
 *
 * <p>摘掉之后，定时触发那边靠 {@code enabled=0} 自然从快照里消失（顺带立刻推一次快照）。
 */
@Service
public class WorkflowNodeAlertService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowNodeAlertService.class);

    private final WorkflowNodeAlertMapper alertMapper;
    private final WorkflowInfoMapper infoMapper;
    private final WorkflowVersionMapper versionMapper;
    private final WorkflowTriggerIndexService indexService;
    private final WorkflowDefinitionValidator validator;
    private final ApplicationEventPublisher events;
    private final ObjectMapper objectMapper;

    public WorkflowNodeAlertService(
            WorkflowNodeAlertMapper alertMapper,
            WorkflowInfoMapper infoMapper,
            WorkflowVersionMapper versionMapper,
            WorkflowTriggerIndexService indexService,
            WorkflowDefinitionValidator validator,
            ApplicationEventPublisher events,
            ObjectMapper objectMapper) {
        this.alertMapper = alertMapper;
        this.infoMapper = infoMapper;
        this.versionMapper = versionMapper;
        this.indexService = indexService;
        this.validator = validator;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    /** 插件节点契约变化：标提醒 + 摘触发。 */
    @EventListener
    public void onPluginNodesChanged(PluginNodesChangedEvent event) {
        Map<String, String> reasons = new LinkedHashMap<>();
        event.changedNodeKeys().forEach(key -> reasons.put(key, WorkflowNodeAlert.REASON_CHANGED));
        event.removedNodeKeys().forEach(key -> reasons.put(key, WorkflowNodeAlert.REASON_REMOVED));
        if (reasons.isEmpty()) {
            return;
        }
        try {
            applyChanges(event.pluginVersionId(), reasons);
        } catch (RuntimeException e) {
            // 提醒是通知性质，不该因为它失败就把整次插件同步判成失败
            log.error("处理插件节点变更失败，工作流提醒可能不全: version={}", event.pluginVersionId(), e);
        }
    }

    private void applyChanges(long pluginVersionId, Map<String, String> reasons) {
        Set<Long> touched = new LinkedHashSet<>();
        List<WorkflowInfo> workflows =
                infoMapper.selectList(
                        new LambdaQueryWrapper<WorkflowInfo>()
                                .isNotNull(WorkflowInfo::getCurrentVersionId));
        for (WorkflowInfo info : workflows) {
            WorkflowVersion version = versionMapper.selectById(info.getCurrentVersionId());
            if (version == null) {
                continue;
            }
            JsonNode definition = readDefinition(version);
            if (definition == null) {
                continue;
            }
            for (JsonNode node : definition.path("nodes")) {
                if (!Objects.equals(JsonIds.parse(node, "pluginVersionId"), pluginVersionId)) {
                    continue;
                }
                String nodeKey = node.path("nodeKey").asText("");
                String reason = reasons.get(nodeKey);
                if (reason == null) {
                    continue;
                }
                writeAlert(
                        info.getId(), node.path("id").asText(""), nodeKey, pluginVersionId, reason);
                touched.add(info.getId());
            }
        }
        touched.forEach(this::detachFromTriggers);
        if (!touched.isEmpty()) {
            log.warn(
                    "插件节点变更影响了 {} 个工作流，已标记并停用: version={} workflows={}",
                    touched.size(),
                    pluginVersionId,
                    touched);
            // 停用后定时快照要立刻更新，否则适配器还会按旧快照触发（最多晚一个同步周期）
            events.publishEvent(new WorkflowSchedulesChangedEvent());
        }
    }

    private void writeAlert(
            Long workflowId, String nodeId, String nodeKey, Long pluginVersionId, String reason) {
        if (nodeId.isBlank()) {
            return;
        }
        Long existing =
                alertMapper.selectCount(
                        new LambdaQueryWrapper<WorkflowNodeAlert>()
                                .eq(WorkflowNodeAlert::getWorkflowId, workflowId)
                                .eq(WorkflowNodeAlert::getNodeId, nodeId));
        if (existing != null && existing > 0) {
            return;
        }
        WorkflowNodeAlert alert = new WorkflowNodeAlert();
        alert.setId(IdWorker.getId());
        alert.setWorkflowId(workflowId);
        alert.setNodeId(nodeId);
        alert.setNodeKey(nodeKey);
        alert.setPluginVersionId(pluginVersionId);
        alert.setReason(reason);
        alert.setCreateTime(LocalDateTime.now());
        alertMapper.insert(alert);
    }

    /** 从触发链路里摘掉：先删 Redis 索引，再把状态改成停用。 */
    private void detachFromTriggers(Long workflowId) {
        WorkflowInfo info = infoMapper.selectById(workflowId);
        if (info == null || !Integer.valueOf(1).equals(info.getEnabled())) {
            return;
        }
        Long versionId = info.getCurrentVersionId();
        if (versionId != null) {
            unindexVersion(versionId);
        }
        info.setEnabled(0);
        info.setUpdateTime(LocalDateTime.now());
        infoMapper.updateById(info);
        log.info("工作流因插件节点变更被停用: workflow={} version={}", workflowId, versionId);
    }

    private void unindexVersion(Long versionId) {
        try {
            WorkflowVersion version = versionMapper.selectById(versionId);
            if (version == null) {
                return;
            }
            WorkflowDefinitionValidator.EventEntry entry =
                    validator.eventEntryOf(objectMapper.readTree(version.getDefinition()));
            if (entry == null
                    || !WorkflowDefinitionValidator.KIND_ADAPTER.equals(entry.kind())
                    || entry.connectionId() == null
                    || entry.connectionType() == null) {
                return;
            }
            indexService.removeWorkflowVersion(
                    entry.connectionId(), entry.connectionType(), entry.nodeKey(), versionId);
        } catch (RuntimeException e) {
            log.error("移除触发索引失败，必须人工确认: version={}", versionId, e);
        }
    }

    /**
     * 保存后重算提醒。
     *
     * <p>拿定义里每个节点存的 {@code pluginNodeHash} 和当前目录比：换成当前版本了就清掉，没换就留着。 所以"改好了保存"能让提醒消失，"什么都没改直接保存"不会。
     *
     * <p>老定义里没有这个字段（本次改动之前保存的），跳过不判——宁可不报，也不要凭空造出假提醒。
     */
    public void recompute(Long workflowId, JsonNode definition) {
        alertMapper.delete(
                new LambdaQueryWrapper<WorkflowNodeAlert>()
                        .eq(WorkflowNodeAlert::getWorkflowId, workflowId));
        for (JsonNode node : definition.path("nodes")) {
            String nodeId = node.path("id").asText("");
            String nodeKey = node.path("nodeKey").asText("");
            Long pluginVersionId = JsonIds.parse(node, "pluginVersionId");
            String storedHash = node.path("pluginNodeHash").asText("");
            if (nodeId.isBlank() || pluginVersionId == null || storedHash.isBlank()) {
                continue;
            }
            String currentHash = currentSignature(pluginVersionId, nodeKey);
            if (currentHash == null) {
                writeAlert(
                        workflowId,
                        nodeId,
                        nodeKey,
                        pluginVersionId,
                        WorkflowNodeAlert.REASON_REMOVED);
            } else if (!currentHash.equals(storedHash)) {
                writeAlert(
                        workflowId,
                        nodeId,
                        nodeKey,
                        pluginVersionId,
                        WorkflowNodeAlert.REASON_CHANGED);
            }
        }
    }

    private String currentSignature(Long pluginVersionId, String nodeKey) {
        return validator.nodeSignatureOf(pluginVersionId, nodeKey);
    }

    public List<WorkflowNodeAlert> alertsOf(Long workflowId) {
        return alertMapper.selectList(
                new LambdaQueryWrapper<WorkflowNodeAlert>()
                        .eq(WorkflowNodeAlert::getWorkflowId, workflowId)
                        .orderByAsc(WorkflowNodeAlert::getCreateTime));
    }

    public boolean hasAlert(Long workflowId) {
        Long count =
                alertMapper.selectCount(
                        new LambdaQueryWrapper<WorkflowNodeAlert>()
                                .eq(WorkflowNodeAlert::getWorkflowId, workflowId));
        return count != null && count > 0;
    }

    /** 一批工作流里哪些有提醒。列表页角标用，避免逐个查。 */
    public Set<Long> workflowsWithAlerts(java.util.Collection<Long> workflowIds) {
        if (workflowIds == null || workflowIds.isEmpty()) {
            return Set.of();
        }
        return alertMapper
                .selectList(
                        new LambdaQueryWrapper<WorkflowNodeAlert>()
                                .select(WorkflowNodeAlert::getWorkflowId)
                                .in(WorkflowNodeAlert::getWorkflowId, workflowIds))
                .stream()
                .map(WorkflowNodeAlert::getWorkflowId)
                .collect(java.util.stream.Collectors.toSet());
    }

    /** 有提醒就不让跑：节点契约已经和定义对不上，跑下去只会得到看不懂的错误。 */
    public void assertRunnable(Long workflowId) {
        if (!hasAlert(workflowId)) {
            return;
        }
        throw new IllegalArgumentException("工作流里有节点引用的插件已变更或已删除，请先处理画布上的提醒再执行");
    }

    private JsonNode readDefinition(WorkflowVersion version) {
        try {
            return objectMapper.readTree(version.getDefinition());
        } catch (RuntimeException e) {
            log.warn("工作流定义解析失败，跳过提醒检查: version={}", version.getId(), e);
            return null;
        }
    }
}
