package com.loom.plugin.sync;

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
            throw new IOException("缺少 plugin.toml: " + manifest);
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
