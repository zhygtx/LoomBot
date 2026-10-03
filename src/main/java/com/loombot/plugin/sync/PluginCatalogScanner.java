package com.loombot.plugin.sync;

import com.loombot.config.AdapterProperties;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 调用主扫描器，把一个插件库扫成统一目录。
 *
 * <p>**一次扫完整个库**，而不是一个插件起一次进程：一个有几十个插件的库，原来要起几十次 Python， 每次约 100ms，全量重扫时这个开销比解析本身还大。
 *
 * <p>主扫描器（`system.scanner.describe`）自己不做任何自定义解析，它按名字找到插件库声明的子扫描器， 由子扫描器负责"这个库的目录长什么样"。所以 Java
 * 这边完全不用区分插件库来自哪种格式。
 */
@Component
public class PluginCatalogScanner {

    private static final Logger log = LoggerFactory.getLogger(PluginCatalogScanner.class);
    private static final long TIMEOUT_SECONDS = 300L;
    private static final String DEFAULT_SCANNER = "loombot";

    private final AdapterProperties adapterProperties;
    private final ObjectMapper objectMapper;

    public PluginCatalogScanner(AdapterProperties adapterProperties, ObjectMapper objectMapper) {
        this.adapterProperties = adapterProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 扫描一个插件库文件夹。
     *
     * @param repoFolder 插件库文件夹（含 repo/ 与 repo.json）
     * @param scanner 子扫描器名字：内置的 loombot，或 local（库文件夹里的 scanner.py）
     */
    public RepoCatalog scanRepository(Path repoFolder, String scanner) {
        Path workingDirectory =
                Path.of(adapterProperties.workingDirectory()).toAbsolutePath().normalize();
        String scannerName =
                scanner == null || scanner.isBlank() ? DEFAULT_SCANNER : scanner.strip();
        List<String> command =
                List.of(
                        adapterProperties.pythonCommand(),
                        "-m",
                        "system.scanner.describe",
                        repoFolder.toString(),
                        "--scanner",
                        scannerName);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.environment().put("PYTHONUTF8", "1");
        File errorFile = null;
        try {
            errorFile = Files.createTempFile("loombot-plugin-scan", ".err").toFile();
            builder.redirectError(errorFile);
            Process process = builder.start();
            byte[] stdout = process.getInputStream().readAllBytes();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("插件库扫描超时: " + repoFolder);
            }
            String output = new String(stdout, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                String reason = Files.readString(errorFile.toPath(), StandardCharsets.UTF_8);
                throw new IllegalStateException("插件库扫描失败: " + reason.strip());
            }
            return new RepoCatalog(objectMapper.readTree(output));
        } catch (IOException e) {
            throw new IllegalStateException("插件库扫描失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("插件库扫描被中断", e);
        } finally {
            if (errorFile != null) {
                try {
                    Files.deleteIfExists(errorFile.toPath());
                } catch (IOException e) {
                    log.debug("清理扫描临时文件失败: {}", errorFile, e);
                }
            }
        }
    }

    /** 整份库目录：一个库里的全部插件与版本。 */
    public record RepoCatalog(JsonNode root) {

        public List<ScannedPlugin> plugins() {
            List<ScannedPlugin> plugins = new ArrayList<>();
            for (JsonNode plugin : root.path("plugins")) {
                List<ScannedVersion> versions = new ArrayList<>();
                for (JsonNode version : plugin.path("versions")) {
                    versions.add(
                            new ScannedVersion(
                                    text(version.path("version")),
                                    text(version.path("path")),
                                    text(version.path("publishedTime")),
                                    new Catalog(version.path("catalog"))));
                }
                plugins.add(
                        new ScannedPlugin(
                                text(plugin.path("key")),
                                text(plugin.path("namespace")),
                                versions));
            }
            return plugins;
        }

        private static String text(JsonNode node) {
            return node == null || node.isMissingNode() || node.isNull()
                    ? ""
                    : node.asString("").strip();
        }
    }

    /** 库里的一个插件。 */
    public record ScannedPlugin(String key, String namespace, List<ScannedVersion> versions) {}

    /** 插件的一个版本，带上它的节点目录。 */
    public record ScannedVersion(
            String version, String path, String publishedTime, Catalog catalog) {}

    /** 单个插件版本的节点目录。 */
    public record Catalog(JsonNode root) {

        public String pluginKey() {
            return text(root.path("pluginKey"));
        }

        public String pluginVersion() {
            return text(root.path("pluginVersion"));
        }

        /** 子扫描器给出的显示名；异构库没有 plugin.toml 时用它。 */
        public String pluginName() {
            return text(root.path("pluginName"));
        }

        public String pluginDescription() {
            return text(root.path("pluginDescription"));
        }

        public List<String> capabilities() {
            List<String> values = new ArrayList<>();
            for (JsonNode item : root.path("capabilities")) {
                String value = text(item);
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
            return values;
        }

        public List<String> adapterTypes() {
            List<String> values = new ArrayList<>();
            for (JsonNode adapter : root.path("adapters")) {
                values.add(text(adapter.path("adapterType")));
            }
            return values;
        }

        /** 全部节点：适配器事件、适配器动作、普通节点。 */
        public List<ScannedNode> nodes() {
            List<ScannedNode> nodes = new ArrayList<>();
            for (JsonNode adapter : root.path("adapters")) {
                String connectionType = text(adapter.path("adapterType"));
                for (JsonNode node : adapter.path("nodes")) {
                    nodes.add(adapterNode(node, connectionType));
                }
                for (JsonNode node : adapter.path("actions")) {
                    nodes.add(adapterNode(node, connectionType));
                }
            }
            for (JsonNode node : root.path("nodes")) {
                nodes.add(workflowNode(node));
            }
            return nodes;
        }

        private ScannedNode adapterNode(JsonNode node, String connectionType) {
            String nodeType = text(node.path("nodeType"));
            boolean event = "EVENT".equals(nodeType);
            String inputSchema = event ? null : jsonOrNull(node.path("paramsSchema"));
            String outputSchema =
                    event
                            ? jsonOrNull(node.path("payloadSchema"))
                            : jsonOrNull(node.path("resultSchema"));
            return new ScannedNode(
                    text(node.path("nodeKey")),
                    nodeType,
                    connectionType,
                    text(node.path("name")),
                    text(node.path("description")),
                    text(node.path("sourceRef")),
                    node.path("sort").asInt(0),
                    inputSchema,
                    outputSchema,
                    null);
        }

        private ScannedNode workflowNode(JsonNode node) {
            return new ScannedNode(
                    text(node.path("nodeKey")),
                    "NODE",
                    null,
                    text(node.path("name")),
                    text(node.path("description")),
                    text(node.path("sourceRef")),
                    node.path("sort").asInt(0),
                    node.path("parameters").isMissingNode()
                            ? null
                            : node.path("parameters").toString(),
                    node.path("returnFields").isMissingNode()
                            ? null
                            : node.path("returnFields").toString(),
                    text(node.path("signatureHash")));
        }

        private static String jsonOrNull(JsonNode node) {
            return node == null || node.isNull() || node.isMissingNode() ? null : node.toString();
        }

        private static String text(JsonNode node) {
            return node == null || node.isMissingNode() || node.isNull()
                    ? ""
                    : node.asString("").strip();
        }
    }

    /** 目录里的一个节点。 */
    public record ScannedNode(
            String nodeKey,
            String nodeType,
            String connectionType,
            String name,
            String description,
            String sourceRef,
            int sort,
            String inputSchema,
            String outputSchema,
            String signatureHash) {}
}
