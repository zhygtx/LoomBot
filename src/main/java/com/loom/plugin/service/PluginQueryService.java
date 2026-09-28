package com.loom.plugin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.loom.plugin.domain.Plugin;
import com.loom.plugin.domain.PluginConnectionType;
import com.loom.plugin.domain.PluginNode;
import com.loom.plugin.domain.PluginVersion;
import com.loom.plugin.dto.AdapterTypeResponse;
import com.loom.plugin.dto.PluginNodeResponse;
import com.loom.plugin.dto.PluginResponse;
import com.loom.plugin.dto.PluginVersionResponse;
import com.loom.plugin.mapper.PluginConnectionTypeMapper;
import com.loom.plugin.mapper.PluginMapper;
import com.loom.plugin.mapper.PluginNodeMapper;
import com.loom.plugin.mapper.PluginVersionMapper;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PluginQueryService {

    private final PluginMapper pluginMapper;
    private final PluginVersionMapper versionMapper;
    private final PluginConnectionTypeMapper connectionTypeMapper;
    private final PluginNodeMapper nodeMapper;

    public PluginQueryService(
            PluginMapper pluginMapper,
            PluginVersionMapper versionMapper,
            PluginConnectionTypeMapper connectionTypeMapper,
            PluginNodeMapper nodeMapper) {
        this.pluginMapper = pluginMapper;
        this.versionMapper = versionMapper;
        this.connectionTypeMapper = connectionTypeMapper;
        this.nodeMapper = nodeMapper;
    }

    public List<PluginResponse> list() {
        return pluginMapper
                .selectList(
                        new LambdaQueryWrapper<Plugin>()
                                .orderByAsc(Plugin::getSort)
                                .orderByAsc(Plugin::getPluginKey))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private PluginResponse toResponse(Plugin plugin) {
        List<PluginVersionResponse> versions =
                versionMapper
                        .selectList(
                                new LambdaQueryWrapper<PluginVersion>()
                                        .eq(PluginVersion::getPluginId, plugin.getId())
                                        .orderByDesc(PluginVersion::getSyncedTime))
                        .stream()
                        .map(this::toResponse)
                        .toList();
        return new PluginResponse(
                plugin.getId(),
                plugin.getPluginKey(),
                plugin.getName(),
                plugin.getDescription(),
                plugin.getHomepage(),
                plugin.getAuthor(),
                versions);
    }

    private PluginVersionResponse toResponse(PluginVersion version) {
        List<AdapterTypeResponse> adapterTypes =
                connectionTypeMapper
                        .selectList(
                                new LambdaQueryWrapper<PluginConnectionType>()
                                        .eq(
                                                PluginConnectionType::getPluginVersionId,
                                                version.getId())
                                        .orderByAsc(PluginConnectionType::getSort))
                        .stream()
                        .map(
                                type ->
                                        new AdapterTypeResponse(
                                                type.getConnectionType(),
                                                type.getDisplayName(),
                                                type.getDirection()))
                        .toList();
        List<PluginNodeResponse> nodes =
                nodeMapper
                        .selectList(
                                new LambdaQueryWrapper<PluginNode>()
                                        .eq(PluginNode::getPluginVersionId, version.getId())
                                        .orderByAsc(PluginNode::getSort)
                                        .orderByAsc(PluginNode::getNodeKey))
                        .stream()
                        .map(
                                node ->
                                        new PluginNodeResponse(
                                                node.getNodeKey(),
                                                node.getNodeType(),
                                                node.getName(),
                                                node.getDescription()))
                        .toList();
        return new PluginVersionResponse(
                version.getId(),
                version.getVersion(),
                version.getSourceCommitHash(),
                version.getEntryPoint(),
                version.getSyncedTime(),
                adapterTypes,
                nodes);
    }
}
