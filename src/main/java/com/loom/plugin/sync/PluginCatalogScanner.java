package com.loom.plugin.sync;

import com.loom.config.AdapterProperties;
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
 * 调用 Python 扫描入口导出插件目录。
 *
 * <p>一次调用覆盖该插件版本的全部适配器、工作流节点和实体。扫描只读取模块与装饰器元数据，不建立连接、不访问网络。
 */
@Component
public class PluginCatalogScanner {

    private static final Logger log = LoggerFactory.getLogger(PluginCatalogScanner.class);
    private static final long TIMEOUT_SECONDS = 120L;

    private final AdapterProperties adapterProperties;
    private final ObjectMapper objectMapper;

    public PluginCatalogScanner(AdapterProperties adapterProperties, ObjectMapper objectMapper) {
        this.adapterProperties = adapterProperties;
        this.objectMapper = objectMapper;
    }

    /** 扫描一个插件目录，返回节点目录。 */
    public Catalog scan(Path pluginDir) {
        Path workingDirectory =
                Path.of(adapterProperties.workingDirectory()).toAbsolutePath().normalize();
        List<String> command =
                List.of(
                        adapterProperties.pythonCommand(),
                        "-m",
                        "system.scanner.describe",
                        pluginDir.toString());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.environment().put("PYTHONUTF8", "1");
        File errorFile = null;
        try {
            errorFile = Files.createTempFile("loom-plugin-scan", ".err").toFile();
            builder.redirectError(errorFile);
            Process process = builder.start();
            byte[] stdout = process.getInputStream().readAllBytes();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("插件目录扫描超时: " + pluginDir);
            }
            String output = new String(stdout, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                String reason = Files.readString(errorFile.toPath(), StandardCharsets.UTF_8);
                throw new IllegalStateException("插件目录扫描失败: " + reason.strip());
            }
            return new Catalog(objectMapper.readTree(output));
        } catch (IOException e) {
            throw new IllegalStateException("插件目录扫描失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("插件目录扫描被中断", e);
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

    /** 扫描结果。 */
    public record Catalog(JsonNode root) {

        public String pluginKey() {
            return text(root.path("pluginKey"));
        }

        public String pluginVersion() {
            return text(root.path("pluginVersion"));
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
