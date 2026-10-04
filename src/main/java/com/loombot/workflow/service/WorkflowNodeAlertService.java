package com.loombot.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.loombot.connection.domain.WsConnection;
import com.loombot.connection.mapper.WsConnectionMapper;
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
    private final WsConnectionMapper connectionMapper;
    private final WorkflowTriggerIndexService indexService;
    private final WorkflowDefinitionValidator validator;
    private final ApplicationEventPublisher events;
    private final ObjectMapper objectMapper;

    public WorkflowNodeAlertService(
            WorkflowNodeAlertMapper alertMapper,
            WorkflowInfoMapper infoMapper,
            WorkflowVersionMapper versionMapper,
            WsConnectionMapper connectionMapper,
            WorkflowTriggerIndexService indexService,
            WorkflowDefinitionValidator validator,
            ApplicationEventPublisher events,
            ObjectMapper objectMapper) {
        this.alertMapper = alertMapper;
        this.infoMapper = infoMapper;
        this.versionMapper = versionMapper;
        this.connectionMapper = connectionMapper;
        this.indexService = indexService;
        this.validator = validator;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    /** 插件节点契约变化：标提醒 + 摘触发。 */
    @EventListener
    public void onPluginNodesChanged(PluginNodesChangedEvent event) {
        Map<String, PluginNodesChangedEvent.NodeChange> changes = new LinkedHashMap<>();
        Map<String, String> reasons = new LinkedHashMap<>();
        event.changedNodes()
                .forEach(
                        node -> {
                            changes.put(node.nodeKey(), node);
                            reasons.put(node.nodeKey(), WorkflowNodeAlert.REASON_CHANGED);
                        });
        event.removedNodes()
                .forEach(
                        node -> {
                            changes.put(node.nodeKey(), node);
                            reasons.put(node.nodeKey(), WorkflowNodeAlert.REASON_REMOVED);
                        });
        if (reasons.isEmpty()) {
            return;
        }
        try {
            applyChanges(event, changes, reasons);
        } catch (RuntimeException e) {
            // 提醒是通知性质，不该因为它失败就把整次插件同步判成失败
            log.error("处理插件节点变更失败，工作流提醒可能不全: version={}", event.pluginVersionId(), e);
        }
    }

    private void applyChanges(
            PluginNodesChangedEvent event,
            Map<String, PluginNodesChangedEvent.NodeChange> changes,
            Map<String, String> reasons) {
        long pluginVersionId = event.pluginVersionId();
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
                writeAlert(info, version, node, event, changes.get(nodeKey), reason);
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

    /** 写一条失效记录。身份快照从事件里抄，目录行删掉之后这条记录仍然能解释"原来是什么、为什么没了"。 */
    private void writeAlert(
            WorkflowInfo info,
            WorkflowVersion version,
            JsonNode node,
            PluginNodesChangedEvent event,
            PluginNodesChangedEvent.NodeChange change,
            String reason) {
        String nodeId = node.path("id").asText("");
        if (nodeId.isBlank()) {
            return;
        }
        WorkflowNodeAlert alert = new WorkflowNodeAlert();
        alert.setWorkflowId(info.getId());
        alert.setWorkflowVersionId(version == null ? null : version.getId());
        alert.setNodeId(nodeId);
        alert.setNodeKey(change == null ? node.path("nodeKey").asText("") : change.nodeKey());
        alert.setNodeName(change == null ? null : trim(change.nodeName()));
        alert.setNodeType(change == null ? null : change.nodeType());
        alert.setPluginKey(event.pluginKey());
        alert.setPluginVersion(event.pluginVersion());
        alert.setPluginVersionId(event.pluginVersionId());
        Long connectionId = JsonIds.parse(node, "connectionId");
        alert.setConnectionId(connectionId);
        alert.setConnectionName(connectionName(connectionId));
        alert.setReason(reason);
        alert.setDetail(
                WorkflowNodeAlert.REASON_REMOVED.equals(reason) ? "节点已从插件目录移除" : "节点契约或实现已变更");
        alert.setDetectedAt(LocalDateTime.now());
        upsert(alert);
    }

    /** 同一个（工作流，画布节点）只保留一条记录；状态变化时以最新原因覆盖。 */
    private void upsert(WorkflowNodeAlert alert) {
        WorkflowNodeAlert existing =
                alertMapper.selectOne(
                        new LambdaQueryWrapper<WorkflowNodeAlert>()
                                .eq(WorkflowNodeAlert::getWorkflowId, alert.getWorkflowId())
                                .eq(WorkflowNodeAlert::getNodeId, alert.getNodeId()));
        if (existing != null) {
            alert.setId(existing.getId());
            alert.setCreateTime(existing.getCreateTime());
            alertMapper.updateById(alert);
            return;
        }
        alert.setId(IdWorker.getId());
        alert.setCreateTime(LocalDateTime.now());
        alertMapper.insert(alert);
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 128 ? value : value.substring(0, 128);
    }

    /** 从触发链路里摘掉：先删 Redis 索引，再把状态改成停用。 */
    private void detachFromTriggers(Long workflowId) {
        WorkflowInfo info = infoMapper.selectById(workflowId);
        if (info == null) {
            return;
        }
        Long versionId = info.getCurrentVersionId();
        if (Integer.valueOf(1).equals(info.getEnabled())) {
            if (versionId != null) {
                unindexVersion(versionId);
            }
            info.setEnabled(0);
        }
        // availability 是系统判定、enabled 是用户开关，这里两个都动是过渡态：
        // 触发链路（定时快照）目前仍按 enabled 过滤，等它切到 availability 之后这里就不再碰 enabled。
        info.setAvailability(WorkflowInfo.AVAILABILITY_UNAVAILABLE);
        info.setAvailabilityCheckedAt(LocalDateTime.now());
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
                writeMinimalAlert(
                        workflowId,
                        nodeId,
                        nodeKey,
                        pluginVersionId,
                        WorkflowNodeAlert.REASON_REMOVED);
            } else if (!currentHash.equals(storedHash)) {
                writeMinimalAlert(
                        workflowId,
                        nodeId,
                        nodeKey,
                        pluginVersionId,
                        WorkflowNodeAlert.REASON_CHANGED);
            }
        }
        // 重算之后没有失效记录，说明用户已经改好了，把系统判定恢复回可用。
        WorkflowInfo info = infoMapper.selectById(workflowId);
        if (info != null) {
            info.setAvailability(
                    hasAlert(workflowId)
                            ? WorkflowInfo.AVAILABILITY_UNAVAILABLE
                            : WorkflowInfo.AVAILABILITY_AVAILABLE);
            info.setAvailabilityCheckedAt(LocalDateTime.now());
            infoMapper.updateById(info);
        }
    }

    /** 保存时重算只判断"还在不在、哈希一不一致"，没有目录里的身份快照可抄。 */
    private void writeMinimalAlert(
            Long workflowId, String nodeId, String nodeKey, Long pluginVersionId, String reason) {
        WorkflowNodeAlert alert = new WorkflowNodeAlert();
        alert.setWorkflowId(workflowId);
        alert.setNodeId(nodeId);
        alert.setNodeKey(nodeKey);
        alert.setPluginVersionId(pluginVersionId);
        alert.setReason(reason);
        alert.setDetectedAt(LocalDateTime.now());
        upsert(alert);
    }

    private String connectionName(Long connectionId) {
        if (connectionId == null) {
            return null;
        }
        WsConnection connection = connectionMapper.selectById(connectionId);
        return connection == null ? null : connection.getName();
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

    /** 工作流删除时一起清掉提醒；这张表按 workflow_id 关联，没有数据库外键兜底。 */
    public void removeWorkflow(Long workflowId) {
        alertMapper.delete(
                new LambdaQueryWrapper<WorkflowNodeAlert>()
                        .eq(WorkflowNodeAlert::getWorkflowId, workflowId));
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
