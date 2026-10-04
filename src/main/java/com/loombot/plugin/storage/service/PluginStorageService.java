package com.loombot.plugin.storage.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.loombot.plugin.storage.PluginStorageException;
import com.loombot.plugin.storage.PluginStorageProperties;
import com.loombot.plugin.storage.domain.PluginFile;
import com.loombot.plugin.storage.domain.PluginState;
import com.loombot.plugin.storage.mapper.PluginFileMapper;
import com.loombot.plugin.storage.mapper.PluginStateMapper;
import com.loombot.plugin.storage.store.ObjectStorage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 插件持久化的读写实现。
 *
 * <h2>职责边界</h2>
 *
 * <p>结构化状态和文件元数据的真相在 MySQL；文件内容在 {@link ObjectStorage}；跨进程互斥在 {@link PluginLockService} 的 Redis
 * 上。三者不重叠，是为了让"某个东西坏了"时影响面清晰： Redis 挂了只影响锁，不影响已经写入的数据。
 *
 * <h2>为什么 CAS 用行锁而不是版本号的裸 UPDATE</h2>
 *
 * <p>在 SQL 里用 {@code WHERE version = ?} 也可以实现乐观锁，但调用方拿到的是"影响 0 行"这个含糊结果，
 * 还得再查一次才知道是"不存在"还是"版本不对"。行锁加 Java 侧比对，冲突时能直接给出期望值和当前值， 排查日志时一眼能看懂。
 */
@Service
public class PluginStorageService {

    private static final Logger log = LoggerFactory.getLogger(PluginStorageService.class);

    private static final Pattern KNOWN_SCOPE = Pattern.compile("^(PLUGIN|CONNECTION|EPHEMERAL)$");

    /** 作用域标识（插件 key、连接 id）的长度上限，与表结构一致。 */
    private static final int MAX_PART_LENGTH = 160;

    /** key 的长度上限，与 {@code plugin_state.state_key} 的列宽一致。 */
    private static final int MAX_KEY_LENGTH = 255;

    /** 扫描上限。防止一次请求把整张表捞回来。 */
    private static final int MAX_SCAN_LIMIT = 1000;

    /** 对象键的目录分片长度，避免同一插件下所有对象挤在一个目录里。 */
    private static final int SHARD_LENGTH = 2;

    /**
     * 一条结构化状态。
     *
     * @param key 逻辑 key
     * @param valueJson 值的 JSON 文本
     * @param version 乐观锁版本号
     * @param expiresAtMillis 过期时间（epoch 毫秒）；{@code null} 表示永不过期
     */
    public record StateRecord(String key, String valueJson, long version, Long expiresAtMillis) {}

    /**
     * 一条文件元数据。
     *
     * @param ref 插件侧的逻辑引用
     * @param sizeBytes 字节数
     * @param sha256 内容哈希
     * @param contentType 内容类型；插件没给就是 {@code null}
     */
    public record FileRecord(String ref, long sizeBytes, String sha256, String contentType) {}

    private final PluginStateMapper stateMapper;
    private final PluginFileMapper fileMapper;
    private final ObjectStorage objectStorage;
    private final PluginStorageProperties properties;

    public PluginStorageService(
            PluginStateMapper stateMapper,
            PluginFileMapper fileMapper,
            ObjectStorage objectStorage,
            PluginStorageProperties properties) {
        this.stateMapper = stateMapper;
        this.fileMapper = fileMapper;
        this.objectStorage = objectStorage;
        this.properties = properties;
    }

    // ============================================================
    // 结构化状态
    // ============================================================

    public Optional<StateRecord> getState(
            String pluginKey, String scope, String scopeId, String key) {
        Scope parts = Scope.of(pluginKey, scope, scopeId);
        String normalizedKey = requireKey(key);
        PluginState row =
                stateMapper.selectOne(
                        parts.pluginKey(), parts.scope(), parts.scopeId(), normalizedKey);
        if (row == null) {
            return Optional.empty();
        }
        if (isExpired(row)) {
            // 惰性删除：过期数据不返回，顺手清掉。定时清理只是兜底，不靠它保证正确性。
            stateMapper.deleteOne(parts.pluginKey(), parts.scope(), parts.scopeId(), normalizedKey);
            return Optional.empty();
        }
        return Optional.of(toRecord(row));
    }

    /**
     * 写一条状态并返回新版本号。
     *
     * @param expectedVersion {@code null} 表示不做版本校验；{@code 0} 表示只允许新建；其他值必须与当前版本一致
     * @param ttlSeconds 过期秒数；{@code null} 表示永不过期
     */
    @Transactional
    public StateRecord putState(
            String pluginKey,
            String scope,
            String scopeId,
            String key,
            String valueJson,
            Double ttlSeconds,
            Long expectedVersion) {
        Scope parts = Scope.of(pluginKey, scope, scopeId);
        String normalizedKey = requireKey(key);
        if (valueJson == null) {
            throw new PluginStorageException.InvalidRequest("状态值不能为空");
        }

        PluginState row =
                stateMapper.selectForUpdate(
                        parts.pluginKey(), parts.scope(), parts.scopeId(), normalizedKey);
        // 已经过期的行按"不存在"处理：它的版本号不该再被续用。
        long currentVersion = row == null || isExpired(row) ? 0L : row.getValueVersion();
        if (expectedVersion != null && expectedVersion != currentVersion) {
            throw new PluginStorageException.Conflict(
                    "存储版本冲突: key="
                            + normalizedKey
                            + " 期望="
                            + expectedVersion
                            + " 当前="
                            + currentVersion);
        }

        long nextVersion = currentVersion + 1;
        LocalDateTime expiresAt = expiresAtFrom(ttlSeconds);
        PluginState target = row == null || isExpired(row) ? new PluginState() : row;
        target.setPluginKey(parts.pluginKey());
        target.setScope(parts.scope());
        target.setScopeId(parts.scopeId());
        target.setStateKey(normalizedKey);
        target.setValueJson(valueJson);
        target.setValueVersion(nextVersion);
        target.setExpiresAt(expiresAt);
        if (row == null || isExpired(row)) {
            stateMapper.insert(target);
        } else {
            stateMapper.update(target);
        }
        return new StateRecord(normalizedKey, valueJson, nextVersion, toMillis(expiresAt));
    }

    /** 删除一条状态。{@code expectedVersion} 语义与 {@link #putState} 相同。 */
    @Transactional
    public boolean deleteState(
            String pluginKey, String scope, String scopeId, String key, Long expectedVersion) {
        Scope parts = Scope.of(pluginKey, scope, scopeId);
        String normalizedKey = requireKey(key);
        PluginState row =
                stateMapper.selectForUpdate(
                        parts.pluginKey(), parts.scope(), parts.scopeId(), normalizedKey);
        if (row == null || isExpired(row)) {
            return false;
        }
        if (expectedVersion != null && expectedVersion != row.getValueVersion()) {
            throw new PluginStorageException.Conflict(
                    "存储版本冲突: key="
                            + normalizedKey
                            + " 期望="
                            + expectedVersion
                            + " 当前="
                            + row.getValueVersion());
        }
        stateMapper.deleteOne(parts.pluginKey(), parts.scope(), parts.scopeId(), normalizedKey);
        return true;
    }

    /** 按前缀扫描，返回值带版本号，供调用方做后续 CAS。 */
    public List<StateRecord> listState(
            String pluginKey, String scope, String scopeId, String prefix, int limit) {
        Scope parts = Scope.of(pluginKey, scope, scopeId);
        String normalizedPrefix = prefix == null || prefix.isBlank() ? "" : requireKey(prefix);
        List<PluginState> rows =
                stateMapper.selectByPrefix(
                        parts.pluginKey(),
                        parts.scope(),
                        parts.scopeId(),
                        likePrefix(normalizedPrefix),
                        clampLimit(limit));
        List<StateRecord> result = new ArrayList<>(rows.size());
        LocalDateTime now = LocalDateTime.now();
        for (PluginState row : rows) {
            if (row.getExpiresAt() != null && !row.getExpiresAt().isAfter(now)) {
                continue;
            }
            result.add(toRecord(row));
        }
        return result;
    }

    // ============================================================
    // 文件
    // ============================================================

    /**
     * 写入文件内容与元数据。
     *
     * <p>顺序是"先对象存储、后 MySQL"：反过来会出现"元数据说文件在、实际读不到"的窗口， 而先写内容只可能留下一个没人引用的孤儿对象。前者对插件是硬故障，后者只是待清理的垃圾。
     */
    @Transactional
    public FileRecord putFile(
            String pluginKey,
            String scope,
            String scopeId,
            String ref,
            byte[] data,
            String contentType) {
        Scope parts = Scope.of(pluginKey, scope, scopeId);
        String normalizedRef = requireFileRef(ref);
        byte[] payload = data == null ? new byte[0] : data;
        if (payload.length > properties.maxFileBytes()) {
            throw new PluginStorageException.InvalidRequest(
                    "文件超过上限: " + payload.length + " > " + properties.maxFileBytes());
        }
        String digest = sha256Hex(payload);
        String objectKey =
                objectKey(parts.pluginKey(), parts.scope(), parts.scopeId(), normalizedRef);
        String resolvedContentType =
                contentType == null || contentType.isBlank() ? null : contentType.strip();
        objectStorage.put(objectKey, payload, resolvedContentType);

        PluginFile existing =
                fileMapper.selectOne(
                        parts.pluginKey(), parts.scope(), parts.scopeId(), normalizedRef);
        if (existing == null) {
            PluginFile row = new PluginFile();
            row.setId(IdWorker.getId());
            row.setPluginKey(parts.pluginKey());
            row.setScope(parts.scope());
            row.setScopeId(parts.scopeId());
            row.setFileRef(normalizedRef);
            row.setObjectKey(objectKey);
            row.setContentType(resolvedContentType);
            row.setSizeBytes((long) payload.length);
            row.setSha256(digest);
            fileMapper.insert(row);
        } else {
            existing.setObjectKey(objectKey);
            existing.setContentType(resolvedContentType);
            existing.setSizeBytes((long) payload.length);
            existing.setSha256(digest);
            fileMapper.updateContent(existing);
        }
        return new FileRecord(normalizedRef, payload.length, digest, resolvedContentType);
    }

    /** 读取文件内容。引用不存在和对象丢失都按不存在处理，调用方看到的行为一致。 */
    public byte[] readFile(String pluginKey, String scope, String scopeId, String ref) {
        Scope parts = Scope.of(pluginKey, scope, scopeId);
        String normalizedRef = requireFileRef(ref);
        PluginFile row =
                fileMapper.selectOne(
                        parts.pluginKey(), parts.scope(), parts.scopeId(), normalizedRef);
        if (row == null) {
            throw new PluginStorageException.NotFound("文件不存在: " + normalizedRef);
        }
        try {
            return objectStorage.get(row.getObjectKey());
        } catch (ObjectStorage.ObjectNotFoundException e) {
            // 元数据在、内容不在，说明对象被外部删掉了。这是数据不一致，必须留痕。
            log.error(
                    "插件文件元数据存在但对象缺失: plugin={} scope={} scopeId={} ref={} objectKey={}",
                    parts.pluginKey(),
                    parts.scope(),
                    parts.scopeId(),
                    normalizedRef,
                    row.getObjectKey());
            throw new PluginStorageException.NotFound("文件不存在: " + normalizedRef);
        }
    }

    /** 删除文件。先删元数据再删内容：元数据是索引，索引先消失才不会指向已被删掉的对象。 */
    @Transactional
    public boolean deleteFile(String pluginKey, String scope, String scopeId, String ref) {
        Scope parts = Scope.of(pluginKey, scope, scopeId);
        String normalizedRef = requireFileRef(ref);
        PluginFile row =
                fileMapper.selectOne(
                        parts.pluginKey(), parts.scope(), parts.scopeId(), normalizedRef);
        if (row == null) {
            return false;
        }
        fileMapper.deleteOne(parts.pluginKey(), parts.scope(), parts.scopeId(), normalizedRef);
        objectStorage.delete(row.getObjectKey());
        return true;
    }

    public List<FileRecord> listFiles(
            String pluginKey, String scope, String scopeId, String prefix, int limit) {
        Scope parts = Scope.of(pluginKey, scope, scopeId);
        String normalizedPrefix = prefix == null || prefix.isBlank() ? "" : requireFileRef(prefix);
        List<PluginFile> rows =
                fileMapper.selectByPrefix(
                        parts.pluginKey(),
                        parts.scope(),
                        parts.scopeId(),
                        likePrefix(normalizedPrefix),
                        clampLimit(limit));
        List<FileRecord> result = new ArrayList<>(rows.size());
        for (PluginFile row : rows) {
            result.add(
                    new FileRecord(
                            row.getFileRef(),
                            row.getSizeBytes(),
                            row.getSha256(),
                            row.getContentType()));
        }
        return result;
    }

    /** 定时清掉过期的状态行。过期行在读取时已不可见，这里只是不让垃圾堆积。 */
    @Scheduled(fixedDelayString = "${loombot.plugin-storage.purge-interval:10m}")
    public void purgeExpiredState() {
        int removed = stateMapper.purgeExpired(LocalDateTime.now());
        if (removed > 0) {
            log.info("已清理过期插件状态: 数量={}", removed);
        }
    }

    // ============================================================
    // 内部工具
    // ============================================================

    /** 已校验过的作用域三元组。构造成功即代表参数合法，后续方法不必重复校验。 */
    private record Scope(String pluginKey, String scope, String scopeId) {

        static Scope of(String pluginKey, String scope, String scopeId) {
            String normalizedScope = scope == null ? "" : scope.strip().toUpperCase();
            if (!KNOWN_SCOPE.matcher(normalizedScope).matches()) {
                throw new PluginStorageException.InvalidRequest("未知存储作用域: " + scope);
            }
            return new Scope(
                    requirePart(pluginKey, "pluginKey"),
                    normalizedScope,
                    scopeId == null || scopeId.isBlank() ? "0" : requirePart(scopeId, "scopeId"));
        }
    }

    private static String requirePart(String value, String field) {
        String text = value == null ? "" : value.strip();
        if (!isSafePart(text)) {
            throw new PluginStorageException.InvalidRequest(
                    field + " 不能为空、不能超过 160 字符，也不能包含路径分隔符、`..` 或控制字符");
        }
        return text;
    }

    private static String requireKey(String key) {
        String text = key == null ? "" : key.strip();
        if (!isSafeKey(text)) {
            throw new PluginStorageException.InvalidRequest(
                    "key 不能为空、不能超过 255 字符，也不能以斜杠开头或包含 `..`、纯点、控制字符");
        }
        return text;
    }

    /**
     * 作用域标识（插件 key、连接 id、锁名）的合法性。
     *
     * <p>刻意不维护字符白名单：插件 key 来自插件库的目录名，中文、日文、西里尔字母、emoji 都应当 原样可用，只挡真正会造成问题的东西 —— 路径分隔符、{@code
     * ..}、纯点、控制字符。维护白名单的代价 是"目录名能建、{@code ctx.storage} 用不了"这种半截兼容，比放宽规则更难查。
     *
     * <p>长度按码点算：Java 的 {@code length()} 数的是 UTF-16 单元，MySQL 的 {@code VARCHAR} 和 Python 的 {@code
     * len()} 数的是字符，用码点才能对齐。
     */
    private static boolean isSafePart(String text) {
        if (text.isEmpty()
                || text.codePointCount(0, text.length()) > MAX_PART_LENGTH
                || text.contains("/")
                || text.contains("\\")
                || text.contains("..")
                || text.strip().chars().allMatch(ch -> ch == '.')) {
            return false;
        }
        return text.codePoints().noneMatch(Character::isISOControl);
    }

    /**
     * KV key 与文件引用的合法性。
     *
     * <p>比作用域标识多允许斜杠，用来表达层级；仍然拒绝 {@code ..}、以斜杠开头和控制字符。
     */
    private static boolean isSafeKey(String text) {
        if (text.isEmpty()
                || text.codePointCount(0, text.length()) > MAX_KEY_LENGTH
                || text.contains("..")
                || text.strip().chars().allMatch(ch -> ch == '.')
                || text.startsWith("/")
                || text.startsWith("\\")) {
            return false;
        }
        return text.codePoints().noneMatch(Character::isISOControl);
    }

    /**
     * 文件引用在 key 的基础上归一化分隔符。
     *
     * <p>反斜杠统一成正斜杠，免得同一个引用在 Windows 和 Linux 上算出两个不同的对象键， 出现"同一份文件在两个平台看到不同内容"的怪事。
     */
    private static String requireFileRef(String ref) {
        return requireKey(ref == null ? "" : ref.replace('\\', '/'));
    }

    /**
     * 拼 LIKE 前缀并转义通配符。
     *
     * <p>转义符选 {@code !} 而不是反斜杠：反斜杠在 MySQL 字符串字面量里还有一层转义， 容易写出"看起来对、实际少一层"的错。转义本身是必需的，因为 key 允许下划线，
     * 不转义的话 {@code keys("user_")} 会连 {@code userX} 一起匹配出来。
     */
    private static String likePrefix(String prefix) {
        StringBuilder builder = new StringBuilder(prefix.length() + 8);
        for (int index = 0; index < prefix.length(); index++) {
            char ch = prefix.charAt(index);
            if (ch == '!' || ch == '%' || ch == '_') {
                builder.append('!');
            }
            builder.append(ch);
        }
        return builder.append('%').toString();
    }

    private static int clampLimit(int limit) {
        if (limit <= 0) {
            return MAX_SCAN_LIMIT;
        }
        return Math.min(limit, MAX_SCAN_LIMIT);
    }

    /**
     * 生成对象键。
     *
     * <p>路径段做字符白名单替换，末尾拼上引用的摘要：即使两个 key 归一化后同名，摘要不同也不会互相覆盖。
     * 摘要是引用的函数而不是内容的函数，所以同一个引用重复写入始终落在同一个对象上，不产生历史垃圾。
     */
    private static String objectKey(
            String pluginKey, String scope, String scopeId, String fileRef) {
        String referenceDigest =
                sha256Hex(
                        (pluginKey + "\n" + scope + "\n" + scopeId + "\n" + fileRef)
                                .getBytes(StandardCharsets.UTF_8));
        return sanitizeSegment(pluginKey)
                + "/"
                + scope
                + "/"
                + sanitizeSegment(scopeId)
                + "/"
                + referenceDigest.substring(0, SHARD_LENGTH)
                + "/"
                + referenceDigest;
    }

    /**
     * 把一段标识压成可以安全当目录名用的形式。
     *
     * <p>保留 Unicode 字母数字，所以 {@code loombot.Warframe裂隙} 在磁盘上还是这个名字，排查时不用
     * 对着一串下划线猜是哪个插件。真正保证唯一性的是对象键末尾的摘要，不是这里的可读部分。
     */
    private static String sanitizeSegment(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        value.codePoints()
                .forEach(
                        ch -> {
                            boolean safe =
                                    Character.isLetterOrDigit(ch)
                                            || ch == '.'
                                            || ch == '_'
                                            || ch == '-';
                            builder.appendCodePoint(safe ? ch : '_');
                        });
        return builder.toString();
    }

    private static boolean isExpired(PluginState row) {
        return row.getExpiresAt() != null && !row.getExpiresAt().isAfter(LocalDateTime.now());
    }

    private static LocalDateTime expiresAtFrom(Double ttlSeconds) {
        if (ttlSeconds == null) {
            return null;
        }
        if (ttlSeconds.isNaN() || ttlSeconds.isInfinite() || ttlSeconds <= 0) {
            throw new PluginStorageException.InvalidRequest("ttl 必须是正数秒数");
        }
        long nanos = (long) (ttlSeconds * 1_000_000_000L);
        return LocalDateTime.now().plusNanos(nanos);
    }

    private static Long toMillis(LocalDateTime value) {
        return value == null
                ? null
                : value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static StateRecord toRecord(PluginState row) {
        return new StateRecord(
                row.getStateKey(),
                row.getValueJson(),
                row.getValueVersion(),
                toMillis(row.getExpiresAt()));
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须提供的算法，走到这里说明运行环境被裁剪过
            throw new IllegalStateException("运行环境缺少 SHA-256", e);
        }
    }
}
