package com.loom.plugin.sync;

import com.loom.plugin.PluginProperties;
import com.loom.plugin.PluginRepoDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 发现插件库：扫 plugins 根目录，凡是含 `repo.json` 的子目录就是一个插件库。
 *
 * <p>这就是"热插拔"的入口——加一个文件夹就接入，删一个文件夹就退出，不用改配置也不用重启。 所以发现过程本身要**尽量不抛异常**：一个库的 `repo.json`
 * 写错了，只跳过它并记一条警告， 不能让其它库也同步不了。
 */
@Component
public class PluginRepoDiscovery {

    private static final Logger log = LoggerFactory.getLogger(PluginRepoDiscovery.class);

    private static final String MANIFEST_NAME = "repo.json";
    private static final String WORKING_COPY_DIR = "repo";

    private final PluginProperties properties;
    private final ObjectMapper objectMapper;

    public PluginRepoDiscovery(PluginProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public List<PluginRepoDefinition> discover() {
        Path root = Path.of(properties.pluginsRoot()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            log.debug("插件库根目录不存在，跳过发现: {}", root);
            return List.of();
        }
        List<PluginRepoDefinition> found = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        List<Path> folders;
        try (var entries = Files.list(root)) {
            folders = entries.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            log.warn("扫描插件库根目录失败: {}", root, e);
            return List.of();
        }
        for (Path folder : folders) {
            Path manifest = folder.resolve(MANIFEST_NAME);
            if (!Files.isRegularFile(manifest)) {
                continue;
            }
            try {
                PluginRepoDefinition definition = read(folder, manifest);
                if (!seenKeys.add(definition.key())) {
                    log.warn("插件库 key 重复，跳过: key={} folder={}", definition.key(), folder);
                    continue;
                }
                found.add(definition);
            } catch (RuntimeException e) {
                log.warn("插件库声明无法解析，跳过: {} ({})", manifest, e.getMessage());
            }
        }
        return found;
    }

    /**
     * 读一个库文件夹的声明；解析失败抛 {@link IllegalStateException}。
     *
     * <p>管理接口也要用同一份解析，否则"页面能存进去"和"发现得出来"会各判一套。
     */
    public PluginRepoDefinition readDefinition(Path folder) {
        return read(folder, folder.resolve(MANIFEST_NAME));
    }

    private PluginRepoDefinition read(Path folder, Path manifest) {
        RepoManifest parsed;
        try {
            parsed = objectMapper.readValue(manifest.toFile(), RepoManifest.class);
        } catch (RuntimeException e) {
            throw new IllegalStateException("repo.json 解析失败: " + e.getMessage(), e);
        }
        if (parsed == null || parsed.key() == null || parsed.key().isBlank()) {
            throw new IllegalStateException("repo.json 缺少 key");
        }
        String url = parsed.url() == null ? "" : parsed.url().strip();
        String branch =
                parsed.branch() == null || parsed.branch().isBlank()
                        ? "main"
                        : parsed.branch().strip();
        String scanner =
                parsed.scanner() == null || parsed.scanner().isBlank()
                        ? "loom"
                        : parsed.scanner().strip();
        return new PluginRepoDefinition(
                parsed.key().strip(),
                url,
                branch,
                scanner,
                folder,
                folder.resolve(WORKING_COPY_DIR));
    }

    /** repo.json 的形状。字段都可选，除了 key。 */
    private record RepoManifest(String key, String url, String branch, String scanner) {}
}
