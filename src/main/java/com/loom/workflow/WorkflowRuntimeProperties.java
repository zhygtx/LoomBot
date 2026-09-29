package com.loom.workflow;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis Stream 工作流运行时参数。
 *
 * @param taskStreamKey 待执行任务 Stream
 * @param executionLogStreamKey Worker 执行日志 Stream
 * @param indexKeyPrefix 触发倒排索引 key 前缀
 * @param cleanupIntervalMs 过期任务清理周期
 * @param taskTtl 待执行任务有效期
 */
@ConfigurationProperties(prefix = "loom.workflow.runtime")
public record WorkflowRuntimeProperties(
        String taskStreamKey,
        String executionLogStreamKey,
        String indexKeyPrefix,
        Long cleanupIntervalMs,
        Duration taskTtl) {

    public WorkflowRuntimeProperties {
        taskStreamKey =
                taskStreamKey == null || taskStreamKey.isBlank()
                        ? "workflow:task:v1"
                        : taskStreamKey.strip();
        executionLogStreamKey =
                executionLogStreamKey == null || executionLogStreamKey.isBlank()
                        ? "workflow:execution-log:v1"
                        : executionLogStreamKey.strip();
        indexKeyPrefix =
                indexKeyPrefix == null || indexKeyPrefix.isBlank()
                        ? "loom:workflow:index"
                        : indexKeyPrefix.strip();
        cleanupIntervalMs =
                cleanupIntervalMs == null || cleanupIntervalMs <= 0 ? 60_000L : cleanupIntervalMs;
        taskTtl =
                taskTtl == null || taskTtl.isZero() || taskTtl.isNegative()
                        ? Duration.ofMinutes(5)
                        : taskTtl;
    }
}
