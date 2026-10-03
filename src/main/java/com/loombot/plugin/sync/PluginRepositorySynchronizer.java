package com.loombot.plugin.sync;

import com.loombot.plugin.PluginProperties;
import com.loombot.plugin.PluginRepoDefinition;
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
 * 插件库同步器：把某个库的工作副本更新到声明的分支，并解析当前 commit。
 *
 * <p>只碰工作副本（`<库文件夹>/repo`），不碰库文件夹里的其它东西——子扫描器和 `repo.json` 都在 工作副本外面，`git pull` 覆盖不到它们。
 */
@Component
public class PluginRepositorySynchronizer {

    private static final Logger log = LoggerFactory.getLogger(PluginRepositorySynchronizer.class);

    private final PluginProperties properties;

    public PluginRepositorySynchronizer(PluginProperties properties) {
        this.properties = properties;
    }

    public RepositorySnapshot synchronize(PluginRepoDefinition definition)
            throws IOException, InterruptedException {
        Path root = definition.workingCopy().toAbsolutePath().normalize();
        syncGitIfConfigured(definition, root);
        if (!Files.isDirectory(root)) {
            throw new IOException("插件库工作副本不存在: " + root);
        }
        String commit = resolveCommit(root, definition.url().isBlank());
        log.info("插件库已就绪: key={}, path={}, commit={}", definition.key(), root, commit);
        return new RepositorySnapshot(root, commit);
    }

    private void syncGitIfConfigured(PluginRepoDefinition definition, Path root)
            throws IOException, InterruptedException {
        if (definition.url().isBlank()) {
            // 纯本地库：不 clone 也不 pull，直接把目录当工作副本用
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
                            definition.branch(),
                            definition.url(),
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
                        definition.branch()));
        run(List.of("git", "-C", root.toString(), "checkout", definition.branch()));
        run(
                List.of(
                        "git",
                        "-C",
                        root.toString(),
                        "pull",
                        "--ff-only",
                        "origin",
                        definition.branch()));
    }

    private String resolveCommit(Path root, boolean localLibrary)
            throws IOException, InterruptedException {
        // 纯本地库（url 为空）永远按目录内容算版本号：它很可能本身就是个 git 仓库，但作者改代码时
        // 不会先提交——按 HEAD 算会让同步器认为"内容没变"直接跳过扫描，改动就永远扫不出来。
        if (!localLibrary && Files.isDirectory(root.resolve(".git"))) {
            String output = run(List.of("git", "-C", root.toString(), "rev-parse", "HEAD")).strip();
            return output.isBlank() ? "unknown" : output;
        }
        // 没有 commit 可用时，用目录内容摘要当版本号：内容变了才会重扫。
        Path index = root.resolve("index.json");
        if (!Files.isRegularFile(index)) {
            throw new IOException("插件库缺少 index.json: " + index);
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
