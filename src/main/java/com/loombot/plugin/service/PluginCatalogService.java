package com.loombot.plugin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.loombot.plugin.domain.Plugin;
import com.loombot.plugin.domain.PluginCapability;
import com.loombot.plugin.domain.PluginConnectionType;
import com.loombot.plugin.domain.PluginVersion;
import com.loombot.plugin.mapper.PluginCapabilityMapper;
import com.loombot.plugin.mapper.PluginConnectionTypeMapper;
import com.loombot.plugin.mapper.PluginMapper;
import com.loombot.plugin.mapper.PluginVersionMapper;
import com.loombot.runtime.plugin.AdapterConnectionTypeRuntime;
import com.loombot.runtime.plugin.PluginCatalog;
import com.loombot.runtime.plugin.PluginVersionRuntime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 插件注册表的只读实现。
 *
 * <p>数据库是唯一数据源：每次调用直接查表并组装返回值，不在 Java 进程里维护目录快照。 插件同步只负责把文件变化写进数据库，前端请求不依赖同步过程的内存状态。
 */
@Service
public class PluginCatalogService implements PluginCatalog {

    private final PluginMapper pluginMapper;
    private final PluginVersionMapper versionMapper;
    private final PluginCapabilityMapper capabilityMapper;
    private final PluginConnectionTypeMapper connectionTypeMapper;
    private final ObjectMapper objectMapper;

    public PluginCatalogService(
            PluginMapper pluginMapper,
            PluginVersionMapper versionMapper,
            PluginCapabilityMapper capabilityMapper,
            PluginConnectionTypeMapper connectionTypeMapper,
            ObjectMapper objectMapper) {
        this.pluginMapper = pluginMapper;
        this.versionMapper = versionMapper;
        this.capabilityMapper = capabilityMapper;
        this.connectionTypeMapper = connectionTypeMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<AdapterConnectionTypeRuntime> adapterConnectionTypes() {
        List<PluginConnectionType> types =
                connectionTypeMapper.selectList(
                        new LambdaQueryWrapper<PluginConnectionType>()
                                .orderByAsc(PluginConnectionType::getConnectionType)
                                .orderByDesc(PluginConnectionType::getId));
        if (types.isEmpty()) {
            return List.of();
        }
        Map<Long, PluginVersion> versions =
                versionsByIds(
                        types.stream().map(PluginConnectionType::getPluginVersionId).toList());
        Map<Long, Plugin> plugins =
                pluginsByIds(versions.values().stream().map(PluginVersion::getPluginId).toList());
        Map<Long, List<PluginCapability>> capabilities =
                capabilitiesByVersionIds(
                        types.stream().map(PluginConnectionType::getPluginVersionId).toList());
        return types.stream()
                .map(
                        type ->
                                map(
                                        type,
                                        versions.get(type.getPluginVersionId()),
                                        plugins,
                                        capabilities))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @Override
    public Optional<AdapterConnectionTypeRuntime> adapterConnectionType(
            long pluginVersionId, String connectionType) {
        PluginConnectionType type =
                connectionTypeMapper.selectOne(
                        new LambdaQueryWrapper<PluginConnectionType>()
                                .eq(PluginConnectionType::getPluginVersionId, pluginVersionId)
                                .eq(PluginConnectionType::getConnectionType, connectionType));
        if (type == null) {
            return Optional.empty();
        }
        PluginVersion version = versionMapper.selectById(pluginVersionId);
        if (version == null) {
            return Optional.empty();
        }
        Plugin plugin = pluginMapper.selectById(version.getPluginId());
        if (plugin == null) {
            return Optional.empty();
        }
        List<PluginCapability> capabilities =
                capabilityMapper.selectList(
                        new LambdaQueryWrapper<PluginCapability>()
                                .eq(PluginCapability::getPluginVersionId, pluginVersionId)
                                .orderByAsc(PluginCapability::getId));
        return Optional.ofNullable(map(type, version, plugin, capabilities));
    }

    @Override
    public Optional<PluginVersionRuntime> adapterVersion(long pluginVersionId) {
        PluginVersion version = versionMapper.selectById(pluginVersionId);
        if (version == null) {
            return Optional.empty();
        }
        Plugin plugin = pluginMapper.selectById(version.getPluginId());
        return plugin == null ? Optional.empty() : Optional.of(map(version, plugin));
    }

    private AdapterConnectionTypeRuntime map(
            PluginConnectionType type,
            PluginVersion version,
            Map<Long, Plugin> plugins,
            Map<Long, List<PluginCapability>> capabilities) {
        if (version == null) {
            return null;
        }
        Plugin plugin = plugins.get(version.getPluginId());
        return map(type, version, plugin, capabilities.getOrDefault(version.getId(), List.of()));
    }

    private AdapterConnectionTypeRuntime map(
            PluginConnectionType type,
            PluginVersion version,
            Plugin plugin,
            List<PluginCapability> capabilities) {
        if (version == null || plugin == null) {
            return null;
        }
        List<String> values = capabilities.stream().map(PluginCapability::getCapability).toList();
        String capabilitiesJson;
        try {
            capabilitiesJson = objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            capabilitiesJson = "[]";
        }
        return new AdapterConnectionTypeRuntime(
                plugin.getId(),
                version.getId(),
                plugin.getPluginKey(),
                plugin.getName(),
                version.getVersion(),
                type.getConnectionType(),
                type.getEntryPoint(),
                type.getDisplayName(),
                type.getDirection(),
                type.getProtocolVersion(),
                type.getSchemaVersion(),
                type.getConfigSchema(),
                capabilitiesJson);
    }

    private Map<Long, List<PluginCapability>> capabilitiesByVersionIds(List<Long> versionIds) {
        if (versionIds == null || versionIds.isEmpty()) {
            return Map.of();
        }
        return capabilityMapper
                .selectList(
                        new LambdaQueryWrapper<PluginCapability>()
                                .in(PluginCapability::getPluginVersionId, versionIds)
                                .orderByAsc(PluginCapability::getId))
                .stream()
                .collect(
                        java.util.stream.Collectors.groupingBy(
                                PluginCapability::getPluginVersionId,
                                LinkedHashMap::new,
                                java.util.stream.Collectors.toList()));
    }

    private Map<Long, PluginVersion> versionsByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, PluginVersion> result = new LinkedHashMap<>();
        versionMapper.selectBatchIds(ids).forEach(version -> result.put(version.getId(), version));
        return result;
    }

    private Map<Long, Plugin> pluginsByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Plugin> result = new LinkedHashMap<>();
        pluginMapper.selectBatchIds(ids).forEach(plugin -> result.put(plugin.getId(), plugin));
        return result;
    }

    private PluginVersionRuntime map(PluginVersion version, Plugin plugin) {
        return new PluginVersionRuntime(
                version.getId(),
                plugin.getId(),
                plugin.getPluginKey(),
                plugin.getName(),
                version.getVersion(),
                version.getInstallPath(),
                version.getEntryPoint(),
                version.getPythonPath(),
                version.getArtifactSha256(),
                version.getRuntimeKey(),
                version.getManifestJson());
    }
}
