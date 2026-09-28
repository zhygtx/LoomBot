package com.loom.runtime.plugin;

/**
 * 一个可被运行时加载的插件版本。
 *
 * <p>这是 {@code runtime} 模块对业务模块暴露的最小只读视图。它不依赖插件实体， 也不依赖连接实体，因此 {@code connection} 可以使用插件能力而无需依赖
 * {@code plugin} 模块。
 */
public record PluginVersionRuntime(
        long pluginVersionId,
        long pluginId,
        String pluginKey,
        String pluginName,
        String pluginVersion,
        String installPath,
        String entryPoint,
        String pythonPath,
        String runtimeKey,
        String manifestJson) {}
