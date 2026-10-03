package com.loombot.runtime.plugin;

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
        /** 制品哈希：同一版本目录被原地覆盖时它会变，运行期据此判断"要不要重载"。 */
        String artifactSha256,
        String runtimeKey,
        String manifestJson) {}
