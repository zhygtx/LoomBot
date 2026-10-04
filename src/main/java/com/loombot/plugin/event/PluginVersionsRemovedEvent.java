package com.loombot.plugin.event;

import java.util.List;

/**
 * 插件版本已经从目录里移除（作者撤回，或整个插件下架）。
 *
 * <p>目录行此刻已经删掉，所以这个事件是连接模块清理"绑定了已移除版本"的连接的唯一信号。 插件模块不直接删连接：那是连接模块自己的边界。
 *
 * @param pluginVersionIds 被移除的插件版本 ID
 */
public record PluginVersionsRemovedEvent(List<Long> pluginVersionIds) {}
