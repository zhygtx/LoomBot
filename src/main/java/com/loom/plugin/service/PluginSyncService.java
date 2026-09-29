package com.loom.plugin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.loom.plugin.PluginProperties;
import com.loom.plugin.domain.Plugin;
import com.loom.plugin.domain.PluginCapability;
import com.loom.plugin.domain.PluginConnectionType;
import com.loom.plugin.domain.PluginDependency;
import com.loom.plugin.domain.PluginNode;
import com.loom.plugin.domain.PluginRepository;
import com.loom.plugin.domain.PluginVersion;
import com.loom.plugin.mapper.PluginCapabilityMapper;
import com.loom.plugin.mapper.PluginConnectionTypeMapper;
import com.loom.plugin.mapper.PluginDependencyMapper;
import com.loom.plugin.mapper.PluginMapper;
import com.loom.plugin.mapper.PluginNodeMapper;
import com.loom.plugin.mapper.PluginRepositoryMapper;
import com.loom.plugin.mapper.PluginVersionMapper;
import com.loom.plugin.sync.PluginCatalogScanner;
import com.loom.plugin.sync.PluginDependencyInstaller;
import com.loom.plugin.sync.PluginManifestReader;
import com.loom.plugin.sync.PluginRepositorySynchronizer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
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
    private final AtomicBoolean syncing = new AtomicBoolean(false);

    public PluginSyncService(
            PluginProperties properties,
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
            TransactionTemplate transactionTemplate) {
        this.properties = properties;
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
    @Scheduled(fixedDelayString = "${loom.plugin.sync-interval:30s}")
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
            log.debug("插件仓库正在同步，跳过本次触发");
            return;
        }
        try {
            PluginRepository repository = ensureRepository();
            PluginRepositorySynchronizer.RepositorySnapshot snapshot;
            try {
                snapshot = repositorySynchronizer.synchronize();
                repository.setLastPullTime(LocalDateTime.now());
            } catch (IOException | InterruptedException e) {
                repository.setLastError(trim(e.getMessage(), 1024));
                repositoryMapper.updateById(repository);
                throw e;
            }

            // 快照键 = commit + 扫描语义版本。扫描器属于框架代码，插件目录可能一个字都没改，
            // 但参数必填规则、返回字段展开这些行为变了，目录就必须重扫，否则一直沿用旧结果。
            String scanKey = snapshot.commitHash() + "@catalog" + CATALOG_VERSION;
            if (scanKey.equals(repository.getLastCommitHash())
                    && repository.getLastError() == null
                    && hasRegisteredConnectionTypes()) {
                log.info("插件仓库 commit 未变化，跳过扫描: {}", snapshot.commitHash());
                repository.setLastScanTime(LocalDateTime.now());
                repositoryMapper.updateById(repository);
                return;
            }

            try {
                transactionTemplate.executeWithoutResult(
                        status -> applySnapshot(repository, snapshot));
                repository.setLastCommitHash(scanKey);
                repository.setLastScanTime(LocalDateTime.now());
                repository.setLastError(null);
                repositoryMapper.updateById(repository);
                log.info("插件仓库同步完成: commit={}", snapshot.commitHash());
            } catch (RuntimeException e) {
                repository.setLastError(trim(e.getMessage(), 1024));
                repositoryMapper.updateById(repository);
                throw e;
            }
        } finally {
            syncing.set(false);
        }
    }

    private void applySnapshot(
            PluginRepository repository, PluginRepositorySynchronizer.RepositorySnapshot snapshot) {
        PluginIndex index = readIndex(snapshot.root());
        if (index.plugins() == null || index.plugins().isEmpty()) {
            log.warn("插件仓库没有登记任何插件: {}", snapshot.root());
            return;
        }
        List<String> pluginKeys =
                index.plugins().stream()
                        .map(PluginIndexEntry::key)
                        .filter(java.util.Objects::nonNull)
                        .map(String::strip)
                        .toList();
        Map<String, Plugin> pluginsByKey =
                pluginKeys.isEmpty()
                        ? Map.of()
                        : pluginMapper
                                .selectList(
                                        new LambdaQueryWrapper<Plugin>()
                                                .in(Plugin::getPluginKey, pluginKeys))
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
        for (PluginIndexEntry pluginEntry : index.plugins()) {
            if (pluginEntry.key() == null || pluginEntry.key().isBlank()) {
                throw new IllegalArgumentException("index.json 存在缺少 key 的插件");
            }
            if (pluginEntry.versions() == null || pluginEntry.versions().isEmpty()) {
                continue;
            }
            Plugin plugin = upsertPlugin(repository, pluginEntry, pluginsByKey);
            for (PluginVersionEntry versionEntry : pluginEntry.versions()) {
                scanVersion(plugin, pluginEntry, versionEntry, snapshot, versionsByKey, batch);
            }
        }
        flushBatch(batch);
    }

    private Plugin upsertPlugin(
            PluginRepository repository, PluginIndexEntry entry, Map<String, Plugin> pluginsByKey) {
        String key = entry.key().strip();
        Plugin plugin = pluginsByKey.get(key);
        if (plugin == null) {
            plugin = new Plugin();
            plugin.setRepositoryId(repository.getId());
            plugin.setPluginKey(key);
            plugin.setName(key);
            plugin.setSort(0);
            pluginMapper.insert(plugin);
            pluginsByKey.put(key, plugin);
        }
        return plugin;
    }

    private void scanVersion(
            Plugin plugin,
            PluginIndexEntry pluginEntry,
            PluginVersionEntry versionEntry,
            PluginRepositorySynchronizer.RepositorySnapshot snapshot,
            Map<VersionKey, PluginVersion> versionsByKey,
            SyncBatch batch) {
        if (versionEntry.version() == null || versionEntry.version().isBlank()) {
            throw new IllegalArgumentException("插件 " + pluginEntry.key() + " 缺少版本号");
        }
        Path pluginDir = resolveInside(snapshot.root(), versionEntry.path());
        PluginManifestReader.PluginManifest manifest;
        String artifactSha256;
        try {
            manifest = PluginManifestReader.read(pluginDir);
            artifactSha256 = catalogHash(pluginDir);
        } catch (IOException e) {
            throw new IllegalStateException("扫描插件版本失败: " + pluginDir, e);
        }
        if (manifest.key() != null && !manifest.key().equals(pluginEntry.key())) {
            throw new IllegalArgumentException("plugin.toml key 与 index.json 不一致: " + pluginDir);
        }
        if (manifest.version() != null && !manifest.version().equals(versionEntry.version())) {
            throw new IllegalArgumentException(
                    "plugin.toml version 与 index.json 不一致: " + pluginDir);
        }

        updatePluginMetadata(plugin, manifest, pluginEntry);

        VersionKey versionKey = new VersionKey(plugin.getId(), versionEntry.version());
        PluginVersion existing = versionsByKey.get(versionKey);
        if (existing != null && artifactSha256.equals(existing.getArtifactSha256())) {
            return;
        }

        List<AdapterDeclaration> adapters = adapterDeclarations(pluginDir, manifest);
        String manifestJson =
                manifestJson(plugin, manifest, versionEntry, adapters, artifactSha256);
        PluginVersion version;
        if (existing == null) {
            version = new PluginVersion();
            version.setId(IdWorker.getId());
            version.setPluginId(plugin.getId());
            version.setVersion(versionEntry.version());
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
        version.setPublishedTime(parseTime(versionEntry.publishedTime()));
        version.setSyncedTime(LocalDateTime.now());

        appendCapabilities(batch, version.getId(), manifest.capabilities());
        for (AdapterDeclaration adapter : adapters) {
            appendConnectionType(batch, version.getId(), plugin, manifest, adapter);
        }
        appendCatalogNodes(batch, version.getId(), pluginDir, adapters);
        appendDependencies(batch, version.getId(), pluginDir);
        log.info(
                "插件版本已{}: {}-{}, adapters={}",
                existing == null ? "登记" : "覆盖",
                pluginEntry.key(),
                versionEntry.version(),
                adapters.stream().map(AdapterDeclaration::connectionType).toList());
    }

    /** 目录内容哈希 + 扫描语义版本：扫描规则变了也要重写这一版目录，不能只比目录内容。 */
    private String catalogHash(Path pluginDir) throws IOException {
        String contentHash = com.loom.plugin.sync.DirectoryHasher.sha256(pluginDir);
        return com.loom.plugin.sync.DirectoryHasher.sha256(
                contentHash + "@catalog" + CATALOG_VERSION);
    }

    private void updatePluginMetadata(
            Plugin plugin, PluginManifestReader.PluginManifest manifest, PluginIndexEntry entry) {
        String name = manifest.name() == null ? entry.key() : manifest.name();
        if (java.util.Objects.equals(plugin.getName(), name)
                && java.util.Objects.equals(plugin.getDescription(), manifest.description())
                && java.util.Objects.equals(plugin.getHomepage(), manifest.homepage())
                && java.util.Objects.equals(plugin.getAuthor(), manifest.author())) {
            return;
        }
        plugin.setName(name);
        plugin.setDescription(manifest.description());
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
            SyncBatch batch, long versionId, Path pluginDir, List<AdapterDeclaration> adapters) {
        PluginCatalogScanner.Catalog catalog = catalogScanner.scan(pluginDir);
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
        for (PluginCatalogScanner.ScannedNode node : catalog.nodes()) {
            PluginNode entity = new PluginNode();
            entity.setId(IdWorker.getId());
            entity.setPluginVersionId(versionId);
            entity.setNodeKey(required(node.nodeKey(), "nodeKey", pluginDir));
            entity.setNodeType(node.nodeType());
            entity.setConnectionType(node.connectionType());
            entity.setName(node.name());
            entity.setDescription(trim(node.description(), 512));
            entity.setInputSchema(node.inputSchema());
            entity.setOutputSchema(node.outputSchema());
            entity.setSourceRef(trim(node.sourceRef(), 255));
            entity.setSignatureHash(node.signatureHash());
            entity.setSort(node.sort());
            batch.nodes.add(entity);
        }
        log.info("插件节点目录已导出: version={} nodes={}", versionId, catalog.nodes().size());
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

    private PluginIndex readIndex(Path root) {
        Path index = root.resolve("index.json");
        try {
            return objectMapper.readValue(index.toFile(), PluginIndex.class);
        } catch (RuntimeException e) {
            throw new IllegalStateException("读取 index.json 失败: " + index, e);
        }
    }

    private PluginRepository ensureRepository() {
        PluginRepository repository =
                repositoryMapper.selectOne(
                        new LambdaQueryWrapper<PluginRepository>()
                                .eq(PluginRepository::getRepoKey, properties.repositoryKey()));
        if (repository == null) {
            repository = new PluginRepository();
            repository.setRepoKey(properties.repositoryKey());
            repository.setRepoUrl(properties.repositoryUrl());
            repository.setBranch(properties.branch());
            repository.setLocalPath(
                    Path.of(properties.localPath()).toAbsolutePath().normalize().toString());
            repositoryMapper.insert(repository);
        } else {
            String localPath =
                    Path.of(properties.localPath()).toAbsolutePath().normalize().toString();
            boolean changed =
                    !java.util.Objects.equals(repository.getRepoUrl(), properties.repositoryUrl())
                            || !java.util.Objects.equals(
                                    repository.getBranch(), properties.branch())
                            || !java.util.Objects.equals(repository.getLocalPath(), localPath);
            if (changed) {
                repository.setRepoUrl(properties.repositoryUrl());
                repository.setBranch(properties.branch());
                repository.setLocalPath(localPath);
                repositoryMapper.updateById(repository);
            }
        }
        return repository;
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
            PluginVersionEntry versionEntry,
            List<AdapterDeclaration> adapters,
            String artifactSha256) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put(
                "plugin",
                Map.of(
                        "key", plugin.getPluginKey(),
                        "name", plugin.getName(),
                        "version", versionEntry.version()));
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
    }

    private record VersionKey(long pluginId, String version) {}

    private record PluginIndex(int schemaVersion, List<PluginIndexEntry> plugins) {}

    private record PluginIndexEntry(String key, List<PluginVersionEntry> versions) {}

    private record PluginVersionEntry(String version, String path, String publishedTime) {}
}
