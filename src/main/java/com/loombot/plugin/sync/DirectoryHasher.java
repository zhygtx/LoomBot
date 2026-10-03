package com.loombot.plugin.sync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Set;

/** 对插件版本目录做稳定哈希：相对路径参与摘要，文件顺序固定。 */
public final class DirectoryHasher {

    private static final Set<String> IGNORED_DIRECTORIES =
            Set.of(".git", "__pycache__", ".venv", "venv", ".pytest_cache", ".mypy_cache");

    private DirectoryHasher() {}

    public static String sha256(Path root) throws IOException {
        MessageDigest digest = sha256Digest();
        try (var paths = Files.walk(root)) {
            for (Path file :
                    paths.filter(Files::isRegularFile)
                            .filter(path -> !ignored(root.relativize(path)))
                            .sorted(Comparator.comparing(path -> portable(root.relativize(path))))
                            .toList()) {
                digest.update(portable(root.relativize(file)).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(file));
                digest.update((byte) 0);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String sha256(String value) {
        MessageDigest digest = sha256Digest();
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境不支持 SHA-256", e);
        }
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static boolean ignored(Path relative) {
        for (Path part : relative) {
            if (IGNORED_DIRECTORIES.contains(part.toString())) {
                return true;
            }
        }
        String name = relative.getFileName() == null ? "" : relative.getFileName().toString();
        return name.endsWith(".pyc") || name.endsWith(".pyo");
    }
}
