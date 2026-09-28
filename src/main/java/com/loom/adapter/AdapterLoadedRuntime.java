package com.loom.adapter;

/** Python Adapter 当前已加载的插件版本。 */
public record AdapterLoadedRuntime(
        long pluginVersionId,
        String pluginKey,
        String pluginVersion,
        String connectionType,
        String state) {}
