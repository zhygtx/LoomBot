package com.loombot.runtime.plugin;

/**
 * 适配器插件版本声明的连接类型。
 *
 * <p>{@code direction} 保持字符串形式，由 connection 模块解析为它自己的枚举。 这样 runtime 不需要反向依赖任何业务模块。
 */
public record AdapterConnectionTypeRuntime(
        long pluginId,
        long pluginVersionId,
        String pluginKey,
        String pluginName,
        String pluginVersion,
        String connectionType,
        String entryPoint,
        String displayName,
        String direction,
        String protocolVersion,
        String schemaVersion,
        String configSchemaJson,
        String capabilitiesJson) {}
