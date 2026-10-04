package com.loombot.plugin.event;

import java.util.List;

/**
 * 某个插件版本重扫之后，节点契约发生变化。
 *
 * <p>只带「哪个版本、哪些节点变了 / 没了」，不带工作流信息：谁受影响是工作流模块自己的事， 插件模块不该反过来依赖工作流。
 *
 * <p>为什么连名称、类型、连接类型一起带上：插件目录已经是可变镜像，版本被移除时目录行会跟着 删除。工作流那边要在删除之前把身份快照记下来，否则事后只剩一个 ID，界面没法解释失效原因。
 *
 * @param pluginVersionId 发生变化的插件版本
 * @param pluginKey 插件键，如 {@code loombot.qq-official-adapter}
 * @param pluginVersion 插件版本号
 * @param changedNodes 还在、但入参、返回值或实现变了的节点
 * @param removedNodes 已经不在目录里的节点
 */
public record PluginNodesChangedEvent(
        long pluginVersionId,
        String pluginKey,
        String pluginVersion,
        List<NodeChange> changedNodes,
        List<NodeChange> removedNodes) {

    /** 一个变化节点的身份快照。 */
    public record NodeChange(
            String nodeKey, String nodeName, String nodeType, String connectionType) {}
}
