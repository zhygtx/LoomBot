package com.loom.plugin.event;

import java.util.List;

/**
 * 某个插件版本重扫之后，节点契约发生变化。
 *
 * <p>只带「哪个版本、哪些 nodeKey 变了 / 没了」，不带工作流信息：谁受影响是工作流模块自己的事， 插件模块不该反过来依赖工作流。
 *
 * @param pluginVersionId 发生变化的插件版本
 * @param changedNodeKeys 还在、但入参或返回值变了的节点
 * @param removedNodeKeys 已经不在目录里的节点
 */
public record PluginNodesChangedEvent(
        long pluginVersionId, List<String> changedNodeKeys, List<String> removedNodeKeys) {}
