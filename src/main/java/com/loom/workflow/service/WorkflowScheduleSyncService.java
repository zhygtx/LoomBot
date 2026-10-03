package com.loom.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.loom.adapter.AdapterControlClient;
import com.loom.adapter.AdapterControlException;
import com.loom.workflow.domain.WorkflowInfo;
import com.loom.workflow.domain.WorkflowVersion;
import com.loom.workflow.event.WorkflowSchedulesChangedEvent;
import com.loom.workflow.mapper.WorkflowInfoMapper;
import com.loom.workflow.mapper.WorkflowVersionMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 定时触发快照同步。
 *
 * <p>Java 是调度真相：这里把启用中的定时工作流整理成快照推给适配器层，由适配器层求值 Cron 并投递任务。 推送失败只告警，下个周期自动重试，不影响其他工作流。
 */
@Service
public class WorkflowScheduleSyncService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowScheduleSyncService.class);

    private final WorkflowInfoMapper infoMapper;
    private final WorkflowVersionMapper versionMapper;
    private final WorkflowService workflowService;
    private final AdapterControlClient controlClient;
    private final ObjectMapper objectMapper;

    /** 上一次成功推送的快照指纹；内容没变就不重复记日志。 */
    private volatile String lastPushedSignature;

    public WorkflowScheduleSyncService(
            WorkflowInfoMapper infoMapper,
            WorkflowVersionMapper versionMapper,
            WorkflowService workflowService,
            AdapterControlClient controlClient,
            ObjectMapper objectMapper) {
        this.infoMapper = infoMapper;
        this.versionMapper = versionMapper;
        this.workflowService = workflowService;
        this.controlClient = controlClient;
        this.objectMapper = objectMapper;
    }

    /** 启动后等适配器宿主起来再推第一次；适配器宿主由 Java 托管，启动需要几秒， 立刻推送只会得到一次「控制 API 不可达」的噪音告警。 */
    @Scheduled(
            fixedDelayString = "${loom.workflow.runtime.schedule-sync-interval:30s}",
            initialDelayString = "${loom.workflow.runtime.schedule-sync-initial-delay:15s}")
    public void syncAutomatically() {
        push();
    }

    /** 定时集合被动变化（比如节点失效导致工作流被停用）时立刻推一次，不用等下一个周期。 */
    @EventListener
    public void onSchedulesChanged(WorkflowSchedulesChangedEvent event) {
        push();
    }

    /** 推送整份快照；幂等，适配器层整份替换。 */
    public void push() {
        List<Map<String, Object>> schedules = collect();
        String signature = schedules.toString();
        try {
            controlClient.pushSchedules(schedules);
            if (!signature.equals(lastPushedSignature)) {
                lastPushedSignature = signature;
                log.info("定时触发快照已推送: {} 条", schedules.size());
            } else {
                log.debug("定时触发快照无变化: {} 条", schedules.size());
            }
        } catch (AdapterControlException e) {
            log.warn("推送定时触发失败，稍后自动重试: {}", e.getMessage());
        }
    }

    private List<Map<String, Object>> collect() {
        List<Map<String, Object>> schedules = new ArrayList<>();
        List<WorkflowInfo> workflows =
                infoMapper.selectList(
                        new LambdaQueryWrapper<WorkflowInfo>().eq(WorkflowInfo::getEnabled, 1));
        for (WorkflowInfo info : workflows) {
            Long versionId = info.getCurrentVersionId();
            if (versionId == null) {
                continue;
            }
            WorkflowVersion version = versionMapper.selectById(versionId);
            if (version == null) {
                continue;
            }
            try {
                WorkflowDefinitionValidator.EventEntry eventEntry =
                        workflowService.eventEntryOf(version);
                if (eventEntry == null
                        || !WorkflowDefinitionValidator.KIND_SCHEDULE.equals(eventEntry.kind())) {
                    continue;
                }
                JsonNode definition = objectMapper.readTree(version.getDefinition());
                String cron = "";
                for (JsonNode node : definition.path("nodes")) {
                    if (eventEntry.nodeId().equals(node.path("id").asText(""))) {
                        cron = node.path("config").path("cron").asText("");
                        break;
                    }
                }
                if (cron.isBlank()) {
                    log.warn("定时工作流缺少 Cron 表达式，跳过: workflow={}", info.getId());
                    continue;
                }
                CronExpressionSupport.validate(cron);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("workflowVersionId", versionId);
                item.put("nodeKey", eventEntry.nodeKey());
                item.put("cron", cron);
                schedules.add(item);
            } catch (RuntimeException e) {
                log.warn("整理定时触发失败，跳过: workflow={} error={}", info.getId(), e.getMessage());
            }
        }
        // 指纹按版本号排序后生成，避免数据库返回顺序抖动导致误判为「有变化」。
        schedules.sort(Comparator.comparing(item -> (Long) item.get("workflowVersionId")));
        return schedules;
    }
}
