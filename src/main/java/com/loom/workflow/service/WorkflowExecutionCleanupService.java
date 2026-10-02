package com.loom.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.loom.system.service.SystemConfigService;
import com.loom.workflow.WorkflowRuntimeProperties;
import com.loom.workflow.domain.WorkflowExecution;
import com.loom.workflow.domain.WorkflowExecutionPayload;
import com.loom.workflow.mapper.WorkflowExecutionHourlyMapper;
import com.loom.workflow.mapper.WorkflowExecutionMapper;
import com.loom.workflow.mapper.WorkflowExecutionPayloadMapper;
import com.loom.workflow.mapper.WorkflowVersionMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 执行日志保留与清理。
 *
 * <p>默认每小时跑一次，顺序不能反：
 *
 * <ol>
 *   <li>把超期的 `workflow_execution` 先聚合进 `workflow_execution_hourly`，再删原始行（同一事务）；
 *   <li>再删掉「不是当前版本、已过保留期、且没有任何执行记录引用」的 `workflow_version`；
 *   <li>按汇总自己的保留期删老的小时汇总。
 * </ol>
 *
 * <p>第二步依赖第一步：老的执行记录删掉之后，它引用的那些定义版本才变成无引用，这时候才允许删。留着当前版本， 以及仍被保留期内执行记录引用的版本。
 *
 * <h2>保留期是运行时配置</h2>
 *
 * <p>天数从 {@code sys_config} 读（`workflow.log.retention-days` /
 * `workflow.log.summary-retention-days`）， 每次跑都重新读，所以在系统配置页改完下一轮就生效，不用重启。配置文件里的值只作为兜底默认。
 */
@Service
public class WorkflowExecutionCleanupService {

    private static final Logger log =
            LoggerFactory.getLogger(WorkflowExecutionCleanupService.class);

    /** 单轮清理的硬上限，防止积压太多时一次跑很久。剩下的下个周期继续。 */
    private static final int MAX_ROWS_PER_RUN = 50_000;

    /** 按工作流整体清理时的分批大小，避免一次 IN 列表太长。 */
    private static final int PURGE_BATCH = 500;

    /** 小时汇总没有配置时的兜底保留天数。0 = 永久保留。 */
    private static final int DEFAULT_SUMMARY_RETENTION_DAYS = 0;

    /** 两条保留期配置里存天数的字段名，值形如 {@code {"days": 7}}。 */
    private static final String RETENTION_DAYS_FIELD = "days";

    private final WorkflowRuntimeProperties properties;
    private final WorkflowExecutionMapper executionMapper;
    private final WorkflowExecutionPayloadMapper payloadMapper;
    private final WorkflowVersionMapper versionMapper;
    private final WorkflowExecutionHourlyMapper hourlyMapper;
    private final SystemConfigService systemConfig;
    private final TransactionTemplate transaction;

    public WorkflowExecutionCleanupService(
            WorkflowRuntimeProperties properties,
            WorkflowExecutionMapper executionMapper,
            WorkflowExecutionPayloadMapper payloadMapper,
            WorkflowVersionMapper versionMapper,
            WorkflowExecutionHourlyMapper hourlyMapper,
            SystemConfigService systemConfig,
            PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.executionMapper = executionMapper;
        this.payloadMapper = payloadMapper;
        this.versionMapper = versionMapper;
        this.hourlyMapper = hourlyMapper;
        this.systemConfig = systemConfig;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * 删工作流时一次性清掉它的全部执行日志痕迹：执行记录、大内容行、落盘文件、小时汇总。
     *
     * <p>定义都删了，日志里的节点和版本也就对不上，没有留着的意义；大内容行和落盘文件必须跟着走，
     * 否则删完工作流它们会永远留在库里和磁盘上（保留期清理只会扫「已过期的执行记录」，够不到孤儿行）。
     */
    public int purgeWorkflow(Long workflowId) {
        List<WorkflowExecution> rows =
                executionMapper.selectList(
                        new LambdaQueryWrapper<WorkflowExecution>()
                                .select(WorkflowExecution::getId, WorkflowExecution::getExecutionId)
                                .eq(WorkflowExecution::getWorkflowId, workflowId));
        if (rows.isEmpty()) {
            hourlyMapper.deleteByWorkflowId(workflowId);
            return 0;
        }
        List<Long> ids = rows.stream().map(WorkflowExecution::getId).toList();
        List<String> executionIds =
                rows.stream().map(WorkflowExecution::getExecutionId).distinct().toList();
        for (int start = 0; start < ids.size(); start += PURGE_BATCH) {
            executionMapper.deleteByIds(
                    ids.subList(start, Math.min(start + PURGE_BATCH, ids.size())));
        }
        Path artifactsRoot = properties.artifactsRoot();
        for (int start = 0; start < executionIds.size(); start += PURGE_BATCH) {
            List<String> slice =
                    executionIds.subList(start, Math.min(start + PURGE_BATCH, executionIds.size()));
            payloadMapper.delete(
                    new LambdaQueryWrapper<WorkflowExecutionPayload>()
                            .in(WorkflowExecutionPayload::getExecutionId, slice));
        }
        executionIds.forEach(executionId -> deleteArtifacts(artifactsRoot, executionId));
        hourlyMapper.deleteByWorkflowId(workflowId);
        return rows.size();
    }

    @Scheduled(
            fixedDelayString = "${loom.workflow.runtime.log-cleanup-interval:1h}",
            initialDelayString = "${loom.workflow.runtime.log-cleanup-initial-delay:5m}")
    public void cleanup() {
        try {
            int retentionDays =
                    systemConfig.positiveInt(
                            SystemConfigService.WORKFLOW_LOG_RETENTION_DAYS,
                            RETENTION_DAYS_FIELD,
                            properties.logRetentionDays());
            // 0 = 永久保留。汇总行数只跟「小时 × 工作流 × 连接 × 触发类型」的组合数有关，
            // 跟执行量无关，留着不心疼；真要按合规要求清理，填个天数即可。
            int summaryDays =
                    systemConfig.nonNegativeInt(
                            SystemConfigService.WORKFLOW_LOG_SUMMARY_RETENTION_DAYS,
                            RETENTION_DAYS_FIELD,
                            DEFAULT_SUMMARY_RETENTION_DAYS);
            LocalDateTime cutoffTime = LocalDateTime.now().minusDays(retentionDays);
            // created_date 上有索引，用它做粗筛（start_time < cutoffTime 必然满足 created_date <= cutoffDate）；
            // 真正的边界按 start_time 精确算，否则「保留 1 天」会变成「保留今天 + 昨天」即 24~48 小时。
            LocalDate cutoffDate = cutoffTime.toLocalDate();

            int executions = deleteExpiredExecutions(cutoffTime, cutoffDate);
            int versions = deleteUnreferencedVersions(cutoffDate);
            purgeOrphanArtifacts(cutoffDate);
            int hourly = 0;
            if (summaryDays > 0) {
                hourly =
                        hourlyMapper.deleteBefore(
                                LocalDateTime.now()
                                        .minusDays(summaryDays)
                                        .truncatedTo(ChronoUnit.HOURS));
            }
            if (executions > 0 || versions > 0 || hourly > 0) {
                log.info(
                        "执行日志清理完成: executions={}, versions={}, hourly={}（原始保留 {} 天，汇总保留 {}）",
                        executions,
                        versions,
                        hourly,
                        retentionDays,
                        summaryDays > 0 ? summaryDays + " 天" : "永久");
            }
        } catch (RuntimeException e) {
            log.warn("执行日志清理失败，下个周期重试: {}", e.getMessage(), e);
        }
    }

    private int deleteExpiredExecutions(LocalDateTime cutoffTime, LocalDate cutoffDate) {
        int batch = properties.logCleanupBatchSize();
        Path artifactsRoot = properties.artifactsRoot();
        int total = 0;
        while (total < MAX_ROWS_PER_RUN) {
            List<WorkflowExecution> expired =
                    executionMapper.selectList(
                            new LambdaQueryWrapper<WorkflowExecution>()
                                    .select(
                                            WorkflowExecution::getId,
                                            WorkflowExecution::getExecutionId)
                                    .le(WorkflowExecution::getCreatedDate, cutoffDate)
                                    .lt(WorkflowExecution::getStartTime, cutoffTime)
                                    .last("LIMIT " + batch));
            if (expired.isEmpty()) {
                break;
            }
            List<Long> ids = expired.stream().map(WorkflowExecution::getId).toList();
            List<String> executionIds =
                    expired.stream().map(WorkflowExecution::getExecutionId).distinct().toList();
            // 汇总和删除必须同一个事务：汇总里的计数是累加的，先汇总后删中间失败就会重复计数。
            // 大内容行和落盘文件跟在事务后面删——它们删失败只是晚一轮再删，不会算错数。
            transaction.executeWithoutResult(
                    status -> {
                        hourlyMapper.rollupByIds(ids);
                        executionMapper.deleteByIds(ids);
                    });
            payloadMapper.delete(
                    new LambdaQueryWrapper<WorkflowExecutionPayload>()
                            .in(WorkflowExecutionPayload::getExecutionId, executionIds));
            executionIds.forEach(executionId -> deleteArtifacts(artifactsRoot, executionId));
            total += expired.size();
            if (expired.size() < batch) {
                break;
            }
        }
        return total;
    }

    /** 删掉一次执行落盘的大内容；目录名就是 executionId，路径先校验仍在根目录内。 */
    private void deleteArtifacts(Path root, String executionId) {
        if (executionId == null || executionId.isBlank()) {
            return;
        }
        Path target = root.resolve(executionId).normalize();
        if (!target.startsWith(root)) {
            return;
        }
        try (var walk = Files.walk(target)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException e) {
                                    log.debug("删除落盘大内容失败: {}", path, e);
                                }
                            });
        } catch (IOException e) {
            log.debug("清理落盘大内容目录失败: {}", target, e);
        }
    }

    /**
     * 清理没有对应执行记录的落盘目录。
     *
     * <p>正常路径是「删执行记录时顺带删目录」，但工作流整体删除、写盘后落库失败等情况会留下孤儿目录， 这里按目录名的 executionId 反查一次，查不到且目录本身也过了保留期才删。
     */
    private void purgeOrphanArtifacts(LocalDate cutoffDate) {
        Path root = properties.artifactsRoot();
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var entries = Files.list(root)) {
            for (Path dir : entries.filter(Files::isDirectory).toList()) {
                String executionId = dir.getFileName().toString();
                Long count =
                        executionMapper.selectCount(
                                new LambdaQueryWrapper<WorkflowExecution>()
                                        .eq(WorkflowExecution::getExecutionId, executionId));
                if (count != null && count > 0) {
                    continue;
                }
                var modified =
                        Files.getLastModifiedTime(dir)
                                .toInstant()
                                .atZone(java.time.ZoneId.systemDefault())
                                .toLocalDate();
                if (modified.isBefore(cutoffDate)) {
                    deleteArtifacts(root, executionId);
                }
            }
        } catch (IOException e) {
            log.debug("扫描落盘大内容目录失败: {}", root, e);
        }
    }

    private int deleteUnreferencedVersions(LocalDate cutoffDate) {
        return versionMapper.deleteUnreferencedBefore(cutoffDate.atStartOfDay());
    }
}
