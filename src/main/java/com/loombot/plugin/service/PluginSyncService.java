package com.loombot.plugin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.loombot.plugin.PluginProperties;
import com.loombot.plugin.PluginRepoDefinition;
import com.loombot.plugin.domain.Plugin;
import com.loombot.plugin.domain.PluginCapability;
import com.loombot.plugin.domain.PluginConnectionType;
import com.loombot.plugin.domain.PluginDependency;
import com.loombot.plugin.domain.PluginNode;
import com.loombot.plugin.domain.PluginRepository;
import com.loombot.plugin.domain.PluginVersion;
import com.loombot.plugin.event.PluginNodesChangedEvent;
import com.loombot.plugin.mapper.PluginCapabilityMapper;
import com.loombot.plugin.mapper.PluginConnectionTypeMapper;
import com.loombot.plugin.mapper.PluginDependencyMapper;
import com.loombot.plugin.mapper.PluginMapper;
import com.loombot.plugin.mapper.PluginNodeMapper;
import com.loombot.plugin.mapper.PluginRepositoryMapper;
import com.loombot.plugin.mapper.PluginVersionMapper;
import com.loombot.plugin.sync.PluginCatalogScanner;
import com.loombot.plugin.sync.PluginDependencyInstaller;
import com.loombot.plugin.sync.PluginManifestReader;
import com.loombot.plugin.sync.PluginRepoDiscovery;
import com.loombot.plugin.sync.PluginRepositorySynchronizer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 同步公共插件仓库到本地注册表。
 *
 * <p>元数据同步与运行时加载分离：这里只落库，不 import 插件代码，也不启动适配器。
 */
@Service
public class PluginSyncService {

    private static final Logger log = LoggerFactory.getLogger(PluginSyncService.class);

    /**
     * 节点目录扫描的语义版本。
     *
     * <p>目录由 Python 扫描器生成，扫描规则（参数必填判定、返回字段展开等）属于框架代码。 改这些规则时把它 +1，强制下次启动重扫所有插件版本。
     */
    private static final int CATALOG_VERSION = 2;

    private final PluginProperties properties;
    private final PluginRepoDiscovery discovery;
    private final PluginRepositorySynchronizer repositorySynchronizer;
    private final PluginDependencyInstaller dependencyInstaller;
    private final PluginCatalogScanner catalogScanner;
    private final PluginRepositoryMapper repositoryMapper;
    private final PluginMapper pluginMapper;
    private final PluginVersionMapper versionMapper;
    private final PluginCapabilityMapper capabilityMapper;
    private final PluginConnectionTypeMapper connectionTypeMapper;
    private final PluginNodeMapper nodeMapper;
    private final PluginDependencyMapper dependencyMapper;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher events;
    private final AtomicBoolean syncing = new AtomicBoolean(false);

    public PluginSyncService(
            PluginProperties properties,
            PluginRepoDiscovery discovery,
            PluginRepositorySynchronizer repositorySynchronizer,
            PluginDependencyInstaller dependencyInstaller,
            PluginCatalogScanner catalogScanner,
            PluginRepositoryMapper repositoryMapper,
            PluginMapper pluginMapper,
            PluginVersionMapper versionMapper,
            PluginCapabilityMapper capabilityMapper,
            PluginConnectionTypeMapper connectionTypeMapper,
            PluginNodeMapper nodeMapper,
            PluginDependencyMapper dependencyMapper,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            ApplicationEventPublisher events) {
        this.properties = properties;
        this.discovery = discovery;
        this.repositorySynchronizer = repositorySynchronizer;
        this.dependencyInstaller = dependencyInstaller;
        this.catalogScanner = catalogScanner;
        this.repositoryMapper = repositoryMapper;
        this.pluginMapper = pluginMapper;
        this.versionMapper = versionMapper;
        this.capabilityMapper = capabilityMapper;
        this.connectionTypeMapper = connectionTypeMapper;
        this.nodeMapper = nodeMapper;
        this.dependencyMapper = dependencyMapper;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.events = events;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void syncOnStart() {
        if (!properties.syncOnStart()) {
            log.info("插件仓库启动同步已关闭");
            return;
        }
        try {
            sync();
        } catch (Exception e) {
            log.error("插件仓库同步失败，继续使用上一次成功的注册表: {}", e.getMessage(), e);
        }
    }

    /** 插件目录或远端仓库发生变化时自动重扫，不需要用户在页面上人工操作。 */
    @Scheduled(fixedDelayString = "${loombot.plugin.sync-interval:30s}")
    public void syncAutomatically() {
        if (!properties.autoSync()) {
            return;
        }
        try {
            sync();
        } catch (Exception e) {
            log.warn("插件仓库自动扫描失败，继续使用上一次成功的注册表: {}", e.getMessage());
        }
    }

    public void sync() throws IOException, InterruptedException {
        if (!syncing.compareAndSet(false, true)) {
            log.debug("插件库正在同步，跳过本次触发");
            return;
        }
        try {
            List<PluginRepoDefinition> definitions = discovery.discover();
            if (definitions.isEmpty()) {
                log.debug("没有发现插件库: {}", properties.pluginsRoot());
                return;
            }
            // 一个库失败不该拖垮其它库：错误记在它自己的状态行上，下个周期它自己重试
            for (PluginRepoDefinition definition : definitions) {
                try {
                    syncRepository(definition);
                } catch (IOException | InterruptedException e) {
                    log.warn("插件库同步失败: key={} error={}", definition.key(), e.getMessage());
                } catch (RuntimeException e) {
                    log.warn("插件库同步失败: key={} error={}", definition.key(), e.getMessage());
                    markError(definition.key(), e.getMessage());
                }
            }
        } finally {
            syncing.set(false);
        }
    }

    /** 同步一个插件库：拉工作副本 → 整库扫描 → 落库。 */
    private void syncRepository(PluginRepoDefinition definition)
            throws IOException, InterruptedException {
        PluginRepository state = ensureState(definition.key());
        PluginRepositorySynchronizer.RepositorySnapshot snapshot;
        try {
            snapshot = repositorySynchronizer.synchronize(definition);
        } catch (IOException | InterruptedException e) {
            markError(state, e.getMessage());
            throw e;
        }
        state.setLocalPath(definition.workingCopy().toString());
        state.setLastPullTime(LocalDateTime.now());

        // 快照键 = commit + 扫描语义版本。扫描器属于框架代码，插件目录可能一个字都没改，
        // 但参数必填规则、返回字段展开这些行为变了，目录就必须重扫，否则一直沿用旧结果。
        String scanKey = snapshot.commitHash() + "@catalog" + CATALOG_VERSION;
        if (scanKey.equals(state.getLastCommitHash())
                && state.getLastError() == null
                && hasRegisteredConnectionTypes()) {
            log.info("插件库内容未变化，跳过扫描: key={} commit={}", definition.key(), snapshot.commitHash());
            state.setLastScanTime(LocalDateTime.now());
            repositoryMapper.updateById(state);
            return;
        }

        PluginCatalogScanner.RepoCatalog catalog;
        try {
            catalog = catalogScanner.scanRepository(definition.folder(), definition.scanner());
        } catch (RuntimeException e) {
            markError(state, e.getMessage());
            throw e;
        }

        List<PluginNodesChangedEvent> pendingChanges = new ArrayList<>();
        try {
            transactionTemplate.executeWithoutResult(
                    status -> applyCatalog(state, snapshot, catalog, pendingChanges));
            state.setLastCommitHash(scanKey);
            state.setLastScanTime(LocalDateTime.now());
            state.setLastError(null);
            repositoryMapper.updateById(state);
            log.info(
                    "插件库同步完成: key={} commit={} plugins={}",
                    definition.key(),
                    snapshot.commitHash(),
                    catalog.plugins().size());
            // 节点变更事件在事务提交之后再发：监听方要写工作流提醒、动 Redis 和定时快照，
            // 这些都不是事务性的，放在事务里会变成"插件回滚了但提醒留下了"。
            pendingChanges.forEach(events::publishEvent);
        } catch (RuntimeException e) {
            markError(state, e.getMessage());
            throw e;
        }
    }

    private void applyCatalog(
            PluginRepository repository,
            PluginRepositorySynchronizer.RepositorySnapshot snapshot,
            PluginCatalogScanner.RepoCatalog catalog,
            List<PluginNodesChangedEvent> pendingChanges) {
        // 按仓库取全部插件再按 plugin_key 索引：plugin_key 现在可能带命名空间前缀（alice.text-tools），
        // 拿索引里的裸 key 去 IN 查是查不到的。
        Map<String, Plugin> pluginsByKey =
                pluginMapper
                        .selectList(
                                new LambdaQueryWrapper<Plugin>()
                                        .eq(Plugin::getRepositoryId, repository.getId()))
                        .stream()
                        .collect(
                                Collectors.toMap(
                                        Plugin::getPluginKey,
                                        plugin -> plugin,
                                        (left, right) -> left,
                                        HashMap::new));
        Map<VersionKey, PluginVersion> versionsByKey = new HashMap<>();
        List<Long> existingPluginIds =
                pluginsByKey.values().stream().map(Plugin::getId).distinct().toList();
        if (!existingPluginIds.isEmpty()) {
            versionMapper
                    .selectList(
                            new LambdaQueryWrapper<PluginVersion>()
                                    .in(PluginVersion::getPluginId, existingPluginIds))
                    .forEach(
                            version ->
                                    versionsByKey.put(
                                            new VersionKey(
                                                    version.getPluginId(), version.getVersion()),
                                            version));
        }
        SyncBatch batch = new SyncBatch();
        Set<VersionKey> seenVersions = new HashSet<>();
        for (PluginCatalogScanner.ScannedPlugin scanned : catalog.plugins()) {
            if (scanned.key().isBlank()) {
                throw new IllegalArgumentException("插件库返回了缺少 key 的插件");
            }
            Plugin plugin = upsertPlugin(repository, scanned, pluginsByKey);
            for (PluginCatalogScanner.ScannedVersion version : scanned.versions()) {
                seenVersions.add(new VersionKey(plugin.getId(), version.version()));
                scanVersion(plugin, scanned, version, snapshot, versionsByKey, batch);
            }
        }
        detectRemovedVersions(versionsByKey, seenVersions, batch);
        flushBatch(batch);
        pendingChanges.addAll(batch.nodeChanges);
    }

    /**
     * 库索引里已经没有的版本：作者撤下了自己的版本（或整个插件下架）。
     *
     * <p>和「节点被删」走同一条链路：把这个版本现有的节点全部报成 removed，工作流侧据此标失效、 摘掉触发。行不删——工作流的失效提醒认的是 {@code
     * plugin_version_id}，留着它提醒才成立。
     */
    private void detectRemovedVersions(
            Map<VersionKey, PluginVersion> versionsByKey,
            Set<VersionKey> seenVersions,
            SyncBatch batch) {
        for (Map.Entry<VersionKey, PluginVersion> entry : versionsByKey.entrySet()) {
            if (seenVersions.contains(entry.getKey())) {
                continue;
            }
            long versionId = entry.getValue().getId();
            List<String> nodeKeys =
                    nodeMapper
                            .selectList(
                                    new LambdaQueryWrapper<PluginNode>()
                                            .eq(PluginNode::getPluginVersionId, versionId))
                            .stream()
                            .map(PluginNode::getNodeKey)
                            .toList();
            if (nodeKeys.isEmpty()) {
                continue;
            }
            batch.nodeChanges.add(new PluginNodesChangedEvent(versionId, List.of(), nodeKeys));
            log.warn("插件版本已从库索引移除，引用它的工作流将标失效: version={} nodes={}", versionId, nodeKeys.size());
        }
    }

    private Plugin upsertPlugin(
            PluginRepository repository,
            PluginCatalogScanner.ScannedPlugin scanned,
            Map<String, Plugin> pluginsByKey) {
        String rawKey = scanned.key().strip();
        String namespace = scanned.namespace() == null ? "" : scanned.namespace().strip();
        // 声明了命名空间就拼进 key：alice.text-tools。
        // 同一个仓库里不同作者的同名插件因此是两个不同的插件，各有各的版本和节点目录。
        String key = namespace.isEmpty() ? rawKey : namespace + "." + rawKey;
        Plugin plugin = pluginsByKey.get(key);
        if (plugin == null) {
            plugin = new Plugin();
            plugin.setRepositoryId(repository.getId());
            plugin.setNamespace(namespace.isEmpty() ? null : namespace);
            plugin.setPluginKey(key);
            plugin.setName(rawKey);
            plugin.setSort(0);
            pluginMapper.insert(plugin);
            pluginsByKey.put(key, plugin);
        }
        return plugin;
    }

    private void scanVersion(
            Plugin plugin,
            PluginCatalogScanner.ScannedPlugin scanned,
            PluginCatalogScanner.ScannedVersion scannedVersion,
            PluginRepositorySynchronizer.RepositorySnapshot snapshot,
            Map<VersionKey, PluginVersion> versionsByKey,
            SyncBatch batch) {
        if (scannedVersion.version() == null || scannedVersion.version().isBlank()) {
            throw new IllegalArgumentException("插件 " + scanned.key() + " 缺少版本号");
        }
        Path pluginDir = resolveInside(snapshot.root(), scannedVersion.path());
        PluginManifestReader.PluginManifest manifest;
        String artifactSha256;
        try {
            manifest = PluginManifestReader.read(pluginDir);
            artifactSha256 = catalogHash(pluginDir);
        } catch (IOException e) {
            throw new IllegalStateException("扫描插件版本失败: " + pluginDir, e);
        }
        // 异构库没有 plugin.toml（比如 GeneralBot 用 plugin.json），key/version 由库索引和清单各自给出，
        // 只有清单存在时才要求两边一致。
        if (manifest.key() != null && !manifest.key().equals(scanned.key())) {
            throw new IllegalArgumentException("plugin.toml key 与库索引不一致: " + pluginDir);
        }
        if (manifest.version() != null && !manifest.version().equals(scannedVersion.version())) {
            throw new IllegalArgumentException("plugin.toml version 与库索引不一致: " + pluginDir);
        }

        updatePluginMetadata(plugin, manifest, scannedVersion.catalog(), scanned.key());

        VersionKey versionKey = new VersionKey(plugin.getId(), scannedVersion.version());
        PluginVersion existing = versionsByKey.get(versionKey);
        if (existing != null && artifactSha256.equals(existing.getArtifactSha256())) {
            return;
        }

        List<AdapterDeclaration> adapters = adapterDeclarations(pluginDir, manifest);
        String manifestJson =
                manifestJson(plugin, manifest, scannedVersion, adapters, artifactSha256);
        PluginVersion version;
        if (existing == null) {
            version = new PluginVersion();
            version.setId(IdWorker.getId());
            version.setPluginId(plugin.getId());
            version.setVersion(scannedVersion.version());
            version.setRuntimeKey("runtime:" + version.getId());
            batch.newVersions.add(version);
            versionsByKey.put(versionKey, version);
        } else {
            // 开发环境不区分版本不可变：同版本目录变化直接原地覆盖，
            // 同时重建该版本的连接类型、节点、能力和依赖行。
            version = existing;
            batch.updatedVersions.add(version);
            batch.replacedVersionIds.add(version.getId());
        }
        version.setSourceCommitHash(snapshot.commitHash());
        version.setManifestJson(manifestJson);
        version.setManifestSha256(sha256(manifestJson));
        version.setArtifactSha256(artifactSha256);
        version.setInstallPath(pluginDir.toString());
        version.setEntryPoint(
                adapters.isEmpty()
                        ? (manifest.adapterEntry() == null ? "main.py" : manifest.adapterEntry())
                        : adapters.getFirst().entryPoint());
        try {
            version.setPythonPath(dependencyInstaller.ensure(pluginDir));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("插件依赖安装失败: " + pluginDir, e);
        } catch (IOException e) {
            throw new IllegalStateException("插件依赖安装失败: " + pluginDir, e);
        }
        version.setPublishedTime(parseTime(scannedVersion.publishedTime()));
        version.setSyncedTime(LocalDateTime.now());

        appendCapabilities(batch, version.getId(), manifest.capabilities());
        for (AdapterDeclaration adapter : adapters) {
            appendConnectionType(batch, version.getId(), plugin, manifest, adapter);
        }
        appendCatalogNodes(batch, version.getId(), pluginDir, adapters, scannedVersion.catalog());
        appendDependencies(batch, version.getId(), pluginDir);
        log.info(
                "插件版本已{}: {}-{}, adapters={}",
                existing == null ? "登记" : "覆盖",
                scanned.key(),
                scannedVersion.version(),
                adapters.stream().map(AdapterDeclaration::connectionType).toList());
    }

    /** 目录内容哈希 + 扫描语义版本：扫描规则变了也要重写这一版目录，不能只比目录内容。 */
    private String catalogHash(Path pluginDir) throws IOException {
        String contentHash = com.loombot.plugin.sync.DirectoryHasher.sha256(pluginDir);
        return com.loombot.plugin.sync.DirectoryHasher.sha256(
                contentHash + "@catalog" + CATALOG_VERSION);
    }

    private void updatePluginMetadata(
            Plugin plugin,
            PluginManifestReader.PluginManifest manifest,
            PluginCatalogScanner.Catalog catalog,
            String rawKey) {
        // 清单里的名字优先。异构库没有 plugin.toml（GeneralBot 用 plugin.json），
        // 就用子扫描器产出的目录里的名字，再不行退回库索引里的裸 key。
        String catalogName = catalog.pluginName();
        String name =
                manifest.name() != null
                        ? manifest.name()
                        : (catalogName.isBlank() ? rawKey : catalogName);
        String description =
                manifest.description() != null
                        ? manifest.description()
                        : catalog.pluginDescription();
        if (java.util.Objects.equals(plugin.getName(), name)
                && java.util.Objects.equals(plugin.getDescription(), description)
                && java.util.Objects.equals(plugin.getHomepage(), manifest.homepage())
                && java.util.Objects.equals(plugin.getAuthor(), manifest.author())) {
            return;
        }
        plugin.setName(name);
        plugin.setDescription(description);
        plugin.setHomepage(manifest.homepage());
        plugin.setAuthor(manifest.author());
        pluginMapper.updateById(plugin);
    }

    private List<AdapterDeclaration> adapterDeclarations(
            Path pluginDir, PluginManifestReader.PluginManifest manifest) {
        if (manifest.adapters() != null && !manifest.adapters().isEmpty()) {
            List<AdapterDeclaration> result = new ArrayList<>();
            for (PluginManifestReader.AdapterManifest declared : manifest.adapters()) {
                String connectionType = required(declared.type(), "adapters.type", pluginDir);
                String schemaFile =
                        declared.connectionSchema() == null
                                ? "connection-schema.json"
                                : declared.connectionSchema();
                result.add(
                        readAdapterDeclaration(
                                pluginDir,
                                manifest,
                                connectionType,
                                declared.entry(),
                                schemaFile,
                                declared.protocolVersion(),
                                declared.schemaVersion(),
                                declared.eventsDir(),
                                declared.actionsDir()));
            }
            return List.copyOf(result);
        }
        AdapterDeclaration legacy = adapterDeclaration(pluginDir, manifest);
        return legacy == null ? List.of() : List.of(legacy);
    }

    private AdapterDeclaration adapterDeclaration(
            Path pluginDir, PluginManifestReader.PluginManifest manifest) {
        List<String> adapterCapabilities =
                manifest.capabilities().stream()
                        .filter(value -> value.startsWith("ws_parser:"))
                        .toList();
        if (adapterCapabilities.isEmpty()) {
            return null;
        }
        if (adapterCapabilities.size() > 1) {
            throw new IllegalArgumentException("当前连接适配器协议一次只支持一个 ws_parser 类型: " + pluginDir);
        }
        String capability = adapterCapabilities.getFirst();
        String connectionType = capability.substring("ws_parser:".length()).strip();
        if (connectionType.isEmpty()) {
            throw new IllegalArgumentException("ws_parser 缺少连接类型: " + pluginDir);
        }
        String schemaFile =
                manifest.connectionSchema() == null
                        ? "connection-schema.json"
                        : manifest.connectionSchema();
        return readAdapterDeclaration(
                pluginDir,
                manifest,
                connectionType,
                manifest.adapterEntry(),
                schemaFile,
                manifest.protocolVersion(),
                manifest.schemaVersion(),
                manifest.eventsDir(),
                manifest.actionsDir());
    }

    private AdapterDeclaration readAdapterDeclaration(
            Path pluginDir,
            PluginManifestReader.PluginManifest manifest,
            String connectionType,
            String entryPoint,
            String schemaFile,
            String protocolVersion,
            String schemaVersion,
            String eventsDir,
            String actionsDir) {
        Path schemaPath = pluginDir.resolve(schemaFile).normalize();
        if (!schemaPath.startsWith(pluginDir) || !Files.isRegularFile(schemaPath)) {
            throw new IllegalArgumentException("连接 schema 文件不存在: " + schemaPath);
        }
        try {
            var schema = objectMapper.readTree(schemaPath.toFile());
            var direction = schema.get("x-direction");
            if (direction == null || !direction.isTextual()) {
                throw new IllegalArgumentException(
                        "connection-schema.json 缺少 x-direction: " + schemaPath);
            }
            return new AdapterDeclaration(
                    connectionType,
                    entryPoint == null || entryPoint.isBlank() ? "main.py" : entryPoint.strip(),
                    direction.asText(),
                    protocolVersion == null ? "1" : protocolVersion,
                    schemaVersion,
                    schema.toString(),
                    eventsDir == null || eventsDir.isBlank() ? "events" : eventsDir.strip(),
                    actionsDir == null || actionsDir.isBlank() ? "actions" : actionsDir.strip());
        } catch (RuntimeException e) {
            throw new IllegalStateException("连接 schema 读取失败: " + schemaPath, e);
        }
    }

    private void appendCapabilities(SyncBatch batch, long versionId, List<String> capabilities) {
        for (String capability : capabilities) {
            PluginCapability entity = new PluginCapability();
            entity.setId(IdWorker.getId());
            entity.setPluginVersionId(versionId);
            entity.setCapability(capability);
            int colon = capability.indexOf(':');
            entity.setKind(
                    (colon < 0 ? capability : capability.substring(0, colon))
                            .toUpperCase(java.util.Locale.ROOT));
            entity.setTarget(colon < 0 ? null : capability.substring(colon + 1));
            batch.capabilities.add(entity);
        }
    }

    private void appendConnectionType(
            SyncBatch batch,
            long versionId,
            Plugin plugin,
            PluginManifestReader.PluginManifest manifest,
            AdapterDeclaration adapter) {
        PluginConnectionType entity = new PluginConnectionType();
        entity.setId(IdWorker.getId());
        entity.setPluginVersionId(versionId);
        entity.setConnectionType(adapter.connectionType());
        entity.setEntryPoint(adapter.entryPoint());
        entity.setDisplayName(manifest.name() == null ? plugin.getName() : manifest.name());
        entity.setDirection(adapter.direction());
        entity.setProtocolVersion(adapter.protocolVersion());
        entity.setSchemaVersion(adapter.schemaVersion());
        entity.setConfigSchema(adapter.configSchemaJson());
        entity.setConfigSchemaSha256(sha256(adapter.configSchemaJson()));
        entity.setSort(0);
        batch.connectionTypes.add(entity);
    }

    private void appendDependencies(SyncBatch batch, long versionId, Path pluginDir) {
        Path requirements = pluginDir.resolve("requirements.txt");
        if (!Files.isRegularFile(requirements)) {
            return;
        }
        try {
            for (String raw : Files.readAllLines(requirements, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int split = firstSpecifierIndex(line);
                PluginDependency dependency = new PluginDependency();
                dependency.setId(IdWorker.getId());
                dependency.setPluginVersionId(versionId);
                dependency.setPackageName(split < 0 ? line : line.substring(0, split).strip());
                dependency.setVersionSpec(split < 0 ? null : line.substring(split).strip());
                batch.dependencies.add(dependency);
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取 requirements.txt 失败: " + requirements, e);
        }
    }

    /**
     * 调用 Python 扫描入口导出节点目录。
     *
     * <p>节点不来自手写清单：适配器事件函数写 EVENT，适配器动作函数写 ACTION，普通插件函数写 NODE。 connection_type
     * 非空表示执行时必须绑定连接。扫描失败即同步失败，保留上一次成功注册表。
     */
    private void appendCatalogNodes(
            SyncBatch batch,
            long versionId,
            Path pluginDir,
            List<AdapterDeclaration> adapters,
            PluginCatalogScanner.Catalog catalog) {
        List<String> scannedAdapters = catalog.adapterTypes();
        List<String> declaredAdapters =
                adapters.stream().map(AdapterDeclaration::connectionType).toList();
        if (!scannedAdapters.equals(declaredAdapters)) {
            throw new IllegalStateException(
                    "清单声明的适配器类型与扫描结果不一致: "
                            + pluginDir
                            + " declared="
                            + declaredAdapters
                            + " scanned="
                            + scannedAdapters);
        }
        // 变更检测必须在 flushBatch 删旧行之前做：拿旧目录和新目录比，
        // 同一个 nodeKey 签名变了 = 契约变更，旧的有、新的没有 = 节点被删。
        // 两者都要通知出去，引用了它们的工作流会被标上提醒。
        Map<String, String> previousSignatures = existingNodeSignatures(versionId);
        Set<String> seenKeys = new HashSet<>();
        List<String> changedKeys = new ArrayList<>();
        for (PluginCatalogScanner.ScannedNode node : catalog.nodes()) {
            String nodeKey = required(node.nodeKey(), "nodeKey", pluginDir);
            String signature = nodeSignature(node);
            seenKeys.add(nodeKey);
            String previous = previousSignatures.get(nodeKey);
            if (previous != null && !previous.equals(signature)) {
                changedKeys.add(nodeKey);
            }
            PluginNode entity = new PluginNode();
            entity.setId(IdWorker.getId());
            entity.setPluginVersionId(versionId);
            entity.setNodeKey(nodeKey);
            entity.setNodeType(node.nodeType());
            entity.setConnectionType(node.connectionType());
            entity.setName(node.name());
            entity.setDescription(trim(node.description(), 512));
            entity.setInputSchema(node.inputSchema());
            entity.setOutputSchema(node.outputSchema());
            entity.setSourceRef(trim(node.sourceRef(), 255));
            // 用 Java 这边算的签名，而不是插件自己报的：适配器事件/动作节点根本不带签名，
            // 而且签名要覆盖"契约"（入参/出参 schema），不能由插件随便给一个值。
            entity.setSignatureHash(signature);
            entity.setSort(node.sort());
            batch.nodes.add(entity);
        }
        List<String> removedKeys =
                previousSignatures.keySet().stream()
                        .filter(key -> !seenKeys.contains(key))
                        .toList();
        if (!changedKeys.isEmpty() || !removedKeys.isEmpty()) {
            batch.nodeChanges.add(new PluginNodesChangedEvent(versionId, changedKeys, removedKeys));
            log.info(
                    "插件节点契约发生变化: version={} changed={} removed={}",
                    versionId,
                    changedKeys,
                    removedKeys);
        }
        log.info("插件节点目录已导出: version={} nodes={}", versionId, catalog.nodes().size());
    }

    /** 这个版本当前登记在库里的节点签名，按 nodeKey 索引。新版本返回空表。 */
    private Map<String, String> existingNodeSignatures(long versionId) {
        Map<String, String> signatures = new HashMap<>();
        List<PluginNode> rows =
                nodeMapper.selectList(
                        new LambdaQueryWrapper<PluginNode>()
                                .eq(PluginNode::getPluginVersionId, versionId));
        for (PluginNode row : rows) {
            signatures.put(row.getNodeKey(), nz(row.getSignatureHash()));
        }
        return signatures;
    }

    /**
     * 节点签名，只覆盖「契约」：节点 key、类型、连接类型、入参 schema、出参 schema。
     *
     * <p>刻意不含显示名和描述——改个文案不该把所有引用它的工作流标成「已变更」。
     */
    private static String nodeSignature(PluginCatalogScanner.ScannedNode node) {
        String payload =
                String.join(
                        "\u0000",
                        nz(node.nodeKey()),
                        nz(node.nodeType()),
                        nz(node.connectionType()),
                        nz(node.inputSchema()),
                        nz(node.outputSchema()));
        return sha256(payload).substring(0, 32);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private void flushBatch(SyncBatch batch) {
        if (!batch.replacedVersionIds.isEmpty()) {
            capabilityMapper.delete(
                    new LambdaQueryWrapper<PluginCapability>()
                            .in(PluginCapability::getPluginVersionId, batch.replacedVersionIds));
            connectionTypeMapper.delete(
                    new LambdaQueryWrapper<PluginConnectionType>()
                            .in(
                                    PluginConnectionType::getPluginVersionId,
                                    batch.replacedVersionIds));
            nodeMapper.delete(
                    new LambdaQueryWrapper<PluginNode>()
                            .in(PluginNode::getPluginVersionId, batch.replacedVersionIds));
            dependencyMapper.delete(
                    new LambdaQueryWrapper<PluginDependency>()
                            .in(PluginDependency::getPluginVersionId, batch.replacedVersionIds));
        }
        if (!batch.newVersions.isEmpty()) {
            versionMapper.insertBatch(batch.newVersions);
        }
        for (PluginVersion version : batch.updatedVersions) {
            versionMapper.updateById(version);
        }
        if (!batch.capabilities.isEmpty()) {
            capabilityMapper.insertBatch(batch.capabilities);
        }
        if (!batch.connectionTypes.isEmpty()) {
            connectionTypeMapper.insertBatch(batch.connectionTypes);
        }
        if (!batch.nodes.isEmpty()) {
            nodeMapper.insertBatch(batch.nodes);
        }
        if (!batch.dependencies.isEmpty()) {
            dependencyMapper.insertBatch(batch.dependencies);
        }
    }

    /** 取（必要时创建）这个插件库的同步状态行。声明在 repo.json 里，这里只存状态。 */
    private PluginRepository ensureState(String repoKey) {
        PluginRepository state =
                repositoryMapper.selectOne(
                        new LambdaQueryWrapper<PluginRepository>()
                                .eq(PluginRepository::getRepoKey, repoKey));
        if (state == null) {
            state = new PluginRepository();
            state.setRepoKey(repoKey);
            state.setLocalPath("");
            repositoryMapper.insert(state);
        }
        return state;
    }

    /** 把失败原因记在库的状态行上，界面上能看到是哪个库出的问题。 */
    private void markError(PluginRepository state, String message) {
        state.setLastError(trim(message, 1024));
        repositoryMapper.updateById(state);
    }

    private void markError(String repoKey, String message) {
        markError(ensureState(repoKey), message);
    }

    /** commit 未变化不代表注册表仍然完整：上一次扫描可能在写库前失败，或者数据库被重建过。 只要一张连接类型都读不到，就必须重新扫描，不能因为 commit 相同而永久跳过。 */
    private boolean hasRegisteredConnectionTypes() {
        return connectionTypeMapper.selectCount(null) > 0;
    }

    private Path resolveInside(Path root, String relative) {
        if (relative == null || relative.isBlank()) {
            throw new IllegalArgumentException("插件版本缺少 path");
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("插件路径越出仓库目录: " + relative);
        }
        if (!Files.isDirectory(resolved)) {
            throw new IllegalArgumentException("插件目录不存在: " + resolved);
        }
        return resolved;
    }

    private String manifestJson(
            Plugin plugin,
            PluginManifestReader.PluginManifest manifest,
            PluginCatalogScanner.ScannedVersion scannedVersion,
            List<AdapterDeclaration> adapters,
            String artifactSha256) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put(
                "plugin",
                Map.of(
                        "key", plugin.getPluginKey(),
                        "name", plugin.getName(),
                        "version", scannedVersion.version()));
        root.put("capabilities", manifest.capabilities());
        root.put("artifactSha256", artifactSha256);
        if (!adapters.isEmpty()) {
            root.put(
                    "adapters",
                    adapters.stream()
                            .map(
                                    adapter -> {
                                        Map<String, Object> adapterNode = new LinkedHashMap<>();
                                        adapterNode.put("connectionType", adapter.connectionType());
                                        adapterNode.put("entryPoint", adapter.entryPoint());
                                        adapterNode.put("direction", adapter.direction());
                                        adapterNode.put(
                                                "protocolVersion", adapter.protocolVersion());
                                        adapterNode.put("schemaVersion", adapter.schemaVersion());
                                        adapterNode.put("eventsDir", adapter.eventsDir());
                                        adapterNode.put("actionsDir", adapter.actionsDir());
                                        return adapterNode;
                                    })
                            .toList());
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("生成 manifest JSON 失败", e);
        }
    }

    private static String required(String value, String field, Path pluginDir) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 缺失: " + pluginDir);
        }
        return value.strip();
    }

    private static LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int firstSpecifierIndex(String value) {
        for (int index = 0; index < value.length(); index++) {
            if ("<>=!~".indexOf(value.charAt(index)) >= 0) {
                return index;
            }
        }
        return -1;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of()
                    .formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("生成 SHA-256 失败", e);
        }
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record AdapterDeclaration(
            String connectionType,
            String entryPoint,
            String direction,
            String protocolVersion,
            String schemaVersion,
            String configSchemaJson,
            String eventsDir,
            String actionsDir) {}

    private static final class SyncBatch {
        private final List<PluginVersion> newVersions = new ArrayList<>();
        private final List<PluginVersion> updatedVersions = new ArrayList<>();
        private final List<Long> replacedVersionIds = new ArrayList<>();
        private final List<PluginCapability> capabilities = new ArrayList<>();
        private final List<PluginConnectionType> connectionTypes = new ArrayList<>();
        private final List<PluginNode> nodes = new ArrayList<>();
        private final List<PluginDependency> dependencies = new ArrayList<>();
        private final List<PluginNodesChangedEvent> nodeChanges = new ArrayList<>();
    }

    private record VersionKey(long pluginId, String version) {}
}
