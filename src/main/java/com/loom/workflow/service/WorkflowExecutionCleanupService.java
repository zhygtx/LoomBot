package com.loom.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.loom.workflow.WorkflowRuntimeProperties;
import com.loom.workflow.domain.WorkflowExecution;
import com.loom.workflow.mapper.WorkflowExecutionMapper;
import com.loom.workflow.mapper.WorkflowVersionMapper;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 执行日志保留与清理。
 *
 * <p>两步，顺序不能反：
 *
 * <ol>
 *   <li>删掉超过保留期的 `workflow_execution`，分批删，避免一次删太多把表锁住；
 *   <li>再删掉「不是当前版本、已过保留期、且没有任何执行记录引用」的 `workflow_version`。
 * </ol>
 *
 * <p>第二步依赖第一步：老的执行记录删掉之后，它引用的那些定义版本才变成无引用，这时候才允许删。留着当前版本， 以及仍被保留期内执行记录引用的版本。
 */
@Service
public class WorkflowExecutionCleanupService {

    private static final Logger log =
            LoggerFactory.getLogger(WorkflowExecutionCleanupService.class);

    /** 单轮清理的硬上限，防止积压太多时一次跑很久。剩下的下个周期继续。 */
    private static final int MAX_ROWS_PER_RUN = 50_000;

    private final WorkflowRuntimeProperties properties;
    private final WorkflowExecutionMapper executionMapper;
    private final WorkflowVersionMapper versionMapper;

    public WorkflowExecutionCleanupService(
            WorkflowRuntimeProperties properties,
            WorkflowExecutionMapper executionMapper,
            WorkflowVersionMapper versionMapper) {
        this.properties = properties;
        this.executionMapper = executionMapper;
        this.versionMapper = versionMapper;
    }

    @Scheduled(
            fixedDelayString = "${loom.workflow.runtime.log-cleanup-interval:1h}",
            initialDelayString = "${loom.workflow.runtime.log-cleanup-initial-delay:5m}")
    public void cleanup() {
        try {
            int retentionDays = properties.logRetentionDays();
            LocalDate cutoffDate = LocalDate.now().minusDays(retentionDays);
            int executions = deleteExpiredExecutions(cutoffDate);
            int versions = deleteUnreferencedVersions(cutoffDate);
            if (executions > 0 || versions > 0) {
                log.info(
                        "执行日志清理完成: executions={}, versions={}, cutoff={}",
                        executions,
                        versions,
                        cutoffDate);
            }
        } catch (RuntimeException e) {
            log.warn("执行日志清理失败，下个周期重试: {}", e.getMessage(), e);
        }
    }

    private int deleteExpiredExecutions(LocalDate cutoffDate) {
        int batch = properties.logCleanupBatchSize();
        int total = 0;
        while (total < MAX_ROWS_PER_RUN) {
            int deleted =
                    executionMapper.delete(
                            new LambdaQueryWrapper<WorkflowExecution>()
                                    .lt(WorkflowExecution::getCreatedDate, cutoffDate)
                                    .last("LIMIT " + batch));
            total += deleted;
            if (deleted < batch) {
                break;
            }
        }
        return total;
    }

    private int deleteUnreferencedVersions(LocalDate cutoffDate) {
        return versionMapper.deleteUnreferencedBefore(cutoffDate.atStartOfDay());
    }
}
