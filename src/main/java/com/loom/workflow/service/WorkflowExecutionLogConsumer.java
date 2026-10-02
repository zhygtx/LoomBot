package com.loom.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.loom.connection.domain.WsConnection;
import com.loom.connection.mapper.WsConnectionMapper;
import com.loom.workflow.WorkflowRuntimeProperties;
import com.loom.workflow.domain.WorkflowExecution;
import com.loom.workflow.domain.WorkflowExecutionPayload;
import com.loom.workflow.domain.WorkflowInfo;
import com.loom.workflow.mapper.WorkflowExecutionMapper;
import com.loom.workflow.mapper.WorkflowExecutionPayloadMapper;
import com.loom.workflow.mapper.WorkflowInfoMapper;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
import tools.jackson.databind.JsonNode;

/**
 * 执行日志落库：消费 Redis 日志流，按 executionId 幂等写入 workflow_execution。
 *
 * <p>先写 MySQL 再确认，确认后裁剪流长度；Redis 崩溃时接受最近约 1 秒的日志丢失。
 *
 * <p>超限明细由运行时截断并打 `detailTruncated`；这里额外兜一层毒消息保护： 同一条记录连续写库失败到上限后，写一条只有错误摘要的降级记录并确认，
 * 不让单条坏数据永远卡住消费组。
 */
@Component
@ConditionalOnProperty(name = "loom.workflow.runtime.log-persist-enabled", havingValue = "true")
public class WorkflowExecutionLogConsumer {

    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutionLogConsumer.class);
    private static final String GROUP = "java-execution-log";
    private static final String CONSUMER = "java-execution-log-1";
    private static final int BATCH = 50;
    private static final int MAX_ATTEMPTS = 5;
    private static final long TRIM_MAX_LENGTH = 20_000L;

    private final StringRedisTemplate redis;
    private final WorkflowRuntimeProperties properties;
    private final WorkflowExecutionMapper executionMapper;
    private final WorkflowInfoMapper infoMapper;
    private final WsConnectionMapper connectionMapper;
    private final WorkflowExecutionPayloadMapper payloadMapper;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    private final AtomicBoolean consuming = new AtomicBoolean(false);
    private final Map<String, Integer> failedAttempts = new ConcurrentHashMap<>();

    public WorkflowExecutionLogConsumer(
            StringRedisTemplate redis,
            WorkflowRuntimeProperties properties,
            WorkflowExecutionMapper executionMapper,
            WorkflowInfoMapper infoMapper,
            WsConnectionMapper connectionMapper,
            WorkflowExecutionPayloadMapper payloadMapper,
            tools.jackson.databind.ObjectMapper objectMapper) {
        this.redis = redis;
        this.properties = properties;
        this.executionMapper = executionMapper;
        this.infoMapper = infoMapper;
        this.connectionMapper = connectionMapper;
        this.payloadMapper = payloadMapper;
        this.objectMapper = objectMapper;
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
                String recordId = record.getId().getValue();
                if (persist(record.getValue())) {
                    failedAttempts.remove(recordId);
                    acknowledge(record);
                    continue;
                }
                int attempt = failedAttempts.merge(recordId, 1, Integer::sum);
                if (attempt >= MAX_ATTEMPTS) {
                    failedAttempts.remove(recordId);
                    log.error(
                            "执行日志连续写入失败 {} 次，写入降级记录后跳过: execution={}",
                            attempt,
                            text(record.getValue(), "executionId"));
                    persistDegraded(record.getValue());
                    acknowledge(record);
                }
            }
            redis.opsForStream().trim(properties.executionLogStreamKey(), TRIM_MAX_LENGTH, true);
        } catch (RuntimeException e) {
            log.warn("消费执行日志失败: {}", e.getMessage());
        } finally {
            consuming.set(false);
        }
    }

    private void acknowledge(MapRecord<String, Object, Object> record) {
        redis.opsForStream().acknowledge(properties.executionLogStreamKey(), GROUP, record.getId());
    }

    private boolean persist(Map<Object, Object> values) {
        String executionId = text(values, "executionId");
        if (executionId.isEmpty()) {
            return true;
        }
        long startMs = number(values, "startTime") == null ? 0L : number(values, "startTime");
        long endMs = number(values, "endTime") == null ? startMs : number(values, "endTime");
        WorkflowExecution entity = new WorkflowExecution();
        entity.setId(IdWorker.getId());
        entity.setExecutionId(executionId);
        entity.setWorkflowId(number(values, "workflowId"));
        entity.setOwnerUserId(ownerOf(number(values, "workflowId")));
        entity.setDefinitionVersion(intNumber(values, "definitionVersion"));
        entity.setConnectionId(number(values, "connectionId"));
        entity.setAdapterPluginVersionId(number(values, "adapterPluginVersionId"));
        entity.setConnectionType(text(values, "connectionType"));
        entity.setConnectionName(connectionNameOf(number(values, "connectionId")));
        entity.setEventNodeKey(text(values, "eventNodeKey"));
        entity.setEventNodeName(blankToNull(text(values, "eventNodeName")));
        entity.setTriggerType(triggerType(values));
        entity.setEventSummary(blankToNull(text(values, "eventSummary")));
        entity.setStatus(text(values, "status"));
        entity.setErrorCode(text(values, "errorCode"));
        entity.setErrorMessage(text(values, "errorMessage"));
        entity.setDetailJson(text(values, "detailJson"));
        entity.setDetailTruncated("1".equals(text(values, "detailTruncated")) ? 1 : 0);
        entity.setStartTime(toDateTime(startMs));
        entity.setEndTime(endMs == 0 ? null : toDateTime(endMs));
        entity.setDurationMs(number(values, "durationMs"));
        entity.setCreatedDate(toDateTime(startMs).toLocalDate());
        try {
            executionMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 幂等：执行行已经在库里，继续补一次大内容（上一次可能只写了一半）
        } catch (RuntimeException e) {
            log.error("写入执行日志失败: execution={}", executionId, e);
            return false;
        }
        try {
            persistPayloads(
                    executionId, entity.getOwnerUserId(), startMs, text(values, "payloads"));
        } catch (RuntimeException e) {
            log.error("写入执行日志的大内容失败: execution={}", executionId, e);
            return false;
        }
        return true;
    }

    /**
     * 大内容单独一张表，正文不截断。
     *
     * <p>日志详情里只带 `{ref, size, sha256, preview}` 标记，前端点开才按 ref 来取；二进制/文件走 artifact 目录，不经过这里。
     */
    private void persistPayloads(
            String executionId, Long ownerUserId, long startMs, String payloadsJson) {
        if (payloadsJson == null || payloadsJson.isBlank()) {
            return;
        }
        JsonNode array;
        try {
            array = objectMapper.readTree(payloadsJson);
        } catch (RuntimeException e) {
            log.warn("大内容字段不是合法 JSON，跳过: execution={}", executionId);
            return;
        }
        for (JsonNode item : array) {
            String ref = item.path("ref").asText("").strip();
            String content = item.path("content").asText("");
            if (ref.isEmpty() || content.isEmpty()) {
                continue;
            }
            WorkflowExecutionPayload row = new WorkflowExecutionPayload();
            row.setId(IdWorker.getId());
            row.setExecutionId(executionId);
            row.setOwnerUserId(ownerUserId);
            row.setPayloadRef(ref);
            row.setContentType(item.path("contentType").asText("text/plain"));
            row.setSize(item.path("size").asLong(content.length()));
            row.setSha256(item.path("sha256").asText(""));
            row.setContent(content);
            row.setCreatedDate(toDateTime(startMs).toLocalDate());
            try {
                payloadMapper.insert(row);
            } catch (DuplicateKeyException e) {
                // 幂等：同一条大内容重复消费直接跳过
            }
        }
    }

    /** 连续失败到上限时的降级写入：保留可定位的信息，明细只放失败原因。 */
    private void persistDegraded(Map<Object, Object> values) {
        String executionId = text(values, "executionId");
        if (executionId.isEmpty()) {
            return;
        }
        try {
            executionMapper.delete(
                    new LambdaQueryWrapper<WorkflowExecution>()
                            .eq(WorkflowExecution::getExecutionId, executionId));
            long startMs = number(values, "startTime") == null ? 0L : number(values, "startTime");
            WorkflowExecution entity = new WorkflowExecution();
            entity.setId(IdWorker.getId());
            entity.setExecutionId(executionId);
            entity.setWorkflowId(number(values, "workflowId"));
            entity.setOwnerUserId(ownerOf(number(values, "workflowId")));
            entity.setDefinitionVersion(intNumber(values, "definitionVersion"));
            entity.setConnectionId(number(values, "connectionId"));
            entity.setConnectionType(text(values, "connectionType"));
            entity.setConnectionName(connectionNameOf(number(values, "connectionId")));
            entity.setEventNodeKey(text(values, "eventNodeKey"));
            entity.setEventNodeName(blankToNull(text(values, "eventNodeName")));
            entity.setTriggerType(triggerType(values));
            entity.setStatus(text(values, "status").isEmpty() ? "FAILED" : text(values, "status"));
            entity.setErrorCode(text(values, "errorCode"));
            entity.setErrorMessage(text(values, "errorMessage"));
            entity.setDetailJson("{\"detailUnavailable\":true}");
            entity.setDetailTruncated(1);
            entity.setStartTime(toDateTime(startMs));
            entity.setEndTime(toDateTime(startMs));
            entity.setDurationMs(0L);
            entity.setCreatedDate(toDateTime(startMs).toLocalDate());
            executionMapper.insert(entity);
        } catch (RuntimeException e) {
            log.error("写入降级执行日志也失败，放弃这条记录: execution={}", executionId, e);
        }
    }

    private String triggerType(Map<Object, Object> values) {
        String value = text(values, "triggerType").toUpperCase(java.util.Locale.ROOT);
        return value.isEmpty() ? "EVENT" : value;
    }

    private Long ownerOf(Long workflowId) {
        if (workflowId == null) {
            return 0L;
        }
        WorkflowInfo info = infoMapper.selectById(workflowId);
        // 工作流已被删除时留 0：这类记录不属于任何用户，全局日志页也查不到
        return info == null || info.getOwnerUserId() == null ? 0L : info.getOwnerUserId();
    }

    /**
     * 连接名快照。
     *
     * <p>日志是历史记录：连接之后改名或删除都不该影响它。查不到就留空（连接在落库前已被删除）， 前端按"连接已删除"降级显示，不影响这条记录其余字段。
     */
    private String connectionNameOf(Long connectionId) {
        if (connectionId == null) {
            return null;
        }
        WsConnection connection = connectionMapper.selectById(connectionId);
        return connection == null ? null : connection.getName();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
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
