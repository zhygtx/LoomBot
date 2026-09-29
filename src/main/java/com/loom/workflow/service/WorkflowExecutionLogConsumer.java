package com.loom.workflow.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.loom.workflow.WorkflowRuntimeProperties;
import com.loom.workflow.domain.WorkflowExecution;
import com.loom.workflow.mapper.WorkflowExecutionMapper;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 执行日志落库：消费 Redis 日志流，按 executionId 幂等写入 workflow_execution。
 *
 * <p>先写 MySQL 再确认，确认后按已确认位置裁剪；Redis 崩溃时接受最近约 1 秒的日志丢失。
 *
 * <p>当前默认关闭（{@code loom.workflow.runtime.log-persist-enabled=false}）：执行日志落库留到联调之后再做， 运行时仍会把执行日志写进
 * Redis 流，需要时打开开关即可落库。
 */
@Component
@ConditionalOnProperty(name = "loom.workflow.runtime.log-persist-enabled", havingValue = "true")
public class WorkflowExecutionLogConsumer {

    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutionLogConsumer.class);
    private static final String GROUP = "java-execution-log";
    private static final String CONSUMER = "java-execution-log-1";
    private static final int BATCH = 50;
    private static final long TRIM_MAX_LENGTH = 20_000L;

    private final StringRedisTemplate redis;
    private final WorkflowRuntimeProperties properties;
    private final WorkflowExecutionMapper executionMapper;
    private final AtomicBoolean consuming = new AtomicBoolean(false);

    public WorkflowExecutionLogConsumer(
            StringRedisTemplate redis,
            WorkflowRuntimeProperties properties,
            WorkflowExecutionMapper executionMapper) {
        this.redis = redis;
        this.properties = properties;
        this.executionMapper = executionMapper;
    }

    @PostConstruct
    public void ensureGroup() {
        try {
            redis.opsForStream()
                    .createGroup(properties.executionLogStreamKey(), ReadOffset.from("0"), GROUP);
        } catch (RuntimeException e) {
            // BUSYGROUP：组已存在，正常
            if (!String.valueOf(e.getMessage()).contains("BUSYGROUP")) {
                log.warn("创建执行日志消费组失败: {}", e.getMessage());
            }
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void consume() {
        if (!consuming.compareAndSet(false, true)) {
            return;
        }
        try {
            List<MapRecord<String, Object, Object>> records =
                    redis.opsForStream()
                            .read(
                                    Consumer.from(GROUP, CONSUMER),
                                    StreamReadOptions.empty().count(BATCH),
                                    StreamOffset.create(
                                            properties.executionLogStreamKey(),
                                            ReadOffset.lastConsumed()));
            if (records == null || records.isEmpty()) {
                return;
            }
            for (MapRecord<String, Object, Object> record : records) {
                if (persist(record.getValue())) {
                    redis.opsForStream()
                            .acknowledge(properties.executionLogStreamKey(), GROUP, record.getId());
                }
            }
            redis.opsForStream().trim(properties.executionLogStreamKey(), TRIM_MAX_LENGTH, true);
        } catch (RuntimeException e) {
            log.warn("消费执行日志失败: {}", e.getMessage());
        } finally {
            consuming.set(false);
        }
    }

    private boolean persist(Map<Object, Object> values) {
        String executionId = text(values, "executionId");
        if (executionId.isEmpty()) {
            return true;
        }
        WorkflowExecution entity = new WorkflowExecution();
        entity.setId(IdWorker.getId());
        entity.setExecutionId(executionId);
        entity.setWorkflowId(number(values, "workflowId"));
        entity.setDefinitionVersion(intNumber(values, "definitionVersion"));
        entity.setConnectionId(number(values, "connectionId"));
        entity.setAdapterPluginVersionId(number(values, "adapterPluginVersionId"));
        entity.setConnectionType(text(values, "connectionType"));
        entity.setNodeKey(text(values, "nodeKey"));
        entity.setStatus(text(values, "status"));
        entity.setErrorCode(text(values, "errorCode"));
        entity.setErrorMessage(text(values, "errorMessage"));
        entity.setDetailJson(text(values, "detailJson"));
        entity.setDetailTruncated("1".equals(text(values, "detailTruncated")) ? 1 : 0);
        long startMs = number(values, "startTime") == null ? 0L : number(values, "startTime");
        long endMs = number(values, "endTime") == null ? startMs : number(values, "endTime");
        entity.setStartTime(toDateTime(startMs));
        entity.setEndTime(endMs == 0 ? null : toDateTime(endMs));
        entity.setDurationMs(number(values, "durationMs"));
        entity.setCreatedDate(toDateTime(startMs).toLocalDate());
        entity.setGroupId(extractGroup(values));
        try {
            executionMapper.insert(entity);
            return true;
        } catch (DuplicateKeyException e) {
            // 幂等：同一条执行重复消费直接跳过
            return true;
        } catch (RuntimeException e) {
            log.error("写入执行日志失败: execution={}", executionId, e);
            return false;
        }
    }

    private String extractGroup(Map<Object, Object> values) {
        String detail = text(values, "detailJson");
        int index = detail.indexOf("\"group_id\"");
        if (index < 0) {
            return null;
        }
        int colon = detail.indexOf(':', index);
        int end = detail.indexOf(',', colon);
        if (colon < 0) {
            return null;
        }
        String raw =
                (end < 0 ? detail.substring(colon + 1) : detail.substring(colon + 1, end))
                        .replace("\"", "")
                        .strip();
        return raw.isEmpty() ? null : raw;
    }

    private static LocalDateTime toDateTime(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
    }

    private static String text(Map<Object, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static Long number(Map<Object, Object> values, String key) {
        String raw = text(values, key);
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer intNumber(Map<Object, Object> values, String key) {
        Long value = number(values, key);
        return value == null ? null : value.intValue();
    }
}
