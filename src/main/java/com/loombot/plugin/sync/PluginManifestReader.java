package com.loombot.plugin.sync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/** 读取插件包内的 plugin.toml。 */
public final class PluginManifestReader {

    private PluginManifestReader() {}

    public static PluginManifest read(Path pluginDir) throws IOException {
        Path manifest = pluginDir.resolve("plugin.toml");
        if (!Files.isRegularFile(manifest)) {
            // 异构插件库不一定有 plugin.toml（比如 GeneralBot 用 plugin.json）。
            // 那种情况返回空清单：插件身份来自库索引，显示名和描述由子扫描器产出的目录提供。
            return EMPTY;
        }
        TomlParseResult toml = Toml.parse(manifest);
        if (toml.hasErrors()) {
            throw new IOException("plugin.toml 解析失败: " + toml.errors());
        }

        List<String> capabilities = new ArrayList<>();
        TomlArray values = toml.getArray("plugin.capabilities");
        if (values != null) {
            for (int index = 0; index < values.size(); index++) {
                String value = values.getString(index);
                if (value != null && !value.isBlank()) {
                    capabilities.add(value.strip());
                }
            }
        }
        List<AdapterManifest> adapters = new ArrayList<>();
        // tomlj 1.1.1 的 getArray 会把「表数组」判定为异构数组并抛异常，
        // [[adapters]] 必须通过 get + TomlArray 读，不能走 getArray。
        Object adaptersValue = toml.get("adapters");
        if (adaptersValue instanceof TomlArray adapterValues) {
            for (int index = 0; index < adapterValues.size(); index++) {
                TomlTable table = adapterValues.getTable(index);
                if (table == null) {
                    continue;
                }
                adapters.add(
                        new AdapterManifest(
                                firstText(table, "type", "connection_type"),
                                firstText(table, "entry"),
                                firstText(table, "connection_schema"),
                                firstText(table, "protocol_version"),
                                firstText(table, "schema_version"),
                                firstText(table, "events_dir"),
                                firstText(table, "actions_dir")));
            }
        }

        return new PluginManifest(
                text(toml, "plugin.key"),
                text(toml, "plugin.name"),
                text(toml, "plugin.version"),
                text(toml, "plugin.description"),
                text(toml, "plugin.homepage"),
                text(toml, "plugin.author"),
                List.copyOf(capabilities),
                text(toml, "manifest.mode"),
                text(toml, "adapter.entry"),
                text(toml, "adapter.connection_schema"),
                text(toml, "adapter.protocol_version"),
                text(toml, "adapter.schema_version"),
                text(toml, "adapter.events_dir"),
                text(toml, "adapter.actions_dir"),
                List.copyOf(adapters));
    }

    private static String text(TomlParseResult toml, String key) {
        String value = toml.getString(key);
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** 没有 plugin.toml 时的空清单。 */
    private static final PluginManifest EMPTY =
            new PluginManifest(
                    null, null, null, null, null, null, List.of(), null, null, null, null, null,
                    null, null, null);

    private static String firstText(org.tomlj.TomlTable table, String... keys) {
        for (String key : keys) {
            String value = table.getString(key);
            if (value != null && !value.isBlank()) return value.strip();
        }
        return null;
    }

    public record AdapterManifest(
            String type,
            String entry,
            String connectionSchema,
            String protocolVersion,
            String schemaVersion,
            String eventsDir,
            String actionsDir) {}

    public record PluginManifest(
            String key,
            String name,
            String version,
            String description,
            String homepage,
            String author,
            List<String> capabilities,
            String manifestMode,
            String adapterEntry,
            String connectionSchema,
            String protocolVersion,
            String schemaVersion,
            String eventsDir,
            String actionsDir,
            List<AdapterManifest> adapters) {}
}
