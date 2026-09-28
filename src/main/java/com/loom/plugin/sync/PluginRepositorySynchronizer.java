package com.loom.plugin.sync;

import com.loom.plugin.PluginProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 插件仓库同步器。
 *
 * <p>只负责“把仓库工作区更新到请求的分支并解析当前 commit”，不解析插件清单。 所有 git 操作都限制在配置的插件目录内。
 */
@Component
public class PluginRepositorySynchronizer {

    private static final Logger log = LoggerFactory.getLogger(PluginRepositorySynchronizer.class);

    private final PluginProperties properties;

    public PluginRepositorySynchronizer(PluginProperties properties) {
        this.properties = properties;
    }

    public RepositorySnapshot synchronize() throws IOException, InterruptedException {
        Path root = Path.of(properties.localPath()).toAbsolutePath().normalize();
        syncGitIfConfigured(root);
        if (!Files.isDirectory(root)) {
            throw new IOException("插件仓库目录不存在: " + root);
        }
        String commit = resolveCommit(root);
        log.info("插件仓库已就绪: path={}, commit={}", root, commit);
        return new RepositorySnapshot(root, commit);
    }

    private void syncGitIfConfigured(Path root) throws IOException, InterruptedException {
        if (properties.repositoryUrl().isBlank()) {
            return;
        }
        boolean hasGit = Files.isDirectory(root.resolve(".git"));
        if (!hasGit) {
            Files.createDirectories(root.getParent());
            run(
                    List.of(
                            "git",
                            "clone",
                            "--depth",
                            "1",
                            "--branch",
                            properties.branch(),
                            properties.repositoryUrl(),
                            root.toString()));
            return;
        }
        run(
                List.of(
                        "git",
                        "-C",
                        root.toString(),
                        "fetch",
                        "--depth",
                        "1",
                        "origin",
                        properties.branch()));
        run(List.of("git", "-C", root.toString(), "checkout", properties.branch()));
        run(
                List.of(
                        "git",
                        "-C",
                        root.toString(),
                        "pull",
                        "--ff-only",
                        "origin",
                        properties.branch()));
    }

    private String resolveCommit(Path root) throws IOException, InterruptedException {
        if (Files.isDirectory(root.resolve(".git"))) {
            String output = run(List.of("git", "-C", root.toString(), "rev-parse", "HEAD")).strip();
            return output.isBlank() ? "unknown" : output;
        }
        Path index = root.resolve("index.json");
        if (!Files.isRegularFile(index)) {
            throw new IOException("插件仓库缺少 index.json: " + index);
        }
        return "local-" + DirectoryHasher.sha256(root);
    }

    private String run(List<String> command) throws IOException, InterruptedException {
        Process process =
                new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .directory(Path.of(".").toAbsolutePath().normalize().toFile())
                        .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(properties.commandTimeoutSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("git 命令超时: " + String.join(" ", command));
        }
        if (process.exitValue() != 0) {
            throw new IOException(
                    "git 命令失败("
                            + process.exitValue()
                            + "): "
                            + String.join(" ", command)
                            + System.lineSeparator()
                            + output);
        }
        return output;
    }

    public record RepositorySnapshot(Path root, String commitHash) {}
}
