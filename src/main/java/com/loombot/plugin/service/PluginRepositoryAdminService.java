package com.loombot.plugin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.loombot.plugin.PluginProperties;
import com.loombot.plugin.PluginRepoDefinition;
import com.loombot.plugin.domain.Plugin;
import com.loombot.plugin.domain.PluginCapability;
import com.loombot.plugin.domain.PluginConnectionType;
import com.loombot.plugin.domain.PluginDependency;
import com.loombot.plugin.domain.PluginNode;
import com.loombot.plugin.domain.PluginRepository;
import com.loombot.plugin.domain.PluginVersion;
import com.loombot.plugin.dto.PluginRepoRequest;
import com.loombot.plugin.dto.PluginRepoResponse;
import com.loombot.plugin.event.PluginNodesChangedEvent;
import com.loombot.plugin.event.PluginVersionsRemovedEvent;
import com.loombot.plugin.mapper.PluginCapabilityMapper;
import com.loombot.plugin.mapper.PluginConnectionTypeMapper;
import com.loombot.plugin.mapper.PluginDependencyMapper;
import com.loombot.plugin.mapper.PluginMapper;
import com.loombot.plugin.mapper.PluginNodeMapper;
import com.loombot.plugin.mapper.PluginRepositoryMapper;
import com.loombot.plugin.mapper.PluginVersionMapper;
import com.loombot.plugin.sync.PluginRepoDiscovery;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

/**
 * 插件库的后台管理：列出、声明、上传配套文件、删除、立即同步。
 *
 * <p>**文件夹仍然是唯一真相。** 这里只是把"手工往 `python/plugins/` 放文件夹"这件事搬到后台：
 * 写/删的就是那套文件夹，所以手工放的库照样有效，也不新增表、不搞"数据库声明 + 物化目录"那套双真相。
 *
 * <p>安全边界：上传 `scanner.py` 等于让调用方在服务器上执行任意 Python。这和现有信任模型一致
 * （插件代码本来就按受信任代码处理、不做沙箱），但入口的性质从"有文件系统权限"变成了"有后台权限"， 所以接口挂在独立的 `plugin:repo:manage`
 * 权限上，且解包严格限制在库文件夹内、不许碰工作副本 `repo/`。
 */
@Service
public class PluginRepositoryAdminService {

    private static final Logger log = LoggerFactory.getLogger(PluginRepositoryAdminService.class);

    private static final String MANIFEST = "repo.json";
    private static final String WORKING_COPY = "repo";

    /** 库 key 同时当文件夹名用，所以限死字符集，顺带挡住路径穿越。 */
    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /** 已知子扫描器：内置的 `loombot`，或 `local`（用库文件夹里的 scanner.py）。 */
    private static final Set<String> SCANNERS = Set.of("loombot", "local");

    private static final int MAX_ENTRIES = 200;
    private static final long MAX_UNPACKED_BYTES = 5L * 1024 * 1024;

    private final PluginProperties properties;
    private final PluginRepoDiscovery discovery;
    private final PluginSyncService syncService;
    private final PluginRepositoryMapper repositoryMapper;
    private final PluginMapper pluginMapper;
    private final PluginVersionMapper versionMapper;
    private final PluginNodeMapper nodeMapper;
    private final PluginCapabilityMapper capabilityMapper;
    private final PluginConnectionTypeMapper connectionTypeMapper;
    private final PluginDependencyMapper dependencyMapper;
    private final ApplicationEventPublisher events;
    private final ObjectMapper objectMapper;

    public PluginRepositoryAdminService(
            PluginProperties properties,
            PluginRepoDiscovery discovery,
            PluginSyncService syncService,
            PluginRepositoryMapper repositoryMapper,
            PluginMapper pluginMapper,
            PluginVersionMapper versionMapper,
            PluginNodeMapper nodeMapper,
            PluginCapabilityMapper capabilityMapper,
            PluginConnectionTypeMapper connectionTypeMapper,
            PluginDependencyMapper dependencyMapper,
            ApplicationEventPublisher events,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.discovery = discovery;
        this.syncService = syncService;
        this.repositoryMapper = repositoryMapper;
        this.pluginMapper = pluginMapper;
        this.versionMapper = versionMapper;
        this.nodeMapper = nodeMapper;
        this.capabilityMapper = capabilityMapper;
        this.connectionTypeMapper = connectionTypeMapper;
        this.dependencyMapper = dependencyMapper;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    /** 列出根目录下的每个库。文件夹就是真相，所以没有文件夹的库不会出现在这里。 */
    public List<PluginRepoResponse> list() {
        Path root = pluginsRoot();
        Map<String, PluginRepository> states = statesByKey();
        List<PluginRepoResponse> result = new ArrayList<>();
        if (Files.isDirectory(root)) {
            try (Stream<Path> entries = Files.list(root)) {
                for (Path folder : entries.filter(Files::isDirectory).sorted().toList()) {
                    if (!Files.isRegularFile(folder.resolve(MANIFEST))) {
                        continue;
                    }
                    result.add(describe(folder, states));
                }
            } catch (IOException e) {
                throw new IllegalStateException("读取插件库根目录失败: " + root, e);
            }
        }
        return result;
    }

    /** 新建或更新声明。已存在同名 key 的库沿用它的文件夹，不搬家。 */
    public PluginRepoResponse save(String key, PluginRepoRequest request) {
        String cleanKey = requireKey(key);
        String scanner = normalizeScanner(request == null ? null : request.scanner());
        String branch = normalizeBranch(request == null ? null : request.branch());
        String url = request == null || request.url() == null ? "" : request.url().strip();
        Path folder = findFolderByKey(cleanKey).orElseGet(() -> folderFor(cleanKey));
        try {
            Files.createDirectories(folder);
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("key", cleanKey);
            manifest.put("url", url);
            manifest.put("branch", branch);
            manifest.put("scanner", scanner);
            objectMapper
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(folder.resolve(MANIFEST).toFile(), manifest);
        } catch (IOException e) {
            throw new IllegalStateException("写入 repo.json 失败: " + e.getMessage(), e);
        }
        log.info("插件库声明已写入: key={} folder={} scanner={}", cleanKey, folder, scanner);
        return describe(folder, statesByKey());
    }

    /** 上传库文件夹的配套文件：一个 zip，或单个 `.py`（如 `scanner.py`）。 */
    public PluginRepoResponse upload(String key, MultipartFile file) {
        String cleanKey = requireKey(key);
        Path folder =
                findFolderByKey(cleanKey)
                        .orElseThrow(
                                () -> new IllegalArgumentException("插件库不存在，请先保存声明: " + cleanKey));
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }
        String name = fileName(file);
        String lower = name.toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".zip")) {
                extractZip(folder, file);
            } else if (lower.endsWith(".py")) {
                writeSingleFile(folder, name, file);
            } else {
                throw new IllegalArgumentException("只接受 .zip（库配套文件）或单个 .py（如 scanner.py）");
            }
        } catch (IOException e) {
            throw new IllegalStateException("写入配套文件失败: " + e.getMessage(), e);
        }
        log.info("插件库配套文件已上传: key={} file={}", cleanKey, name);
        return describe(folder, statesByKey());
    }

    /**
     * 删掉库文件夹和它的同步状态行。
     *
     * <p>删除前先把整个库的节点按"全部移除"发事件：工作流侧保留身份快照并标失效，连接侧 清理绑定这些版本的连接。然后插件目录行、版本行和同步状态行一起物理删除，数据库只保留
     * "当前确实存在"的插件数据。
     */
    @Transactional
    public void delete(String key) {
        String cleanKey = requireKey(key);
        Optional<Path> folder = findFolderByKey(cleanKey);
        // 必须先摘插件再删状态行：摘的时候要按 repoKey 找到仓库行，才知道它下面有哪些插件。
        detachPlugins(cleanKey);
        if (folder.isPresent()) {
            try {
                deleteRecursively(folder.get());
            } catch (IOException e) {
                throw new IllegalStateException("删除插件库文件夹失败: " + e.getMessage(), e);
            }
            log.info("插件库文件夹已删除: key={} folder={}", cleanKey, folder.get());
        } else {
            log.info("插件库文件夹已不存在，只清理同步状态行: key={}", cleanKey);
        }
        removeState(cleanKey);
    }

    /** 删掉同步状态行；没有就什么都不做（重复删除不该报错）。 */
    private void removeState(String repoKey) {
        repositoryMapper.delete(
                new LambdaQueryWrapper<PluginRepository>()
                        .eq(PluginRepository::getRepoKey, repoKey));
    }

    /**
     * 异步触发一次同步。
     *
     * <p>不在这里同步等结果：首次 clone 要走网络，HTTP 请求不该被它拖住。同步本身有 CAS 锁， 和定时任务天然互斥；进度看 {@link #list()} 里的
     * lastScanTime / lastError。
     */
    @Async
    public void triggerSync() {
        try {
            syncService.sync();
        } catch (Exception e) {
            log.warn("插件库同步失败: {}", e.getMessage(), e);
        }
    }

    private PluginRepoResponse describe(Path folder, Map<String, PluginRepository> states) {
        try {
            PluginRepoDefinition definition = discovery.readDefinition(folder);
            PluginRepository state = states.get(definition.key());
            return new PluginRepoResponse(
                    definition.key(),
                    folder.getFileName().toString(),
                    definition.url(),
                    definition.branch(),
                    definition.scanner(),
                    true,
                    null,
                    state == null ? null : state.getLocalPath(),
                    state == null ? null : state.getLastCommitHash(),
                    state == null ? null : state.getLastPullTime(),
                    state == null ? null : state.getLastScanTime(),
                    state == null ? null : state.getLastError());
        } catch (RuntimeException e) {
            // 声明坏了必须显示出来：这种库会被同步器直接跳过，藏起来只会让人以为配好了。
            return new PluginRepoResponse(
                    null,
                    folder.getFileName().toString(),
                    null,
                    null,
                    null,
                    false,
                    e.getMessage(),
                    null,
                    null,
                    null,
                    null,
                    null);
        }
    }

    /** 按 key 找已有的库文件夹：key 是身份，文件夹名只是外壳。 */
    private Optional<Path> findFolderByKey(String key) {
        Path root = pluginsRoot();
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }
        try (Stream<Path> entries = Files.list(root)) {
            for (Path folder : entries.filter(Files::isDirectory).sorted().toList()) {
                if (!Files.isRegularFile(folder.resolve(MANIFEST))) {
                    continue;
                }
                try {
                    if (key.equals(discovery.readDefinition(folder).key())) {
                        return Optional.of(folder);
                    }
                } catch (RuntimeException e) {
                    log.debug("跳过无法解析的库声明: {} ({})", folder, e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取插件库根目录失败: " + root, e);
        }
        return Optional.empty();
    }

    /** 通知依赖方并删除这个库的插件目录数据。 */
    private void detachPlugins(String repoKey) {
        PluginRepository state =
                repositoryMapper.selectOne(
                        new LambdaQueryWrapper<PluginRepository>()
                                .eq(PluginRepository::getRepoKey, repoKey));
        if (state == null) {
            return;
        }
        List<Plugin> plugins =
                pluginMapper.selectList(
                        new LambdaQueryWrapper<Plugin>()
                                .eq(Plugin::getRepositoryId, state.getId()));
        if (plugins.isEmpty()) {
            return;
        }
        List<Long> pluginIds = plugins.stream().map(Plugin::getId).toList();
        List<PluginVersion> versions =
                versionMapper.selectList(
                        new LambdaQueryWrapper<PluginVersion>()
                                .in(PluginVersion::getPluginId, pluginIds));
        Map<Long, String> keyByPluginId = new HashMap<>();
        plugins.forEach(plugin -> keyByPluginId.put(plugin.getId(), plugin.getPluginKey()));
        for (PluginVersion version : versions) {
            List<PluginNodesChangedEvent.NodeChange> removedNodes =
                    nodeMapper
                            .selectList(
                                    new LambdaQueryWrapper<PluginNode>()
                                            .eq(PluginNode::getPluginVersionId, version.getId()))
                            .stream()
                            .map(
                                    row ->
                                            new PluginNodesChangedEvent.NodeChange(
                                                    row.getNodeKey(),
                                                    row.getName(),
                                                    row.getNodeType(),
                                                    row.getConnectionType()))
                            .toList();
            if (removedNodes.isEmpty()) {
                continue;
            }
            events.publishEvent(
                    new PluginNodesChangedEvent(
                            version.getId(),
                            keyByPluginId.get(version.getPluginId()),
                            version.getVersion(),
                            List.of(),
                            removedNodes));
        }
        // 整个库下架时，绑定在这些版本上的连接也要清掉。
        if (!versions.isEmpty()) {
            events.publishEvent(
                    new PluginVersionsRemovedEvent(
                            versions.stream().map(PluginVersion::getId).toList()));
        }

        List<Long> versionIds = versions.stream().map(PluginVersion::getId).toList();
        if (!versionIds.isEmpty()) {
            capabilityMapper.delete(
                    new LambdaQueryWrapper<PluginCapability>()
                            .in(PluginCapability::getPluginVersionId, versionIds));
            connectionTypeMapper.delete(
                    new LambdaQueryWrapper<PluginConnectionType>()
                            .in(PluginConnectionType::getPluginVersionId, versionIds));
            nodeMapper.delete(
                    new LambdaQueryWrapper<PluginNode>()
                            .in(PluginNode::getPluginVersionId, versionIds));
            dependencyMapper.delete(
                    new LambdaQueryWrapper<PluginDependency>()
                            .in(PluginDependency::getPluginVersionId, versionIds));
            versionMapper.delete(
                    new LambdaQueryWrapper<PluginVersion>().in(PluginVersion::getId, versionIds));
        }
        pluginMapper.delete(new LambdaQueryWrapper<Plugin>().in(Plugin::getId, pluginIds));
        log.info(
                "插件库目录数据已清空: repoKey={} plugins={} versions={}",
                repoKey,
                pluginIds.size(),
                versionIds.size());
    }

    private void extractZip(Path folder, MultipartFile file) throws IOException {
        int count = 0;
        long total = 0;
        try (ZipInputStream zip =
                new ZipInputStream(file.getInputStream(), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                count++;
                if (count > MAX_ENTRIES) {
                    throw new IllegalArgumentException("压缩包文件数超过上限 " + MAX_ENTRIES);
                }
                Path target = resolveEntry(folder, entry.getName());
                byte[] bytes = zip.readAllBytes();
                total += bytes.length;
                if (total > MAX_UNPACKED_BYTES) {
                    throw new IllegalArgumentException("压缩包解压后超过 " + MAX_UNPACKED_BYTES + " 字节");
                }
                Files.createDirectories(target.getParent());
                Files.write(target, bytes);
            }
        }
        if (count == 0) {
            throw new IllegalArgumentException("压缩包里没有文件");
        }
    }

    /** 把压缩包里的一个条目映射到库文件夹内的安全路径，越界就拒绝。 */
    private Path resolveEntry(Path folder, String rawName) {
        String name = rawName == null ? "" : rawName.replace('\\', '/').strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("压缩包里存在空文件名");
        }
        if (name.startsWith("/") || name.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("压缩包里存在绝对路径: " + rawName);
        }
        Path target = folder.resolve(name).normalize();
        if (!target.startsWith(folder)) {
            throw new IllegalArgumentException("压缩包里的路径越界: " + rawName);
        }
        Path relative = folder.relativize(target);
        if (relative.getNameCount() == 0) {
            throw new IllegalArgumentException("压缩包里的路径非法: " + rawName);
        }
        String first = relative.getName(0).toString();
        if (WORKING_COPY.equals(first)) {
            throw new IllegalArgumentException("不允许覆盖工作副本目录 repo/: " + rawName);
        }
        if (MANIFEST.equals(relative.toString().replace('\\', '/'))) {
            throw new IllegalArgumentException("repo.json 由表单生成，请不要放进压缩包");
        }
        return target;
    }

    private void writeSingleFile(Path folder, String name, MultipartFile file) throws IOException {
        if (MANIFEST.equals(name)) {
            throw new IllegalArgumentException("repo.json 由表单生成，不要单独上传");
        }
        Files.write(folder.resolve(name), file.getBytes());
    }

    private static void deleteRecursively(Path target) throws IOException {
        if (!Files.exists(target)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(target)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private Map<String, PluginRepository> statesByKey() {
        Map<String, PluginRepository> states = new LinkedHashMap<>();
        for (PluginRepository row : repositoryMapper.selectList(null)) {
            states.put(row.getRepoKey(), row);
        }
        return states;
    }

    private Path pluginsRoot() {
        return Path.of(properties.pluginsRoot()).toAbsolutePath().normalize();
    }

    private Path folderFor(String key) {
        Path root = pluginsRoot();
        Path folder = root.resolve(key).normalize();
        if (!folder.startsWith(root)) {
            throw new IllegalArgumentException("插件库 key 非法: " + key);
        }
        return folder;
    }

    private static String requireKey(String key) {
        String value = key == null ? "" : key.strip();
        if (!KEY_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("插件库 key 只能包含字母、数字、点、下划线和短横线，且不超过 64 个字符: " + key);
        }
        return value;
    }

    private static String normalizeScanner(String scanner) {
        String value = scanner == null || scanner.isBlank() ? "loombot" : scanner.strip();
        if (!SCANNERS.contains(value)) {
            throw new IllegalArgumentException(
                    "子扫描器只支持 " + String.join(" / ", SCANNERS) + "，收到: " + scanner);
        }
        return value;
    }

    private static String normalizeBranch(String branch) {
        return branch == null || branch.isBlank() ? "main" : branch.strip();
    }

    private static String fileName(MultipartFile file) {
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) {
            throw new IllegalArgumentException("上传文件缺少文件名");
        }
        Path name = Path.of(original).getFileName();
        return name == null ? "" : name.toString();
    }
}
