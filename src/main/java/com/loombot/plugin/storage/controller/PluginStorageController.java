package com.loombot.plugin.storage.controller;

import com.loombot.plugin.storage.PluginStorageException;
import com.loombot.plugin.storage.PluginStorageProperties;
import com.loombot.plugin.storage.service.PluginLockService;
import com.loombot.plugin.storage.service.PluginStorageService;
import com.loombot.plugin.storage.service.PluginStorageService.FileRecord;
import com.loombot.plugin.storage.service.PluginStorageService.StateRecord;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 插件持久化的内部接口。
 *
 * <p>只有 Python 运行时调用它，用户和浏览器都看不到。选取这种"Python 回调 Java"的形态，是为了让 MySQL / Redis / 对象存储三样东西只被 Java
 * 一侧的代码碰到：插件进程里没有数据库连接、没有对象存储密钥， 换后端时也不用重新打包插件。
 *
 * <h2>为什么 key 和文件引用走查询参数</h2>
 *
 * <p>两者都允许带斜杠（{@code group:{id}:config}、{@code downloads/2026-10-05.bin}）。放进路径段要么被
 * 当成路径分隔符，要么依赖容器允许编码斜杠 —— 后者是一个部署相关的开关，换容器就可能失效。 查询参数没有这个问题。
 *
 * <h2>鉴权</h2>
 *
 * <p>{@code /internal/**} 在 Spring Security 里是放行的，令牌校验由本类逐个方法做， 与其他内部接口（{@code
 * WorkflowInternalController}）保持一致。
 */
@RestController
@RequestMapping("/internal/plugin-storage")
public class PluginStorageController {

    /** 请求体里"存什么值"的载体。 */
    public record StateWriteRequest(JsonNode value, Double ttlSeconds, Long expectedVersion) {}

    private final PluginStorageService service;
    private final PluginLockService locks;
    private final PluginStorageProperties properties;
    private final ObjectMapper objectMapper;

    public PluginStorageController(
            PluginStorageService service,
            PluginLockService locks,
            PluginStorageProperties properties,
            ObjectMapper objectMapper) {
        this.service = service;
        this.locks = locks;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    // ============================================================
    // 结构化状态
    // ============================================================

    @GetMapping("/{pluginKey}/{scope}/{scopeId}/kv/entry")
    public ResponseEntity<Map<String, Object>> getState(
            @PathVariable String pluginKey,
            @PathVariable String scope,
            @PathVariable String scopeId,
            @RequestParam("key") String key,
            @RequestHeader(value = "X-Plugin-Storage-Token", required = false) String token) {
        if (!authorized(token)) {
            return unauthorized();
        }
        Optional<StateRecord> record = service.getState(pluginKey, scope, scopeId, key);
        return record.map(value -> ResponseEntity.ok(stateBody(value)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{pluginKey}/{scope}/{scopeId}/kv/entry")
    public ResponseEntity<Map<String, Object>> putState(
            @PathVariable String pluginKey,
            @PathVariable String scope,
            @PathVariable String scopeId,
            @RequestParam("key") String key,
            @RequestBody StateWriteRequest request,
            @RequestHeader(value = "X-Plugin-Storage-Token", required = false) String token) {
        if (!authorized(token)) {
            return unauthorized();
        }
        StateWriteRequest payload =
                request == null ? new StateWriteRequest(null, null, null) : request;
        String valueJson = objectMapper.writeValueAsString(payload.value());
        StateRecord record =
                service.putState(
                        pluginKey,
                        scope,
                        scopeId,
                        key,
                        valueJson,
                        payload.ttlSeconds(),
                        payload.expectedVersion());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", record.key());
        body.put("version", record.version());
        body.put("expiresAt", record.expiresAtMillis());
        return ResponseEntity.ok(body);
    }

    @DeleteMapping("/{pluginKey}/{scope}/{scopeId}/kv/entry")
    public ResponseEntity<Map<String, Object>> deleteState(
            @PathVariable String pluginKey,
            @PathVariable String scope,
            @PathVariable String scopeId,
            @RequestParam("key") String key,
            @RequestParam(value = "expectedVersion", required = false) Long expectedVersion,
            @RequestHeader(value = "X-Plugin-Storage-Token", required = false) String token) {
        if (!authorized(token)) {
            return unauthorized();
        }
        boolean deleted = service.deleteState(pluginKey, scope, scopeId, key, expectedVersion);
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    @GetMapping("/{pluginKey}/{scope}/{scopeId}/kv/keys")
    public ResponseEntity<List<Map<String, Object>>> listState(
            @PathVariable String pluginKey,
            @PathVariable String scope,
            @PathVariable String scopeId,
            @RequestParam(value = "prefix", required = false) String prefix,
            @RequestParam(value = "limit", defaultValue = "1000") int limit,
            @RequestHeader(value = "X-Plugin-Storage-Token", required = false) String token) {
        if (!authorized(token)) {
            return unauthorized();
        }
        List<Map<String, Object>> body =
                service.listState(pluginKey, scope, scopeId, prefix, limit).stream()
                        .map(this::stateBody)
                        .toList();
        return ResponseEntity.ok(body);
    }

    // ============================================================
    // 文件
    // ============================================================

    @PutMapping("/{pluginKey}/{scope}/{scopeId}/files/entry")
    public ResponseEntity<Map<String, Object>> putFile(
            @PathVariable String pluginKey,
            @PathVariable String scope,
            @PathVariable String scopeId,
            @RequestParam("ref") String ref,
            @RequestParam(value = "contentType", required = false) String contentType,
            @RequestBody(required = false) byte[] data,
            @RequestHeader(value = "X-Plugin-Storage-Token", required = false) String token) {
        if (!authorized(token)) {
            return unauthorized();
        }
        FileRecord record = service.putFile(pluginKey, scope, scopeId, ref, data, contentType);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ref", record.ref());
        body.put("size", record.sizeBytes());
        body.put("sha256", record.sha256());
        body.put("contentType", record.contentType());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{pluginKey}/{scope}/{scopeId}/files/entry")
    public ResponseEntity<byte[]> getFile(
            @PathVariable String pluginKey,
            @PathVariable String scope,
            @PathVariable String scopeId,
            @RequestParam("ref") String ref,
            @RequestHeader(value = "X-Plugin-Storage-Token", required = false) String token) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        byte[] data = service.readFile(pluginKey, scope, scopeId, ref);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_LENGTH, Integer.toString(data.length))
                .body(data);
    }

    @DeleteMapping("/{pluginKey}/{scope}/{scopeId}/files/entry")
    public ResponseEntity<Map<String, Object>> deleteFile(
            @PathVariable String pluginKey,
            @PathVariable String scope,
            @PathVariable String scopeId,
            @RequestParam("ref") String ref,
            @RequestHeader(value = "X-Plugin-Storage-Token", required = false) String token) {
        if (!authorized(token)) {
            return unauthorized();
        }
        boolean deleted = service.deleteFile(pluginKey, scope, scopeId, ref);
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    @GetMapping("/{pluginKey}/{scope}/{scopeId}/files/keys")
    public ResponseEntity<List<Map<String, Object>>> listFiles(
            @PathVariable String pluginKey,
            @PathVariable String scope,
            @PathVariable String scopeId,
            @RequestParam(value = "prefix", required = false) String prefix,
            @RequestParam(value = "limit", defaultValue = "1000") int limit,
            @RequestHeader(value = "X-Plugin-Storage-Token", required = false) String token) {
        if (!authorized(token)) {
            return unauthorized();
        }
        List<Map<String, Object>> body =
                service.listFiles(pluginKey, scope, scopeId, prefix, limit).stream()
                        .map(
                                record -> {
                                    Map<String, Object> item = new LinkedHashMap<>();
                                    item.put("ref", record.ref());
                                    item.put("size", record.sizeBytes());
                                    item.put("sha256", record.sha256());
                                    item.put("contentType", record.contentType());
                                    return item;
                                })
                        .toList();
        return ResponseEntity.ok(body);
    }

    // ============================================================
    // 锁
    // ============================================================

    @PostMapping("/{pluginKey}/{scope}/{scopeId}/locks/acquire")
    public ResponseEntity<Map<String, Object>> acquireLock(
            @PathVariable String pluginKey,
            @PathVariable String scope,
            @PathVariable String scopeId,
            @RequestParam("name") String name,
            @RequestParam(value = "ttlMs", required = false) Long ttlMs,
            @RequestHeader(value = "X-Plugin-Storage-Token", required = false) String token) {
        if (!authorized(token)) {
            return unauthorized();
        }
        String lockKey = lockKey(pluginKey, scope, scopeId, name);
        String holder = locks.tryAcquire(lockKey, ttlMs);
        if (holder == null) {
            // 423 Locked：语义就是"资源被占用"，调用方据此决定重试或放弃
            return ResponseEntity.status(HttpStatus.LOCKED).body(Map.of("acquired", false));
        }
        return ResponseEntity.ok(Map.of("acquired", true, "token", holder));
    }

    @PostMapping("/{pluginKey}/{scope}/{scopeId}/locks/release")
    public ResponseEntity<Map<String, Object>> releaseLock(
            @PathVariable String pluginKey,
            @PathVariable String scope,
            @PathVariable String scopeId,
            @RequestParam("name") String name,
            @RequestParam("token") String holderToken,
            @RequestHeader(value = "X-Plugin-Storage-Token", required = false) String token) {
        if (!authorized(token)) {
            return unauthorized();
        }
        String lockKey = lockKey(pluginKey, scope, scopeId, name);
        boolean released = locks.release(lockKey, holderToken);
        return ResponseEntity.ok(Map.of("released", released));
    }

    // ============================================================
    // 错误映射
    // ============================================================

    @ExceptionHandler(PluginStorageException.InvalidRequest.class)
    public ResponseEntity<Map<String, Object>> onInvalid(PluginStorageException.InvalidRequest e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(PluginStorageException.Conflict.class)
    public ResponseEntity<Map<String, Object>> onConflict(PluginStorageException.Conflict e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(PluginStorageException.NotFound.class)
    public ResponseEntity<Map<String, Object>> onMissing(PluginStorageException.NotFound e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    // ============================================================
    // 内部工具
    // ============================================================

    private boolean authorized(String token) {
        return properties.controlToken().equals(token);
    }

    private static <T> ResponseEntity<T> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    /**
     * 状态出参。
     *
     * <p>值在库里是 JSON 文本，出参必须把它还原成 JSON 结构再序列化。直接塞字符串的话， 插件拿到的会是 {@code "123"} 而不是 {@code 123} ——
     * 一个只在数字和布尔上才会暴露的类型漂移。
     */
    private Map<String, Object> stateBody(StateRecord record) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", record.key());
        body.put("value", objectMapper.readTree(record.valueJson()));
        body.put("version", record.version());
        body.put("expiresAt", record.expiresAtMillis());
        return body;
    }

    /**
     * 锁键的组成。
     *
     * <p>在作用域三元组之后再加锁名，锁的命名空间因此和工作流、鉴权等其他 Redis 用途自然隔离： 不同插件、不同连接的同名锁不会互相干扰。
     */
    private static String lockKey(String pluginKey, String scope, String scopeId, String name) {
        return pluginKey + ":" + scope + ":" + scopeId + ":" + name;
    }
}
