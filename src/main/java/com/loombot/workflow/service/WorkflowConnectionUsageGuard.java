package com.loombot.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.loombot.connection.usage.ConnectionUsageGuard;
import com.loombot.workflow.domain.WorkflowInfo;
import com.loombot.workflow.domain.WorkflowVersion;
import com.loombot.workflow.mapper.WorkflowInfoMapper;
import com.loombot.workflow.mapper.WorkflowVersionMapper;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 连接引用的实现：扫工作流当前版本的定义，找绑定这个连接的节点。
 *
 * <p>不做反向索引表：个人项目量级下，一次删除扫几十个工作流的当前版本定义就够了， 而且永远不会出现"索引和定义不一致"的麻烦。数据量真上来再换。
 */
@Component
public class WorkflowConnectionUsageGuard implements ConnectionUsageGuard {

    private static final Logger log = LoggerFactory.getLogger(WorkflowConnectionUsageGuard.class);

    private final WorkflowInfoMapper infoMapper;
    private final WorkflowVersionMapper versionMapper;
    private final WorkflowTriggerIndexService indexService;
    private final ObjectMapper objectMapper;

    public WorkflowConnectionUsageGuard(
            WorkflowInfoMapper infoMapper,
            WorkflowVersionMapper versionMapper,
            WorkflowTriggerIndexService indexService,
            ObjectMapper objectMapper) {
        this.infoMapper = infoMapper;
        this.versionMapper = versionMapper;
        this.indexService = indexService;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<String> workflowsUsing(long connectionId, Long ownerUserId) {
        List<WorkflowInfo> workflows =
                infoMapper.selectList(
                        new LambdaQueryWrapper<WorkflowInfo>()
                                .eq(
                                        ownerUserId != null,
                                        WorkflowInfo::getOwnerUserId,
                                        ownerUserId));
        List<String> names = new ArrayList<>();
        for (WorkflowInfo info : workflows) {
            if (info.getCurrentVersionId() == null) {
                continue;
            }
            WorkflowVersion version = versionMapper.selectById(info.getCurrentVersionId());
            if (version == null) {
                continue;
            }
            if (referencesConnection(version.getDefinition(), connectionId)) {
                names.add(info.getName());
            }
        }
        return names;
    }

    @Override
    public void onConnectionDeleted(long connectionId) {
        indexService.removeConnection(connectionId);
    }

    /** 事件节点和适配器动作节点都可能绑定连接，两种情况都要拦。 */
    private boolean referencesConnection(String definition, long connectionId) {
        try {
            JsonNode root = objectMapper.readTree(definition);
            for (JsonNode node : root.path("nodes")) {
                Long value = JsonIds.parse(node, "connectionId");
                if (value != null && value == connectionId) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            // 定义读不出来时放行删除：这里的目标是拦住正常引用，不是把删不掉变成新的报错源
            log.warn("扫描工作流定义失败，跳过引用检查: {}", e.getMessage());
        }
        return false;
    }
}
